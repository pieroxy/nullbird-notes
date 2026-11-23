package net.pieroxy.notes.export;

import net.pieroxy.notes.api.metadata.InvalidInputException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NotesFileTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private Path file(byte[] bytes) throws Exception {
    Path file = tmp.newFile().toPath();
    Files.write(file, bytes);
    return file;
  }

  private Path file(String text) throws Exception {
    return file(text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void readsSimplenotesFieldsAndSkipsTheOthers() throws Exception {
    String json = """
        {"activeNotes": [{"id": "abc", "content": "Hello", "creationDate": "2020-01-02T03:04:05.000Z",
          "lastModified": "2021-01-02T03:04:05.123Z", "tags": ["a", 3, "b"], "pinned": true, "markdown": false,
          "collaboratorEmails": [], "publicURL": "https://example.com"}],
         "trashedNotes": [{"id": "def", "content": "Bye", "creationDate": "not a date", "options": {"k": "v", "n": 1}}],
         "somethingElse": {"x": 1}}""";

    List<NotesFile.Note> notes = NotesFile.read(file(json), 1 << 20, 10);

    assertEquals(2, notes.size());
    NotesFile.Note hello = notes.get(0);
    assertEquals("abc", hello.id());
    assertEquals("Hello", hello.content());
    assertEquals(Long.valueOf(Instant.parse("2020-01-02T03:04:05Z").toEpochMilli()), hello.createdAt());
    assertEquals(Long.valueOf(Instant.parse("2021-01-02T03:04:05.123Z").toEpochMilli()), hello.modifiedAt());
    assertEquals(List.of("a", "b"), hello.tags());
    assertFalse(hello.deleted());
    NotesFile.Note bye = notes.get(1);
    assertTrue(bye.deleted());
    assertNull(bye.createdAt());
    assertEquals(Map.of("k", "v"), bye.options());
  }

  @Test
  public void booleanFieldsBecomeOptions() throws Exception {
    String json = """
        {"activeNotes": [{"id": "a", "content": "Both", "pinned": true, "markdown": true},
                         {"id": "b", "content": "Neither", "pinned": false, "markdown": "yes"},
                         {"id": "c", "content": "Ours", "monospace": true, "nowrap": true, "options": {"pinned": "true", "color": "blue"}}]}""";

    List<NotesFile.Note> notes = NotesFile.read(file(json), 1 << 20, 10);

    assertEquals(Map.of("pinned", "true", "markdown", "true"), notes.get(0).options());
    assertEquals(Map.of(), notes.get(1).options());
    assertEquals(Map.of("pinned", "true", "color", "blue", "monospace", "true", "nowrap", "true"), notes.get(2).options());
  }

  @Test
  public void aNoteWithoutContentIsEmpty() throws Exception {
    assertEquals("", NotesFile.read(file("{\"activeNotes\": [{\"id\": \"x\"}]}"), 1 << 20, 10).get(0).content());
  }

  @Test
  public void tooManyNotes() throws Exception {
    assertRefused(file("{\"activeNotes\": [{}, {}, {}]}"), 1 << 20, 2, "more than 2 notes");
  }

  @Test
  public void aZipThatUnpacksTooBigIsStoppedEarly() throws Exception {
    // 10 MB of spaces compress to almost nothing.
    String json = "{\"activeNotes\": [" + " ".repeat(10 * 1024 * 1024) + "]}";
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("source/notes.json"));
      zip.write(json.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    assertTrue(bytes.size() < 100_000);

    assertRefused(file(bytes.toByteArray()), 1024 * 1024, 10, "more than 1 MB");
  }

  @Test
  public void aDamagedZip() throws Exception {
    byte[] bytes = {'P', 'K', 3, 4, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    try {
      NotesFile.read(file(bytes), 1 << 20, 10);
      fail("Read");
    } catch (InvalidInputException e) {
      // Damaged, or no notes.json found in it: either way, said.
    }
  }

  @Test
  public void whereNotesJsonMayBe() {
    assertTrue(NotesFile.isNotesJson("source/notes.json"));
    assertTrue(NotesFile.isNotesJson("notes.json"));
    assertTrue(NotesFile.isNotesJson("notes-2026/source/notes.json"));
    assertFalse(NotesFile.isNotesJson("a/b/source/notes.json"));
    assertFalse(NotesFile.isNotesJson("source/other.json"));
  }

  @Test
  public void notesComeOneAtATimeAndTheHandlersFailureIsItsOwn() throws Exception {
    Path file = file("{\"activeNotes\": [{\"content\": \"one\"}, {\"content\": \"two\"}, {\"content\": \"three\"}]}");
    List<String> seen = new java.util.ArrayList<>();
    try {
      NotesFile.read(file, 1 << 20, 10, note -> {
        seen.add(note.content());
        if (seen.size() == 2) throw new IllegalStateException("disk full");
      });
      fail("Read");
    } catch (IllegalStateException e) {
      // Not mistaken for a file that isn't an export.
      assertEquals("disk full", e.getMessage());
    }
    assertEquals(List.of("one", "two"), seen);

    try {
      NotesFile.read(file, 1 << 20, 10, note -> {
        throw new java.io.IOException("no space left");
      });
      fail("Read");
    } catch (InvalidInputException e) {
      fail("Taken for the file's fault: " + e.getMessage());
    } catch (java.io.IOException e) {
      assertEquals("no space left", e.getMessage());
    }
  }

  private static void assertRefused(Path file, long maxBytes, int maxNotes, String expected) throws Exception {
    try {
      NotesFile.read(file, maxBytes, maxNotes);
      fail("Read");
    } catch (InvalidInputException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(expected));
    }
  }
}
