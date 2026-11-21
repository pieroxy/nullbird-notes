package net.pieroxy.notes.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.pieroxy.notes.storage.MetaWrite;
import net.pieroxy.notes.storage.NoteMeta;
import net.pieroxy.notes.storage.NoteWrite;
import net.pieroxy.notes.storage.UserNoteStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SimplenoteExportTest {
  private static final String A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  private static final String C = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  /** Starts at 2026-01-02T03:04:05Z, one second further on each read. */
  private static class TickingClock extends Clock {
    private long millis = Instant.parse("2026-01-02T03:04:05Z").toEpochMilli();

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public synchronized Instant instant() {
      Instant now = Instant.ofEpochMilli(millis);
      millis += 1000;
      return now;
    }
  }

  private UserNoteStore store;
  private int changes;

  @Before
  public void setUp() throws Exception {
    store = UserNoteStore.open(tmp.getRoot().toPath(), new TickingClock());
  }

  private NoteMeta write(String id, int baseRevision, String content, boolean deleted) throws Exception {
    return store.commit(id, baseRevision, new NoteWrite(content, deleted, "c" + (++changes), "device", null));
  }

  private void tag(String id, String... tags) throws Exception {
    store.commitMeta(id, new MetaWrite("m" + (++changes), "device", List.of(tags), null, null, null));
  }

  private byte[] exportBytes() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    SimplenoteExport.write(store, out);
    return out.toByteArray();
  }

  /** Entry name to content, in the zip's order. */
  private Map<String, String> export() throws Exception {
    Map<String, String> entries = new LinkedHashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(exportBytes()), StandardCharsets.UTF_8)) {
      for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
    }
    return entries;
  }

  private static JsonObject notesJson(Map<String, String> entries) {
    return JsonParser.parseString(entries.get(SimplenoteExport.NOTES_JSON)).getAsJsonObject();
  }

  @Test
  public void anEmptyStore() throws Exception {
    Map<String, String> entries = export();

    assertEquals(List.of("source/", SimplenoteExport.NOTES_JSON), new ArrayList<>(entries.keySet()));
    assertEquals("{\r\n  \"activeNotes\": [],\r\n  \"trashedNotes\": []\r\n}", entries.get(SimplenoteExport.NOTES_JSON));
  }

  @Test
  public void notesJsonAsSimplenoteWritesIt() throws Exception {
    write(A, 0, "Groceries\nmilk\n", false);
    write(A, 1, "Groceries\nmilk\neggs\n", false);
    tag(A, "home", "todo");

    String json = export().get(SimplenoteExport.NOTES_JSON);

    assertEquals("{\r\n"
        + "  \"activeNotes\": [\r\n"
        + "    {\r\n"
        + "      \"id\": \"" + A + "\",\r\n"
        + "      \"content\": \"Groceries\\nmilk\\neggs\\n\",\r\n"
        + "      \"creationDate\": \"2026-01-02T03:04:05.000Z\",\r\n"
        + "      \"lastModified\": \"2026-01-02T03:04:06.000Z\",\r\n"
        + "      \"tags\": [\r\n"
        + "        \"home\",\r\n"
        + "        \"todo\"\r\n"
        + "      ]\r\n"
        + "    }\r\n"
        + "  ],\r\n"
        + "  \"trashedNotes\": []\r\n"
        + "}", json);
  }

  @Test
  public void noTagsNoTagsField() throws Exception {
    write(A, 0, "Plain", false);

    JsonObject note = notesJson(export()).getAsJsonArray("activeNotes").get(0).getAsJsonObject();

    assertFalse(note.has("tags"));
    assertFalse(note.has("options"));
  }

  @Test
  public void optionsWhenSetWhichSimplenoteDoesntHave() throws Exception {
    write(A, 0, "Code", false);
    store.commitMeta(A, new MetaWrite("m1", "device", null, null, Map.of("monospace", "1"), null));

    JsonObject note = notesJson(export()).getAsJsonArray("activeNotes").get(0).getAsJsonObject();

    assertEquals("1", note.getAsJsonObject("options").get("monospace").getAsString());
  }

  @Test
  public void booleanOptionsAsSimplenotesPinnedAndMarkdown() throws Exception {
    write(A, 0, "Both", false);
    store.commitMeta(A, new MetaWrite("m1", "device", null, null, Map.of("pinned", "true", "markdown", "true", "monospace", "true",
        "nowrap", "true", "color", "blue"), null));
    write(B, 0, "Neither", false);
    store.commitMeta(B, new MetaWrite("m2", "device", null, null, Map.of("pinned", "no"), null));

    JsonArray notes = notesJson(export()).getAsJsonArray("activeNotes");
    JsonObject both = notes.get(0).getAsJsonObject().get("content").getAsString().equals("Both") ? notes.get(0).getAsJsonObject()
        : notes.get(1).getAsJsonObject();
    JsonObject neither = both == notes.get(0).getAsJsonObject() ? notes.get(1).getAsJsonObject() : notes.get(0).getAsJsonObject();

    for (String option : List.of("pinned", "markdown", "monospace", "nowrap")) {
      assertTrue(option, both.get(option).getAsBoolean());
      assertFalse(option, neither.has(option));
    }
    // An option the app doesn't know: as is, in options.
    assertEquals("{\"color\":\"blue\"}", both.getAsJsonObject("options").toString());
    // Not "true": not a pinned note, but still an option the user has.
    assertEquals("no", neither.getAsJsonObject("options").get("pinned").getAsString());
  }

  @Test
  public void nonAsciiIsWrittenAsIs() throws Exception {
    write(A, 0, "Café <b> & \"quotes\"\ttab", false);

    String json = export().get(SimplenoteExport.NOTES_JSON);

    assertTrue(json, json.contains("\"content\": \"Café <b> & \\\"quotes\\\"\\ttab\""));
  }

  @Test
  public void deletedNotesGoToTrash() throws Exception {
    write(A, 0, "Kept", false);
    write(B, 0, "Thrown away", false);
    write(B, 1, "Thrown away", true);

    Map<String, String> entries = export();

    assertEquals(List.of("source/", SimplenoteExport.NOTES_JSON, "Kept.txt", "trash/", "trash/Thrown away.txt"), new ArrayList<>(entries.keySet()));
    JsonArray trashed = notesJson(entries).getAsJsonArray("trashedNotes");
    assertEquals(1, trashed.size());
    assertEquals("Thrown away", trashed.get(0).getAsJsonObject().get("content").getAsString());
  }

  @Test
  public void mostRecentlyModifiedFirstInTheJsonAndTheFiles() throws Exception {
    write(A, 0, "Old", false);
    write(B, 0, "Newer", false);
    write(A, 1, "Old, edited last", false);

    Map<String, String> entries = export();

    JsonArray notes = notesJson(entries).getAsJsonArray("activeNotes");
    assertEquals(A, notes.get(0).getAsJsonObject().get("id").getAsString());
    assertEquals(B, notes.get(1).getAsJsonObject().get("id").getAsString());
    assertEquals(List.of("source/", SimplenoteExport.NOTES_JSON, "Old, edited last.txt", "Newer.txt"), new ArrayList<>(entries.keySet()));
  }

  @Test
  public void textFilesEndWithTheTags() throws Exception {
    write(A, 0, "Groceries\nmilk", false);
    tag(A, "home", "todo");

    assertEquals("Groceries\nmilk\n\nTags:\n  home, todo", export().get("Groceries.txt"));
  }

  @Test
  public void duplicateNamesCountUpInTheOrderWritten() throws Exception {
    write(A, 0, "Same", false);
    write(B, 0, "Same", false);
    write(C, 0, "SAME", false);

    Map<String, String> entries = export();

    // C, B, A: most recently modified first. Case matters.
    assertEquals(List.of("source/", SimplenoteExport.NOTES_JSON, "SAME.txt", "Same.txt", "Same (1).txt"), new ArrayList<>(entries.keySet()));
  }

  @Test
  public void theTrashHasItsOwnNumbering() throws Exception {
    write(A, 0, "Same", false);
    write(B, 0, "Same", false);
    write(B, 1, "Same", true);
    write(C, 0, "Same", false);
    write(C, 1, "Same", true);

    Map<String, String> entries = export();

    assertEquals(List.of("source/", SimplenoteExport.NOTES_JSON, "Same.txt", "trash/", "trash/Same.txt", "trash/Same (1).txt"),
        new ArrayList<>(entries.keySet()));
  }

  @Test
  public void emptyNotesAreUntitledOrNamedAfterTheirTags() throws Exception {
    write(A, 0, "", false);
    write(B, 0, "\n  \n", false);
    tag(B, "home");

    Map<String, String> entries = export();

    assertEquals("\n  \n\n\nTags:\n  home", entries.get("Tags.txt"));
    assertEquals("", entries.get("untitled.txt"));
  }

  @Test
  public void crlfContentIsKeptAsIsAndTheTagsFollowWithLf() throws Exception {
    write(A, 0, "Windows\r\nline two\r\n", false);
    tag(A, "pc");

    Map<String, String> entries = export();

    assertEquals("Windows\r\nline two\r\n\n\nTags:\n  pc", entries.get("Windows.txt"));
    assertEquals("Windows\r\nline two\r\n",
        notesJson(entries).getAsJsonArray("activeNotes").get(0).getAsJsonObject().get("content").getAsString());
  }

  @Test
  public void fileNames() {
    assertEquals("Groceries", SimplenoteExport.fileName("Groceries\nmilk"));
    assertEquals("Title", SimplenoteExport.fileName("\n\n   \n  Title  \nbody"));
    assertEquals("Title", SimplenoteExport.fileName("Title\r\nbody"));
    assertEquals("ab c", SimplenoteExport.fileName("a/b: c?"));
    // No separator survives: never a path out of the zip's folder.
    assertEquals("....etcpasswd", SimplenoteExport.fileName("../../etc/passwd"));
    assertEquals("..passwd", SimplenoteExport.fileName("..\\passwd"));
    assertEquals("Question", SimplenoteExport.fileName("Question ?"));
    assertEquals(" item", SimplenoteExport.fileName("* item"));
    assertEquals("Wait", SimplenoteExport.fileName("Wait..."));
    assertEquals("tabs", SimplenoteExport.fileName("ta\tbs"));
    assertEquals("No-break", SimplenoteExport.fileName("\u00a0No-break\u00a0"));
    assertEquals("untitled", SimplenoteExport.fileName(""));
    assertEquals("untitled", SimplenoteExport.fileName("\n \n"));
    assertEquals("untitled", SimplenoteExport.fileName("???"));
    assertEquals("Tags", SimplenoteExport.fileName(SimplenoteExport.textFile("", List.of("home"))));
  }

  @Test
  public void namesAreCutAt40Utf16UnitsNeverHalfAnEmoji() {
    assertEquals("x".repeat(40), SimplenoteExport.fileName("x".repeat(60)));
    // Cut at 40 then trailing dots go, but not trailing spaces: as Simplenote does.
    assertEquals("x".repeat(39) + " ", SimplenoteExport.fileName("x".repeat(39) + " z"));
    assertEquals("x".repeat(38), SimplenoteExport.fileName("x".repeat(38) + "..z"));
    assertEquals("x".repeat(39), SimplenoteExport.fileName("x".repeat(39) + "😀"));
    assertEquals("x".repeat(38) + "😀", SimplenoteExport.fileName("x".repeat(38) + "😀z"));
  }
}
