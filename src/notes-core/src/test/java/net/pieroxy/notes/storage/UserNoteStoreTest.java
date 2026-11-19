package net.pieroxy.notes.storage;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

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
    return new NoteWrite(content, false, changeId, "device-1", null, null);
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
    NoteMeta deleted = store.commit(A, 1, new NoteWrite("keep me", true, "c2", "device-1", null, null));

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
    ConflictOf conflictOf = new ConflictOf(B);
    NoteMeta a = store.commit(A, 0, new NoteWrite("copy", false, "c1", "device-1", null, conflictOf));
    store.commit(B, 0, write("b1", "c2"));
    NoteMeta b = store.commit(B, 1, write("b2", "c3"));

    reopen();

    assertEquals(a, store.get(A));
    assertEquals(b, store.get(B));
    assertEquals(conflictOf, store.get(A).conflictOf());
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
    store.commit(A, 0, new NoteWrite("merged", false, "c1", "device-1", "what the client sent", null));
    store.commit(A, 1, new NoteWrite("same", false, "c2", "device-1", "same", null));

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
}
