package net.pieroxy.notes.export;

import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.pieroxy.notes.api.metadata.InvalidInputException;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Reads the notes of an export, ours or Simplenote's (see {@link SimplenoteExport}): the .zip, of
 * which only {@code source/notes.json} is read (the .txt files are made from it), or that
 * notes.json on its own. Told apart by their first bytes, whatever the file is called.
 * <p>
 * Lenient on what it doesn't need: fields it doesn't know (Simplenote's {@code pinned},
 * {@code markdown}, {@code publicURL}...) are skipped, and a date it can't read is null. Strict on
 * size: the uncompressed notes.json can't exceed {@code maxJsonBytes}, so a small zip can't unpack
 * into gigabytes.
 */
public final class NotesFile {
  /**
   * One note of the file, as it is there.
   *
   * @param id         as in the file: anything, or null.
   * @param content    never null: a note without one is empty.
   * @param createdAt  epoch milliseconds; null if absent or unreadable.
   * @param modifiedAt as createdAt.
   * @param deleted    from trashedNotes.
   * @param options    string values only: ours never has others.
   */
  public record Note(String id, String content, Long createdAt, Long modifiedAt, boolean deleted, List<String> tags,
                     Map<String, String> options) {}

  private static final String NOTES_JSON = "notes.json";

  private NotesFile() {}

  /** @throws InvalidInputException if it's not an export, or too big: the message says why, for the user. */
  public static List<Note> read(Path file, long maxJsonBytes, int maxNotes) throws IOException, InvalidInputException {
    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
      in.mark(4);
      byte[] start = in.readNBytes(4);
      in.reset();
      if (start.length == 4 && start[0] == 'P' && start[1] == 'K' && start[2] == 3 && start[3] == 4) return readZip(in, maxJsonBytes, maxNotes);
      return readJson(in, maxJsonBytes, maxNotes);
    }
  }

  private static List<Note> readZip(InputStream in, long maxJsonBytes, int maxNotes) throws IOException {
    ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8);
    try {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (isNotesJson(entry.getName())) return readJson(zip, maxJsonBytes, maxNotes);
      }
    } catch (ZipException e) {
      throw new InvalidInputException("This .zip file is damaged: " + e.getMessage());
    }
    throw new InvalidInputException("There's no source/notes.json in this .zip file: it isn't an export of Notes or Simplenote.");
  }

  /** source/notes.json, also one folder down (the export unpacked, then zipped again), or at the root. */
  static boolean isNotesJson(String name) {
    return name.equals("source/" + NOTES_JSON) || name.equals(NOTES_JSON) || name.matches("[^/]+/source/" + NOTES_JSON);
  }

  private static List<Note> readJson(InputStream in, long maxJsonBytes, int maxNotes) throws IOException {
    JsonReader json = new JsonReader(new InputStreamReader(new Capped(in, maxJsonBytes), StandardCharsets.UTF_8));
    List<Note> notes = new ArrayList<>();
    boolean found = false;
    try {
      if (json.peek() != JsonToken.BEGIN_OBJECT) throw notAnExport();
      json.beginObject();
      while (json.hasNext()) {
        String name = json.nextName();
        if ((name.equals("activeNotes") || name.equals("trashedNotes")) && json.peek() == JsonToken.BEGIN_ARRAY) {
          found = true;
          json.beginArray();
          while (json.hasNext()) {
            if (notes.size() >= maxNotes) throw new InvalidInputException("The file has more than " + maxNotes + " notes, the most one import takes.");
            notes.add(readNote(json, name.equals("trashedNotes")));
          }
          json.endArray();
        } else {
          json.skipValue();
        }
      }
      json.endObject();
    } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
      throw notAnExport();
    } catch (IOException e) {
      // A JSON syntax error is an IOException too (MalformedJsonException, EOFException).
      if (e instanceof TooLarge) throw new InvalidInputException(e.getMessage());
      if (e instanceof ZipException) throw new InvalidInputException("This .zip file is damaged: " + e.getMessage());
      if (e.getClass().getName().startsWith("com.google.gson") || e instanceof java.io.EOFException) throw notAnExport();
      throw e;
    }
    if (!found) throw notAnExport();
    return notes;
  }

  private static InvalidInputException notAnExport() {
    return new InvalidInputException("This file isn't an export of Notes or Simplenote: a .zip file, or the notes.json in it.");
  }

  private static Note readNote(JsonReader json, boolean deleted) throws IOException {
    String id = null;
    String content = "";
    Long createdAt = null;
    Long modifiedAt = null;
    List<String> tags = new ArrayList<>();
    Map<String, String> options = new LinkedHashMap<>();
    json.beginObject();
    while (json.hasNext()) {
      String name = json.nextName();
      JsonToken type = json.peek();
      switch (name) {
        case "id" -> id = type == JsonToken.STRING || type == JsonToken.NUMBER ? json.nextString() : skip(json);
        case "content" -> content = type == JsonToken.STRING ? json.nextString() : skipTo(json, "");
        case "creationDate" -> createdAt = type == JsonToken.STRING ? date(json.nextString()) : skipTo(json, null);
        case "lastModified" -> modifiedAt = type == JsonToken.STRING ? date(json.nextString()) : skipTo(json, null);
        case "tags" -> {
          if (type != JsonToken.BEGIN_ARRAY) {
            json.skipValue();
            break;
          }
          json.beginArray();
          while (json.hasNext()) {
            if (json.peek() == JsonToken.STRING) tags.add(json.nextString());
            else json.skipValue();
          }
          json.endArray();
        }
        case "options" -> {
          if (type != JsonToken.BEGIN_OBJECT) {
            json.skipValue();
            break;
          }
          json.beginObject();
          while (json.hasNext()) {
            String key = json.nextName();
            if (json.peek() == JsonToken.STRING) options.put(key, json.nextString());
            else json.skipValue();
          }
          json.endObject();
        }
        default -> json.skipValue();
      }
    }
    json.endObject();
    return new Note(id, content, createdAt, modifiedAt, deleted, tags, options);
  }

  private static String skip(JsonReader json) throws IOException {
    json.skipValue();
    return null;
  }

  private static <T> T skipTo(JsonReader json, T value) throws IOException {
    json.skipValue();
    return value;
  }

  /** ISO 8601 as both exports write it, "2026-01-02T03:04:05.000Z"; null if it isn't. */
  static Long date(String value) {
    try {
      return Instant.parse(value).toEpochMilli();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static class TooLarge extends IOException {
    TooLarge(String message) {
      super(message);
    }
  }

  /** Fails past {@code max} bytes. */
  private static class Capped extends FilterInputStream {
    private final long max;
    private long count;

    Capped(InputStream in, long max) {
      super(in);
      this.max = max;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b >= 0) count(1);
      return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int n = super.read(buffer, offset, length);
      if (n > 0) count(n);
      return n;
    }

    private void count(int n) throws TooLarge {
      count += n;
      if (count > max) throw new TooLarge("The notes in this file take more than " + (max / (1024 * 1024)) + " MB, the most one import takes.");
    }
  }
}
