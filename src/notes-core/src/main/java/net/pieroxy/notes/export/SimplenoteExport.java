package net.pieroxy.notes.export;

import com.google.gson.FormattingStyle;
import com.google.gson.stream.JsonWriter;
import net.pieroxy.notes.storage.NoteMeta;
import net.pieroxy.notes.storage.NoteOptions;
import net.pieroxy.notes.storage.UserNoteStore;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A user's notes as a zip laid out exactly as Simplenote's export (checked against a real one), so
 * that what reads one reads the other:
 * <ul>
 * <li>{@code source/notes.json}: {@code activeNotes} and {@code trashedNotes}, most recently
 * modified first. Each note has its {@code id}, {@code content}, {@code creationDate} and
 * {@code lastModified} (ISO 8601, UTC, in milliseconds), each of {@link NoteOptions#BOOLEANS} set
 * to true when on ({@code pinned} and {@code markdown} are Simplenote's; the others, a reader of
 * Simplenote's skips), and its {@code tags} if it has any; plus, in {@code options}, any other
 * option it has. Indented by 2 spaces, CRLF line endings,
 * no final line break.</li>
 * <li>a {@code .txt} file per note, in the same order: active notes at the root, deleted ones in
 * {@code trash/}. It holds the content, then, if the note has tags, {@code "\n\nTags:\n  "} and
 * them separated by {@code ", "}. Its name comes from that text: see {@link #fileName}.</li>
 * </ul>
 * Written as it goes: neither the zip nor the notes' content is held in memory.
 */
public final class SimplenoteExport {
  public static final String NOTES_JSON = "source/notes.json";
  public static final String TRASH = "trash/";
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
  /** Removed from file names: no file system takes them, or they'd make a path. */
  private static final String FORBIDDEN = "/\\:*?\"<>|";
  /** In UTF-16 units, as Simplenote (JavaScript) counts. */
  private static final int MAX_NAME_LENGTH = 40;
  private static final String UNTITLED = "untitled";

  private SimplenoteExport() {}

  /** Writes the zip to {@code out}, which it finishes but doesn't close. */
  public static void write(UserNoteStore store, OutputStream out) throws IOException {
    List<NoteMeta> notes = store.list().stream().sorted(Comparator.comparingLong(NoteMeta::modifiedAt).reversed()).toList();
    List<NoteMeta> active = notes.stream().filter(note -> !note.deleted()).toList();
    List<NoteMeta> trashed = notes.stream().filter(NoteMeta::deleted).toList();
    ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);

    zip.putNextEntry(new ZipEntry("source/"));
    zip.closeEntry();
    zip.putNextEntry(new ZipEntry(NOTES_JSON));
    Writer writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
    JsonWriter json = new JsonWriter(writer);
    json.setFormattingStyle(FormattingStyle.PRETTY.withIndent("  ").withNewline("\r\n"));
    json.setHtmlSafe(false);
    json.beginObject();
    writeNotes(json, store, "activeNotes", active);
    writeNotes(json, store, "trashedNotes", trashed);
    json.endObject();
    json.flush();
    writer.flush();
    zip.closeEntry();

    writeTextFiles(zip, store, "", active);
    if (!trashed.isEmpty()) {
      zip.putNextEntry(new ZipEntry(TRASH));
      zip.closeEntry();
      writeTextFiles(zip, store, TRASH, trashed);
    }
    zip.finish();
  }

  private static void writeNotes(JsonWriter json, UserNoteStore store, String name, List<NoteMeta> notes) throws IOException {
    json.name(name).beginArray();
    for (NoteMeta note : notes) {
      String content = store.readContent(note.id());
      if (content == null) continue;
      json.beginObject();
      json.name("id").value(note.id());
      json.name("content").value(content);
      json.name("creationDate").value(DATE.format(Instant.ofEpochMilli(note.createdAt())));
      json.name("lastModified").value(DATE.format(Instant.ofEpochMilli(note.modifiedAt())));
      // As Simplenote's pinned and markdown: there when true only.
      for (String option : NoteOptions.BOOLEANS) {
        if (NoteOptions.isOn(note.options(), option)) json.name(option).value(true);
      }
      if (!note.tags().isEmpty()) {
        json.name("tags").beginArray();
        for (String tag : note.tags()) json.value(tag);
        json.endArray();
      }
      Map<String, String> others = new TreeMap<>(note.options());
      // Written as fields above when on; any other value stays an option.
      for (String key : NoteOptions.BOOLEANS) others.remove(key, NoteOptions.ON);
      others.remove(NoteOptions.PUBLISHED);
      if (!others.isEmpty()) {
        json.name("options").beginObject();
        for (Map.Entry<String, String> option : others.entrySet()) json.name(option.getKey()).value(option.getValue());
        json.endObject();
      }
      json.endObject();
    }
    json.endArray();
  }

  /** Duplicate names get " (1)", " (2)"… in the order written, compared as is: "Log" and "LOG" are both kept. */
  private static void writeTextFiles(ZipOutputStream zip, UserNoteStore store, String folder, List<NoteMeta> notes) throws IOException {
    Map<String, Integer> taken = new HashMap<>();
    for (NoteMeta note : notes) {
      String content = store.readContent(note.id());
      if (content == null) continue;
      String text = textFile(content, note.tags());
      String name = fileName(text);
      int seen = taken.merge(name, 1, Integer::sum) - 1;
      zip.putNextEntry(new ZipEntry(folder + (seen == 0 ? name : name + " (" + seen + ")") + ".txt"));
      zip.write(text.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
  }

  /** A note's .txt file: its content, then its tags if it has any. */
  static String textFile(String content, List<String> tags) {
    return tags.isEmpty() ? content : content + "\n\nTags:\n  " + String.join(", ", tags);
  }

  /**
   * As Simplenote names a .txt file: the first non-blank line of {@code text} (tags included, so an
   * empty note with tags is "Tags"), trimmed, without control or {@link #FORBIDDEN} characters, then
   * without trailing spaces, cut at {@link #MAX_NAME_LENGTH}, without trailing dots; "untitled" if
   * nothing's left.
   */
  static String fileName(String text) {
    String line = "";
    for (String candidate : text.split("\n", -1)) {
      if (!trimEnd(trimStart(candidate)).isEmpty()) {
        line = candidate;
        break;
      }
    }
    StringBuilder name = new StringBuilder();
    trimEnd(trimStart(line)).codePoints().forEach(c -> {
      if (c >= 0x20 && FORBIDDEN.indexOf(c) < 0) name.appendCodePoint(c);
    });
    String cleaned = trimEnd(name.toString());
    if (cleaned.length() > MAX_NAME_LENGTH) {
      // Never half an emoji.
      int end = Character.isHighSurrogate(cleaned.charAt(MAX_NAME_LENGTH - 1)) ? MAX_NAME_LENGTH - 1 : MAX_NAME_LENGTH;
      cleaned = cleaned.substring(0, end);
    }
    int end = cleaned.length();
    while (end > 0 && cleaned.charAt(end - 1) == '.') end--;
    cleaned = cleaned.substring(0, end);
    return cleaned.isEmpty() ? UNTITLED : cleaned;
  }

  /** JavaScript's String.trim() spaces, wider than Java's strip(): no-break spaces too. */
  private static boolean isSpace(char c) {
    return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\uFEFF';
  }

  private static String trimStart(String s) {
    int start = 0;
    while (start < s.length() && isSpace(s.charAt(start))) start++;
    return s.substring(start);
  }

  private static String trimEnd(String s) {
    int end = s.length();
    while (end > 0 && isSpace(s.charAt(end - 1))) end--;
    return s.substring(0, end);
  }
}
