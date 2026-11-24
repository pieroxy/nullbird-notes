package net.pieroxy.notes.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.pieroxy.notes.api.metadata.InvalidInputException;
import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.export.ImportSummary;
import net.pieroxy.notes.export.SimplenoteExport;
import net.pieroxy.notes.storage.MetaWrite;
import net.pieroxy.notes.storage.NoteMeta;
import net.pieroxy.notes.storage.NoteWrite;
import net.pieroxy.notes.storage.UserNoteStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ImportServiceTest {
  private static final String A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  private static final String C = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private final MovableClock clock = new MovableClock();
  private NoteStoreService stores;
  private ImportService imports;
  private final List<LogRecord> logs = new ArrayList<>();
  private final Handler handler = new Handler() {
    @Override
    public void publish(LogRecord record) {
      logs.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  };

  private static class MovableClock extends Clock {
    long millis = Instant.parse("2026-11-21T10:00:00Z").toEpochMilli();

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }
  }

  @Before
  public void setUp() {
    Configuration config = new Configuration();
    config.setMaxNoteSizeMb(1);
    SettingsService settings = new SettingsService(config, new File(tmp.getRoot(), "credentials.json"), tmp.getRoot());
    stores = new NoteStoreService();
    imports = new ImportService(clock);
    ServiceProvider provider = new ServiceProvider(settings, new SessionService(), new ClientGuardService(), stores, new NoteService(),
        imports, new FontService(), new WebServerService());
    stores.init(provider);
    imports.init(provider);
    imports.start();
    Logger.getLogger(ImportService.class.getName()).addHandler(handler);
  }

  @After
  public void tearDown() {
    Logger.getLogger(ImportService.class.getName()).removeHandler(handler);
    imports.destroy();
  }

  private UserNoteStore store(String username) throws IOException {
    return stores.getStore(username);
  }

  private static JsonObject note(String id, String content, String... tags) {
    JsonObject note = new JsonObject();
    if (id != null) note.addProperty("id", id);
    note.addProperty("content", content);
    note.addProperty("creationDate", "2020-01-02T03:04:05.000Z");
    note.addProperty("lastModified", "2021-06-07T08:09:10.000Z");
    if (tags.length > 0) {
      JsonArray array = new JsonArray();
      for (String tag : tags) array.add(tag);
      note.add("tags", array);
    }
    return note;
  }

  private static byte[] json(List<JsonObject> active, List<JsonObject> trashed) {
    JsonObject file = new JsonObject();
    JsonArray a = new JsonArray();
    active.forEach(a::add);
    JsonArray t = new JsonArray();
    trashed.forEach(t::add);
    file.add("activeNotes", a);
    file.add("trashedNotes", t);
    return file.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] json(JsonObject... active) {
    return json(List.of(active), List.of());
  }

  private ImportSummary check(String username, byte[] file) throws IOException {
    return imports.check(username, "127.0.0.1", "notes.zip", new ByteArrayInputStream(file));
  }

  private ImportSummary importNow(String username, byte[] file) throws IOException {
    return imports.run(username, "127.0.0.1", check(username, file).token());
  }

  private List<String> messages(Level level) {
    return logs.stream().filter(r -> r.getLevel() == level).map(LogRecord::getMessage).toList();
  }

  private Path importsFolder() {
    return tmp.getRoot().toPath().resolve("imports");
  }

  private long uploadsLeft() throws IOException {
    if (!Files.isDirectory(importsFolder())) return 0;
    try (Stream<Path> files = Files.list(importsFolder())) {
      return files.count();
    }
  }

  @Test
  public void anExportComesBackAsItWas() throws Exception {
    UserNoteStore bob = store("bob");
    bob.commit(A, 0, new NoteWrite("Groceries\nmilk", false, "c1", "d", null));
    bob.commitMeta(A, new MetaWrite("m1", "d", List.of("home", "list"), null, Map.of("color", "blue", "pinned", "true", "markdown", "true",
        "monospace", "true", "nowrap", "true"), null));
    bob.commit(B, 0, new NoteWrite("Old idea", false, "c2", "d", null));
    bob.commit(B, 1, new NoteWrite("Old idea", true, "c3", "d", null));
    ByteArrayOutputStream zip = new ByteArrayOutputStream();
    SimplenoteExport.write(bob, zip);

    ImportSummary preview = check("alice", zip.toByteArray());
    assertEquals(2, preview.notesInFile());
    assertEquals(2, preview.imported());
    assertEquals(1, preview.trashed());
    assertEquals(0, preview.alreadyThere());
    assertTrue(store("alice").list().isEmpty());

    ImportSummary done = imports.run("alice", "127.0.0.1", preview.token());
    assertNull(done.token());
    assertEquals(2, done.imported());
    UserNoteStore alice = store("alice");
    NoteMeta a = alice.get(A);
    NoteMeta exported = bob.get(A);
    assertEquals("Groceries\nmilk", alice.readContent(A));
    assertEquals(List.of("home", "list"), a.tags());
    assertEquals(Map.of("color", "blue", "pinned", "true", "markdown", "true", "monospace", "true", "nowrap", "true"), a.options());
    assertEquals(exported.createdAt(), a.createdAt());
    assertEquals(exported.modifiedAt(), a.modifiedAt());
    assertFalse(a.deleted());
    assertTrue(alice.get(B).deleted());
    assertEquals("Old idea", alice.readContent(B));
    assertEquals(0, uploadsLeft());
  }

  @Test
  public void importingTheSameFileTwiceImportsNothingTheSecondTime() throws Exception {
    byte[] file = json(note(A, "One"), note(null, "Two"));
    assertEquals(2, importNow("alice", file).imported());
    long seq = store("alice").getLastSeq();

    ImportSummary again = importNow("alice", file);

    assertEquals(0, again.imported());
    assertEquals(2, again.alreadyThere());
    assertEquals(seq, store("alice").getLastSeq());
  }

  @Test
  public void theSameIdIsSkippedWhateverItsContent() throws Exception {
    store("alice").commit(A, 0, new NoteWrite("Edited since", false, "c1", "d", null));

    ImportSummary done = importNow("alice", json(note(A, "As exported")));

    assertEquals(1, done.alreadyThere());
    assertEquals("Edited since", store("alice").readContent(A));
    assertTrue(messages(Level.INFO).contains("Import for \"alice\": " + A + " skipped, already there as " + A));
  }

  @Test
  public void theSameContentIsSkippedWhateverItsId() throws Exception {
    store("alice").commit(A, 0, new NoteWrite("Same text", false, "c1", "d", null));

    ImportSummary done = importNow("alice", json(note("simplenote-id-1", "Same text")));

    assertEquals(1, done.alreadyThere());
    assertEquals(1, store("alice").list().size());
    assertTrue(messages(Level.INFO).contains("Import for \"alice\": simplenote-id-1 skipped, already there as " + A));
  }

  @Test
  public void contentInATrashedNoteCountsAsThere() throws Exception {
    store("alice").commit(A, 0, new NoteWrite("Binned", false, "c1", "d", null));
    store("alice").commit(A, 1, new NoteWrite("Binned", true, "c2", "d", null));

    assertEquals(1, importNow("alice", json(note(null, "Binned"))).alreadyThere());
  }

  @Test
  public void crlfIsComparedAsAPushedNoteIs() throws Exception {
    store("alice").commit(A, 0, new NoteWrite("Line 1\nLine 2", false, "c1", "d", null));

    assertEquals(1, importNow("alice", json(note(null, "Line 1\r\nLine 2"))).alreadyThere());
  }

  @Test
  public void simplenoteIdsGetNewOnesAndOursAreKept() throws Exception {
    importNow("alice", json(note("0123456789abcdef0123456789abcdef", "From Simplenote"), note(B.toUpperCase(), "Ours, upper case")));

    List<NoteMeta> notes = store("alice").list();
    assertEquals(2, notes.size());
    NoteMeta simplenote = notes.stream().filter(n -> n.title().equals("From Simplenote")).findFirst().orElseThrow();
    assertTrue(UserNoteStore.NOTE_ID_PATTERN.matcher(simplenote.id()).matches());
    assertEquals("Ours, upper case", store("alice").get(B).title());
    List<String> info = messages(Level.INFO);
    assertTrue(info.contains("Import for \"alice\": imported 0123456789abcdef0123456789abcdef as " + simplenote.id()));
    assertTrue(info.contains("Import for \"alice\": imported " + B.toUpperCase() + " as " + B));
  }

  @Test
  public void eachImportedNoteIsLoggedWithItsIdFromTheFile() throws Exception {
    importNow("alice", json(List.of(note(A, "Kept")), List.of(note(C, "Binned"))));

    List<String> info = messages(Level.INFO);
    assertTrue(info.toString(), info.contains("Import for \"alice\": imported " + A));
    assertTrue(info.toString(), info.contains("Import for \"alice\": imported " + C + ", to the trash"));
    assertTrue(info.toString(), info.contains("Import for \"alice\": from 127.0.0.1, \"notes.zip\""));
    assertTrue(info.toString(), info.contains("Import for \"alice\": done, 2 notes, 2 imported (1 to the trash), 0 already there, 0 too large"));
  }

  @Test
  public void aNoteTooLargeIsSkippedWithAWarning() throws Exception {
    String big = "Huge note\n" + "x".repeat(1024 * 1024);

    ImportSummary preview = check("alice", json(note(A, big), note(B, "Small")));
    assertEquals(1, preview.imported());
    assertEquals(List.of(new ImportSummary.TooLargeNote("Huge note", big.length())), preview.tooLarge());
    assertEquals(1024 * 1024, preview.maxNoteSize());
    assertTrue(messages(Level.WARNING).isEmpty());

    imports.run("alice", "127.0.0.1", preview.token());
    assertNull(store("alice").get(A));
    assertTrue(messages(Level.WARNING).contains("Import for \"alice\": " + A + " skipped, too large: " + big.length()
        + " bytes, the limit is " + 1024 * 1024));
  }

  @Test
  public void duplicatesWithinTheFileComeInOnce() throws Exception {
    ImportSummary done = importNow("alice", json(note(A, "First"), note(A, "Same ID"), note(null, "First")));

    assertEquals(1, done.imported());
    assertEquals(2, done.alreadyThere());
  }

  @Test
  public void datesAreKeptAndMissingOnesAreNow() throws Exception {
    JsonObject undated = new JsonObject();
    undated.addProperty("id", B);
    undated.addProperty("content", "Undated");
    importNow("alice", json(note(A, "Dated"), undated));

    assertEquals(Instant.parse("2020-01-02T03:04:05Z").toEpochMilli(), store("alice").get(A).createdAt());
    assertEquals(Instant.parse("2021-06-07T08:09:10Z").toEpochMilli(), store("alice").get(A).modifiedAt());
    assertEquals(clock.millis(), store("alice").get(B).createdAt());
    assertEquals(clock.millis(), store("alice").get(B).modifiedAt());
  }

  @Test
  public void invalidTagsAreLeftOutAndNewTagsJoinTheOrder() throws Exception {
    store("alice").commit(C, 0, new NoteWrite("Existing", false, "c1", "d", null));
    store("alice").commitMeta(C, new MetaWrite("m1", "d", List.of("zeta", "alpha"), null, null, null));
    store("alice").setTagOrder(0, List.of("zeta", "alpha"));

    importNow("alice", json(note(A, "Tagged", "work", "two words", "work", "home")));

    assertEquals(List.of("work", "home"), store("alice").get(A).tags());
    assertEquals(List.of("zeta", "alpha", "work", "home"), store("alice").getTagOrder().tags());
    assertTrue(messages(Level.INFO).contains("Import for \"alice\": imported " + A + ", 2 invalid or duplicate tag(s) left out"));
  }

  @Test
  public void nothingIsWrittenBeforeRunning() throws Exception {
    check("alice", json(note(A, "Not yet")));

    assertTrue(store("alice").list().isEmpty());
    assertEquals(1, uploadsLeft());
  }

  @Test
  public void theRunWorksThePlanOutAgain() throws Exception {
    ImportSummary preview = check("alice", json(note(A, "Synced in between"), note(B, "New")));
    assertEquals(2, preview.imported());
    store("alice").commit(A, 0, new NoteWrite("Synced in between", false, "c1", "d", null));

    ImportSummary done = imports.run("alice", "127.0.0.1", preview.token());

    assertEquals(1, done.imported());
    assertEquals(1, done.alreadyThere());
  }

  @Test
  public void cancellingDropsTheFile() throws Exception {
    ImportSummary preview = check("alice", json(note(A, "Never")));

    imports.cancel("alice", preview.token());

    assertEquals(0, uploadsLeft());
    assertRunRefused("alice", preview.token());
    assertTrue(store("alice").list().isEmpty());
  }

  @Test
  public void aSecondFileReplacesTheFirst() throws Exception {
    ImportSummary first = check("alice", json(note(A, "First file")));
    ImportSummary second = check("alice", json(note(B, "Second file")));

    assertEquals(1, uploadsLeft());
    assertRunRefused("alice", first.token());
    assertEquals(1, imports.run("alice", "127.0.0.1", second.token()).imported());
    assertEquals("Second file", store("alice").readContent(B));
  }

  @Test
  public void aTokenIsOnlyItsUsers() throws Exception {
    ImportSummary preview = check("alice", json(note(A, "Alice's")));

    assertRunRefused("bob", preview.token());
    imports.cancel("bob", preview.token());
    assertEquals(1, imports.run("alice", "127.0.0.1", preview.token()).imported());
    assertTrue(store("bob").list().isEmpty());
  }

  @Test
  public void aPendingImportExpires() throws Exception {
    ImportSummary preview = check("alice", json(note(A, "Too late")));
    clock.millis += ImportService.PENDING_FOR.toMillis() + 1;

    assertRunRefused("alice", preview.token());
    assertEquals(0, uploadsLeft());
  }

  @Test
  public void startingEmptiesTheFolder() throws Exception {
    check("alice", json(note(A, "Before a restart")));
    imports.start();
    assertEquals(0, uploadsLeft());
  }

  @Test
  public void notAnExportIsRefusedAndNotKept() throws Exception {
    for (byte[] file : List.of("hello".getBytes(StandardCharsets.UTF_8), "{\"other\": []}".getBytes(StandardCharsets.UTF_8),
        "{\"activeNotes\": [".getBytes(StandardCharsets.UTF_8), "[1, 2]".getBytes(StandardCharsets.UTF_8), zip("readme.txt", "hi"))) {
      try {
        check("alice", file);
        fail("Accepted " + new String(file, StandardCharsets.UTF_8));
      } catch (InvalidInputException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("isn't an export") || e.getMessage().contains("no source/notes.json"));
      }
    }
    assertEquals(0, uploadsLeft());
  }

  @Test
  public void theBareJsonOrAZipOfIt() throws Exception {
    byte[] notes = json(note(A, "In a zip"));
    assertEquals(1, check("alice", zip("source/notes.json", new String(notes, StandardCharsets.UTF_8))).imported());
    assertEquals(1, check("alice", zip("notes-alice/source/notes.json", new String(notes, StandardCharsets.UTF_8))).imported());
    assertEquals(1, check("alice", notes).imported());
  }

  @Test
  public void fileNamesAreCleaned() {
    assertEquals("notes.zip", ImportService.cleanFileName("C:\\Users\\me\\notes.zip"));
    assertEquals("evil.zip", ImportService.cleanFileName("../evil\n\".zip"));
    assertEquals("(no name)", ImportService.cleanFileName(null));
  }

  @Test
  public void oneFileAtATimePerUser() throws Exception {
    java.util.concurrent.CountDownLatch uploading = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch finish = new java.util.concurrent.CountDownLatch(1);
    byte[] file = json(note(A, "Slow upload"));
    // An upload still coming in.
    java.io.InputStream slow = new java.io.InputStream() {
      private int at;

      @Override
      public int read() throws IOException {
        if (at == 0) {
          uploading.countDown();
          try {
            finish.await();
          } catch (InterruptedException e) {
            throw new IOException(e);
          }
        }
        return at < file.length ? file[at++] & 0xff : -1;
      }
    };
    java.util.concurrent.CompletableFuture<ImportSummary> first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try {
        return imports.check("alice", "127.0.0.1", "slow.json", slow);
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    });
    uploading.await();

    try {
      check("alice", json(note(B, "Meanwhile")));
      fail("Checked");
    } catch (net.pieroxy.notes.api.metadata.TooManyRequestsException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("previous file is still being checked"));
    }
    assertEquals(1, check("bob", json(note(B, "Someone else's"))).imported());

    finish.countDown();
    assertEquals(1, first.get().imported());
    assertEquals(1, check("alice", json(note(B, "Afterwards"))).imported());
  }

  @Test
  public void atMostSoManyFilesAtOnceServerWide() throws Exception {
    ImportSummary waiting = check("alice", json(note(A, "Checked before")));
    imports.parsing.acquire(ImportService.MAX_CONCURRENT_IMPORTS);
    try {
      for (Runnable attempt : List.<Runnable>of(
          () -> uncheckedCheck("bob", json(note(B, "Too many"))),
          () -> uncheckedRun("alice", waiting.token()))) {
        try {
          attempt.run();
          fail("Went through");
        } catch (net.pieroxy.notes.api.metadata.TooManyRequestsException e) {
          assertTrue(e.getMessage(), e.getMessage().contains("busy with other imports"));
        }
      }
      // Refused: bob's file not kept, alice's still waiting.
      assertEquals(1, uploadsLeft());
    } finally {
      imports.parsing.release(ImportService.MAX_CONCURRENT_IMPORTS);
    }
    assertEquals(1, imports.run("alice", "127.0.0.1", waiting.token()).imported());
    assertEquals(0, uploadsLeft());
  }

  @Test
  public void aSlotIsFreedWhateverHappens() throws Exception {
    for (int i = 0; i < 3 * ImportService.MAX_CONCURRENT_IMPORTS; i++) {
      try {
        check("alice", "not an export".getBytes(StandardCharsets.UTF_8));
        fail("Accepted");
      } catch (InvalidInputException expected) {
        // refused, and the slot back
      }
    }
    assertEquals(ImportService.MAX_CONCURRENT_IMPORTS, imports.parsing.availablePermits());
    assertEquals(1, importNow("alice", json(note(A, "Still works"))).imported());
    assertEquals(ImportService.MAX_CONCURRENT_IMPORTS, imports.parsing.availablePermits());
  }

  private void uncheckedCheck(String username, byte[] file) {
    try {
      check(username, file);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private void uncheckedRun(String username, String token) {
    try {
      imports.run(username, "127.0.0.1", token);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private void assertRunRefused(String username, String token) throws IOException {
    try {
      imports.run(username, "127.0.0.1", token);
      fail("Ran");
    } catch (InvalidInputException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("no longer waiting"));
    }
  }

  private static byte[] zip(String name, String content) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry(name));
      zip.write(content.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }
}
