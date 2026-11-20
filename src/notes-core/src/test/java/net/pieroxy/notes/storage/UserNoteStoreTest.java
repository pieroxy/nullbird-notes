package net.pieroxy.notes.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UserNoteStoreTest {
  private static final String A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private final TickingClock clock = new TickingClock();
  private Path root;
  private UserNoteStore store;

  /** Advances by a second on every read, so that every commit gets its own time. */
  private static class TickingClock extends Clock {
    private long millis = 1_763_481_600_000L;

    void advance(Duration duration) {
      millis += duration.toMillis();
    }

    @Override
    public long millis() {
      return millis += 1000;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis());
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException();
    }
  }

  /** Thrown from a commit hook in place of the JVM dying. */
  private static class SimulatedCrash extends IOException {
  }

  @Before
  public void setUp() throws IOException {
    root = tmp.getRoot().toPath().resolve("alice");
    store = UserNoteStore.open(root, clock);
  }

  private UserNoteStore reopen() throws IOException {
    store = UserNoteStore.open(root, clock);
    return store;
  }

  private static NoteWrite write(String content, String changeId) {
    return new NoteWrite(content, false, changeId, "device-1", null);
  }

  private NoteMeta commitCrashingAfter(UserNoteStore.CommitStep crashStep, String noteId, int expectedRevision, String content)
      throws IOException, StaleRevisionException {
    store.commitHook = step -> {
      if (step == crashStep) throw new SimulatedCrash();
    };
    try {
      return store.commit(noteId, expectedRevision, write(content, "crashing-change"));
    } catch (SimulatedCrash expected) {
      return null;
    }
  }

  @Test
  public void aSeqWaiterRunsRightAwayWhenTheSeqAlreadyDiffers() throws Exception {
    store.commit(A, 0, write("a", "c1"));
    int[] runs = {0};

    store.awaitSeqChange(0, () -> runs[0]++);
    store.awaitSeqChange(5, () -> runs[0]++);
    assertEquals(2, runs[0]);
  }

  @Test
  public void aSeqWaiterRunsOnceOnTheNextCommit() throws Exception {
    int[] runs = {0};
    store.awaitSeqChange(0, () -> runs[0]++);
    assertEquals(0, runs[0]);

    store.commit(A, 0, write("a", "c1"));
    store.commit(A, 1, write("b", "c2"));
    assertEquals(1, runs[0]);
  }

  @Test
  public void aCancelledSeqWaiterNeverRuns() throws Exception {
    int[] runs = {0};
    Runnable callback = () -> runs[0]++;
    Runnable cancel = store.awaitSeqChange(0, callback);
    store.awaitSeqChange(0, callback);
    cancel.run();

    store.commit(A, 0, write("a", "c1"));
    assertEquals(1, runs[0]);
  }

  @Test
  public void aFailingSeqWaiterDoesNotBreakTheCommitNorTheOtherWaiters() throws Exception {
    int[] runs = {0};
    store.awaitSeqChange(0, () -> {
      throw new IllegalStateException("boom");
    });
    store.awaitSeqChange(0, () -> runs[0]++);

    assertEquals(1, store.commit(A, 0, write("a", "c1")).revision());
    assertEquals(1, runs[0]);
  }

  @Test
  public void createsANote() throws Exception {
    NoteMeta meta = store.commit(A, 0, write("Shopping\nmilk", "c1"));

    assertEquals(1, meta.revision());
    assertEquals(1, meta.seq());
    assertEquals(meta.createdAt(), meta.modifiedAt());
    assertEquals("Shopping", meta.title());
    assertEquals(13, meta.size());
    assertEquals("Shopping\nmilk", store.readContent(A));
    assertEquals(meta, store.get(A));
  }

  @Test
  public void updatesKeepTheCreationTimeAndBumpRevisionAndSeq() throws Exception {
    NoteMeta created = store.commit(A, 0, write("one", "c1"));
    store.commit(B, 0, write("other", "c2"));
    NoteMeta updated = store.commit(A, 1, write("two", "c3"));

    assertEquals(2, updated.revision());
    assertEquals(3, updated.seq());
    assertEquals(created.createdAt(), updated.createdAt());
    assertTrue(updated.modifiedAt() > created.modifiedAt());
    assertEquals(3, store.getLastSeq());
  }

  @Test
  public void keepsEveryRevision() throws Exception {
    store.commit(A, 0, write("one", "c1"));
    store.commit(A, 1, write("two", "c2"));

    Revision first = store.readRevision(A, 1);
    assertEquals("one", first.content());
    assertEquals("c1", first.changeId());
    assertEquals("device-1", first.deviceId());
    assertEquals("two", store.readRevision(A, 2).content());
    assertNull(store.readRevision(A, 0));
    assertNull(store.readRevision(A, 3));
    assertNull(store.readRevision(B, 1));
  }

  @Test
  public void aDeletedNoteKeepsItsContent() throws Exception {
    store.commit(A, 0, write("keep me", "c1"));
    NoteMeta deleted = store.commit(A, 1, new NoteWrite("keep me", true, "c2", "device-1", null));

    assertTrue(deleted.deleted());
    assertEquals("keep me", store.readContent(A));
    assertTrue(store.readRevision(A, 2).deleted());
  }

  @Test
  public void refusesAStaleRevisionWithoutWritingAnything() throws Exception {
    store.commit(A, 0, write("one", "c1"));
    store.commit(A, 1, write("two", "c2"));

    assertThrows(StaleRevisionException.class, () -> store.commit(A, 1, write("late", "c3")));
    assertThrows(StaleRevisionException.class, () -> store.commit(A, 0, write("again", "c4")));
    assertThrows(StaleRevisionException.class, () -> store.commit(B, 1, write("missing", "c5")));
    assertEquals(2, store.get(A).revision());
    assertEquals(2, store.getLastSeq());
    assertNull(store.findChange("c3"));
  }

  @Test
  public void refusesAnInvalidNoteId() {
    for (String id : new String[] {"../escape", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", "", null}) {
      assertThrows(IllegalArgumentException.class, () -> store.commit(id, 0, write("x", "c1")));
    }
  }

  @Test
  public void listsChangesSinceASeqInSeqOrder() throws Exception {
    store.commit(A, 0, write("a1", "c1"));
    store.commit(B, 0, write("b1", "c2"));
    store.commit(A, 1, write("a2", "c3"));

    assertEquals(List.of(B, A), store.changesSince(1).stream().map(NoteMeta::id).toList());
    assertEquals(List.of(A), store.changesSince(2).stream().map(NoteMeta::id).toList());
    assertEquals(List.of(), store.changesSince(3));
    assertEquals(2, store.list().size());
  }

  @Test
  public void findsWhereAChangeLanded() throws Exception {
    store.commit(A, 0, write("a1", "c1"));
    store.commit(A, 1, write("a2", "c2"));

    assertEquals(new ChangeRecord(A, 1), store.findChange("c1"));
    assertEquals(new ChangeRecord(A, 2), store.findChange("c2"));
    assertNull(store.findChange("unknown"));
    assertNull(store.findChange(null));
  }

  @Test
  public void reloadsEverythingFromDisk() throws Exception {
    NoteMeta a = store.commit(A, 0, write("a1", "c1"));
    store.commit(B, 0, write("b1", "c2"));
    NoteMeta b = store.commit(B, 1, write("b2", "c3"));

    reopen();

    assertEquals(a, store.get(A));
    assertEquals(b, store.get(B));
    assertEquals(3, store.getLastSeq());
    assertEquals("b2", store.readContent(B));
    // Only each note's last change is remembered across a restart.
    assertEquals(new ChangeRecord(B, 2), store.findChange("c3"));
    assertNull(store.findChange("c2"));
  }

  @Test
  public void crashAfterTheRevisionLeavesTheNoteUnchangedAndTheNextCommitOverwritesIt() throws Exception {
    store.commit(A, 0, write("one", "c1"));
    commitCrashingAfter(UserNoteStore.CommitStep.REVISION, A, 1, "lost");

    reopen();
    assertEquals(1, store.get(A).revision());
    assertEquals("one", store.readContent(A));
    assertNull(store.readRevision(A, 2));
    assertNull(store.findChange("crashing-change"));

    store.commit(A, 1, write("two", "c2"));
    assertEquals("two", store.readRevision(A, 2).content());
  }

  @Test
  public void crashAfterTheContentRestoresItFromTheHeadRevisionWhenRead() throws Exception {
    store.commit(A, 0, write("one", "c1"));
    commitCrashingAfter(UserNoteStore.CommitStep.CONTENT, A, 1, "lost");

    reopen();
    assertEquals(1, store.get(A).revision());
    assertEquals("one", store.readContent(A));
    assertEquals("one", Files.readString(root.resolve("notes").resolve(A).resolve("content.txt")));
  }

  @Test
  public void crashAfterNoteJsonKeepsTheChangeAndRecoversTheSeq() throws Exception {
    store.commit(A, 0, write("one", "c1"));
    commitCrashingAfter(UserNoteStore.CommitStep.META, A, 1, "two");

    reopen();
    assertEquals(2, store.get(A).revision());
    assertEquals("two", store.readContent(A));
    assertEquals(2, store.getLastSeq());
    assertEquals(new ChangeRecord(A, 2), store.findChange("crashing-change"));
    assertEquals(3, store.commit(B, 0, write("b", "c2")).seq());
  }

  @Test
  public void crashDuringACreationLeavesNoNote() throws Exception {
    commitCrashingAfter(UserNoteStore.CommitStep.CONTENT, A, 0, "lost");

    reopen();
    assertNull(store.get(A));
    assertEquals(0, store.getLastSeq());
    assertEquals(1, store.commit(A, 0, write("created", "c1")).revision());
  }

  @Test
  public void theSeqNeverGoesBackwardsWithoutUserJsonButTheStoreIdChanges() throws Exception {
    store.commit(A, 0, write("a", "c1"));
    store.commit(A, 1, write("a", "c2"));
    String storeId = store.getStoreId();
    Files.delete(root.resolve("user.json"));

    reopen();
    assertEquals(2, store.getLastSeq());
    assertNotEquals(storeId, store.getStoreId());
  }

  @Test
  public void theStoreIdIsCreatedOnceAndSavedRightAway() throws Exception {
    String storeId = store.getStoreId();
    assertNotNull(storeId);
    assertTrue(Files.readString(root.resolve("user.json")).contains(storeId));

    store.commit(A, 0, write("a", "c1"));
    reopen();
    assertEquals(storeId, store.getStoreId());
  }

  @Test
  public void anOldUserJsonWithoutStoreIdGetsOne() throws Exception {
    store.commit(A, 0, write("a", "c1"));
    Files.writeString(root.resolve("user.json"), "{\"lastSeq\":1}");

    reopen();
    assertNotNull(store.getStoreId());
    assertEquals(1, store.getLastSeq());
    assertEquals(store.getStoreId(), reopen().getStoreId());
  }

  @Test
  public void keepsTheSubmittedContentOnlyWhenItDiffers() throws Exception {
    store.commit(A, 0, new NoteWrite("merged", false, "c1", "device-1", "what the client sent"));
    store.commit(A, 1, new NoteWrite("same", false, "c2", "device-1", "same"));

    assertEquals("what the client sent", store.readRevision(A, 1).submittedContent());
    assertNull(store.readRevision(A, 2).submittedContent());
  }

  @Test
  public void aContentFileEditedOnDiskIsRestored() throws Exception {
    store.commit(A, 0, write("original", "c1"));
    Path contentFile = root.resolve("notes").resolve(A).resolve("content.txt");
    Files.writeString(contentFile, "tampered");

    assertEquals("original", store.readContent(A));
    assertEquals("original", Files.readString(contentFile));
  }

  @Test
  public void ignoresFoldersThatAreNotNotes() throws Exception {
    store.commit(A, 0, write("a", "c1"));
    Files.createDirectories(root.resolve("notes").resolve("not-a-note"));

    reopen();
    assertEquals(1, store.list().size());
  }

  @Test
  public void handlesLargeAndNonAsciiContent() throws Exception {
    StringBuilder big = new StringBuilder();
    while (big.length() < 5_000_000) big.append("ligne numéro ").append(big.length()).append(" — 😀\n");
    store.commit(A, 0, write(big.toString(), "c1"));

    reopen();
    assertEquals(big.toString(), store.readContent(A));
    assertEquals(big.toString().getBytes(StandardCharsets.UTF_8).length, store.get(A).size());
  }

  @Test
  public void titleIsTheFirstNonBlankLineTruncated() {
    assertEquals("Title", UserNoteStore.title("\n  \n  Title  \nbody"));
    assertEquals("", UserNoteStore.title(" \n\t\n"));
    assertEquals("x".repeat(UserNoteStore.TITLE_MAX_LENGTH), UserNoteStore.title("x".repeat(500)));
    String emojis = "😀".repeat(UserNoteStore.TITLE_MAX_LENGTH + 5);
    assertEquals(UserNoteStore.TITLE_MAX_LENGTH, UserNoteStore.title(emojis).codePointCount(0, UserNoteStore.title(emojis).length()));
  }

  @Test
  public void aFailedCommitBeforeNoteJsonChangesNothingInMemory() throws Exception {
    store.commit(A, 0, write("one", "c1"));
    commitCrashingAfter(UserNoteStore.CommitStep.CONTENT, A, 1, "lost");
    store.commitHook = step -> {};

    assertEquals(1, store.get(A).revision());
    assertEquals(1, store.getLastSeq());
    assertEquals("one", store.readContent(A));
    try {
      store.commit(A, 1, write("two", "c2"));
    } catch (StaleRevisionException e) {
      fail("The failed commit must not have moved the head");
    }
  }

  private static MetaWrite meta(String changeId, List<String> addTags, List<String> removeTags, Map<String, String> setOptions,
                                List<String> unsetOptions) {
    return new MetaWrite(changeId, "device-1", addTags, removeTags, setOptions, unsetOptions);
  }

  @Test
  public void aMetadataChangeBumpsTheSeqButNotTheRevisionNorModifiedAt() throws Exception {
    NoteMeta before = store.commit(A, 0, write("text", "c1"));
    NoteMeta after = store.commitMeta(A, meta("m1", List.of("work"), null, Map.of("monospace", "true"), null));

    assertEquals(List.of("work"), after.tags());
    assertEquals(Map.of("monospace", "true"), after.options());
    assertEquals(1, after.revision());
    assertEquals(before.modifiedAt(), after.modifiedAt());
    assertEquals(before.seq() + 1, after.seq());
    assertEquals(after.seq(), store.getLastSeq());
    assertEquals(1, after.metaRevision());
    assertEquals(List.of(after), store.changesSince(before.seq()));
  }

  @Test
  public void tagsKeepTheOrderTheyWereAddedInWithoutDuplicates() throws Exception {
    store.commit(A, 0, write("text", "c1"));
    store.commitMeta(A, meta("m1", List.of("b", "a"), null, null, null));
    NoteMeta after = store.commitMeta(A, meta("m2", List.of("a", "c", "A"), List.of("b"), null, null));

    assertEquals(List.of("a", "c", "A"), after.tags());
  }

  @Test
  public void removalsApplyBeforeAdditions() throws Exception {
    store.commit(A, 0, write("text", "c1"));
    NoteMeta after = store.commitMeta(A, meta("m1", List.of("x"), List.of("x"), Map.of("k", "v"), List.of("k")));

    assertEquals(List.of("x"), after.tags());
    assertEquals(Map.of("k", "v"), after.options());
  }

  @Test
  public void aTextCommitKeepsTheTagsAndOptions() throws Exception {
    store.commit(A, 0, write("text", "c1"));
    store.commitMeta(A, meta("m1", List.of("work"), null, Map.of("nowrap", "true"), null));
    NoteMeta after = store.commit(A, 1, write("more text", "c2"));

    assertEquals(List.of("work"), after.tags());
    assertEquals(Map.of("nowrap", "true"), after.options());
    assertEquals(1, after.metaRevision());
  }

  @Test
  public void aNewNoteGetsTheTagsAndOptionsItIsWrittenWith() throws Exception {
    NoteMeta created = store.commit(A, 0, new NoteWrite("copy", false, "c1", "device-1", null, List.of("t"), Map.of("k", "v")));

    assertEquals(List.of("t"), created.tags());
    assertEquals(Map.of("k", "v"), created.options());
  }

  @Test
  public void metadataSurvivesAReloadWithItsHistoryAndLastChangeId() throws Exception {
    store.commit(A, 0, write("text", "c1"));
    store.commitMeta(A, meta("m1", List.of("work"), null, null, null));
    store.commitMeta(A, meta("m2", null, List.of("work"), Map.of("monospace", "true"), null));
    reopen();

    NoteMeta meta = store.get(A);
    assertEquals(List.of(), meta.tags());
    assertEquals(Map.of("monospace", "true"), meta.options());
    assertEquals(A, store.findMetaChange("m2"));
    assertNull(store.findMetaChange("m1"));
    MetaRevision first = store.readMetaRevision(A, 1);
    assertEquals("m1", first.changeId());
    assertEquals(List.of("work"), first.addTags());
    assertNull(store.readMetaRevision(A, 3));
  }

  @Test
  public void aNoteJsonWithoutTagsNorOptionsLoadsWithNone() throws Exception {
    store.commit(A, 0, write("text", "c1"));
    Path noteJson = root.resolve("notes").resolve(A).resolve("note.json");
    String json = Files.readString(noteJson).replaceAll("(?s),\\s*\"tags\".*?\\]", "").replaceAll("(?s),\\s*\"options\".*?\\}", "");
    assertTrue(json, !json.contains("tags") && !json.contains("options"));
    Files.writeString(noteJson, json);
    reopen();

    assertEquals(List.of(), store.get(A).tags());
    assertEquals(Map.of(), store.get(A).options());
  }

  @Test
  public void aMetadataChangeToAnUnknownNoteWritesNothing() throws Exception {
    assertNull(store.commitMeta(A, meta("m1", List.of("x"), null, null, null)));
    assertEquals(0, store.getLastSeq());
  }

  private Path revisionPath(String noteId, int revision) {
    return root.resolve("notes").resolve(noteId).resolve("revisions").resolve(String.format("%06d.json.lz4", revision));
  }

  private JsonObject storedRevision(String noteId, int revision) throws IOException {
    try (InputStream in = new LZ4FrameInputStream(Files.newInputStream(revisionPath(noteId, revision)))) {
      return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }
  }

  private void writeStoredRevision(String noteId, int revision, JsonObject json) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (OutputStream out = new LZ4FrameOutputStream(bytes)) {
      out.write(json.toString().getBytes(StandardCharsets.UTF_8));
    }
    Files.write(revisionPath(noteId, revision), bytes.toByteArray());
  }

  @Test
  public void olderRevisionsAreStoredAsSmallDeltasAndReadBackExactly() throws Exception {
    String big = "Line of a long note.\n".repeat(50_000);
    List<String> texts = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      String text = big.substring(0, 500_000) + "edit " + i + "\n" + big.substring(500_000);
      texts.add(text);
      store.commit(A, i, write(text, "c" + i));
    }

    for (int i = 1; i < 12; i++) {
      assertTrue(storedRevision(A, i).has("delta"));
      assertTrue(Files.size(revisionPath(A, i)) < 1_000);
      assertEquals(texts.get(i - 1), store.readRevision(A, i).content());
    }
    assertTrue(storedRevision(A, 12).has("content"));
    assertEquals(texts.get(11), store.readRevision(A, 12).content());
  }

  @Test
  public void theSubmittedContentSurvivesBeingStoredAsADelta() throws Exception {
    store.commit(A, 0, new NoteWrite("merged text", false, "c1", "device-1", "what the client sent"));
    store.commit(A, 1, write("merged text, then edited", "c2"));

    assertTrue(storedRevision(A, 1).has("delta"));
    Revision first = store.readRevision(A, 1);
    assertEquals("merged text", first.content());
    assertEquals("what the client sent", first.submittedContent());
  }

  @Test
  public void everyIntervalARevisionStaysComplete() throws Exception {
    int interval = 5;
    store.fullRevisionInterval = interval;
    for (int i = 0; i <= interval + 1; i++) store.commit(A, i, write("text " + i, "c" + i));

    assertTrue(storedRevision(A, interval).has("content"));
    assertTrue(storedRevision(A, interval - 1).has("delta"));
    assertTrue(storedRevision(A, interval + 1).has("delta"));
    assertEquals("text 0", store.readRevision(A, 1).content());
  }

  @Test
  public void aRevisionAsBigAsItsDeltaStaysComplete() throws Exception {
    store.commit(A, 0, write("short", "c1"));
    store.commit(A, 1, write("entirely different", "c2"));

    assertTrue(storedRevision(A, 1).has("content"));
    assertEquals("short", store.readRevision(A, 1).content());
  }

  @Test
  public void crashBeforeTheDeltaLeavesThePreviousRevisionComplete() throws Exception {
    store.commit(A, 0, write("a long enough first text", "c1"));
    commitCrashingAfter(UserNoteStore.CommitStep.USER_STATE, A, 1, "a long enough first text, edited");
    reopen();

    assertTrue(storedRevision(A, 1).has("content"));
    assertEquals("a long enough first text", store.readRevision(A, 1).content());
    assertEquals("a long enough first text, edited", store.readRevision(A, 2).content());
    store.commit(A, 2, write("a long enough first text, edited twice", "c3"));
    assertTrue(storedRevision(A, 2).has("delta"));
    assertEquals("a long enough first text", store.readRevision(A, 1).content());
  }

  @Test
  public void aDeltaThatDoesntRebuildItsRevisionIsReported() throws Exception {
    store.commit(A, 0, write("a long enough first text", "c1"));
    store.commit(A, 1, write("a long enough first text, edited", "c2"));
    JsonObject stored = storedRevision(A, 1);
    stored.getAsJsonObject("delta").addProperty("middle", "!");
    writeStoredRevision(A, 1, stored);

    assertThrows(IOException.class, () -> store.readRevision(A, 1));
  }

  @Test
  public void aFormatOneRevisionIsReadAndTurnedIntoADelta() throws Exception {
    store.commit(A, 0, write("a long enough first text", "c1"));
    JsonObject legacy = storedRevision(A, 1);
    legacy.addProperty("submittedContent", "what was sent");
    writeStoredRevision(A, 1, legacy);
    assertEquals("what was sent", store.readRevision(A, 1).submittedContent());

    store.commit(A, 1, write("a long enough first text, edited", "c2"));
    assertTrue(storedRevision(A, 1).has("delta"));
    assertEquals("a long enough first text", store.readRevision(A, 1).content());
    assertEquals("what was sent", store.readRevision(A, 1).submittedContent());
  }

  /**
   * Revisions 1-5 on a day 200 days before now, 6-15 within an hour 30 days before, 16-20 two days
   * before: by the policy, 1 (the first), 5 (its day's last), 15 (its hour's last) and 16-20 stay.
   */
  private List<String> commitOverTime() throws Exception {
    List<String> texts = new ArrayList<>();
    String base = "A paragraph long enough for deltas to be worth it.\n".repeat(20);
    for (int i = 0; i < 20; i++) {
      if (i == 5) clock.advance(Duration.ofDays(170));
      if (i == 15) clock.advance(Duration.ofDays(28));
      String text = base + "version " + i + "\n";
      texts.add(text);
      store.commit(A, i, write(text, "c" + i));
    }
    clock.advance(Duration.ofDays(2));
    return texts;
  }

  private static final Set<Integer> KEPT = Set.of(1, 5, 15, 16, 17, 18, 19, 20);

  @Test
  public void thinningKeepsWhatThePolicySaysAndEveryKeptRevisionReadsBackExactly() throws Exception {
    store.fullRevisionInterval = 7;
    List<String> texts = commitOverTime();
    long now = clock.millis();

    assertEquals(12, store.thin(now));
    for (int revision = 1; revision <= 20; revision++) {
      Revision read = store.readRevision(A, revision);
      if (KEPT.contains(revision)) assertEquals("revision " + revision, texts.get(revision - 1), read.content());
      else assertNull("revision " + revision, read);
    }
    assertEquals(0, store.thin(now));
    reopen();
    assertEquals(texts.get(0), store.readRevision(A, 1).content());
  }

  @Test
  public void anInterruptedThinningLeavesEveryRevisionReadableAndFinishesNextTime() throws Exception {
    List<String> texts = commitOverTime();
    long now = clock.millis();
    store.commitHook = step -> {
      if (step == UserNoteStore.CommitStep.THINNING_REWRITES) throw new SimulatedCrash();
    };
    assertThrows(SimulatedCrash.class, () -> store.thin(now));
    reopen();

    for (int revision = 1; revision <= 20; revision++) assertEquals(texts.get(revision - 1), store.readRevision(A, revision).content());
    assertEquals(12, store.thin(now));
    for (int revision : KEPT) assertEquals(texts.get(revision - 1), store.readRevision(A, revision).content());
  }

  @Test
  public void thinningDropsSubmittedTextsOlderThanADayButNotTheHeads() throws Exception {
    store.commit(A, 0, new NoteWrite("merged, a long enough text", false, "c1", "device-1", "sent, a long enough text"));
    store.commit(A, 1, new NoteWrite("merged again, a long enough text", false, "c2", "device-1", "sent again, a long enough text"));
    clock.advance(Duration.ofHours(12));
    store.commit(A, 2, new NoteWrite("recent, a long enough text", false, "c3", "device-1", "sent recently, a long enough text"));
    store.commit(A, 3, new NoteWrite("head, a long enough text", false, "c4", "device-1", "sent head, a long enough text"));
    clock.advance(Duration.ofHours(13));
    store.thin(clock.millis());

    assertNull(store.readRevision(A, 1).submittedContent());
    assertNull(store.readRevision(A, 2).submittedContent());
    assertEquals("sent recently, a long enough text", store.readRevision(A, 3).submittedContent());
    assertEquals("sent head, a long enough text", store.readRevision(A, 4).submittedContent());
    assertEquals("merged, a long enough text", store.readRevision(A, 1).content());
  }
}
