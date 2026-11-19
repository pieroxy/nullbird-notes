package net.pieroxy.notes.services;

import net.pieroxy.notes.api.metadata.InvalidInputException;
import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.storage.ConflictOf;
import net.pieroxy.notes.sync.ChangesPage;
import net.pieroxy.notes.sync.NoteChange;
import net.pieroxy.notes.sync.NoteContent;
import net.pieroxy.notes.sync.NoteInfo;
import net.pieroxy.notes.sync.NoteRequest;
import net.pieroxy.notes.sync.NotesResult;
import net.pieroxy.notes.sync.PushResult;
import net.pieroxy.notes.sync.PushStatus;
import net.pieroxy.notes.sync.RejectReason;
import net.pieroxy.notes.sync.Splice;
import net.pieroxy.notes.utils.Hashes;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class NoteServiceTest {
  private static final String A = id(1);
  private static final String B = id(2);
  private static final String BASE = "Title\n\nFirst paragraph.\n\nSecond paragraph.\n";

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private NoteService service;
  private NoteStoreService stores;
  private int changeCount;

  private static String id(int n) {
    return String.format("%08x-0000-4000-8000-%012x", n, n);
  }

  @Before
  public void setUp() {
    Configuration config = new Configuration();
    config.setMaxNoteSizeMb(1);
    SettingsService settings = new SettingsService(config, new File(tmp.getRoot(), "credentials.json"), tmp.getRoot());
    stores = new NoteStoreService();
    service = new NoteService(3);
    ServiceProvider provider = new ServiceProvider(settings, new SessionService(), new ClientGuardService(), stores, service,
        new WebServerService());
    stores.init(provider);
    service.init(provider);
  }

  private String nextChangeId() {
    return "change-" + (++changeCount);
  }

  private static NoteChange edit(String changeId, String noteId, int baseRevision, String content) {
    return new NoteChange(changeId, noteId, baseRevision, null, content, null, Hashes.sha256(content), false);
  }

  private PushResult push(String noteId, int baseRevision, String content) throws IOException {
    return service.push("alice", "device-1", edit(nextChangeId(), noteId, baseRevision, content));
  }

  private PushResult pushFrom(String device, String noteId, int baseRevision, String content) throws IOException {
    return service.push("alice", device, edit(nextChangeId(), noteId, baseRevision, content));
  }

  private PushResult delete(String noteId, int baseRevision) throws IOException {
    return service.push("alice", "device-1", new NoteChange(nextChangeId(), noteId, baseRevision, null, null, null, null, true));
  }

  private String content(String noteId) throws IOException {
    return service.getNotes("alice", List.of(new NoteRequest(noteId, null))).notes().get(0).content().apply("");
  }

  /** What the client ends up with: the server's text when the result carries one, its own otherwise. */
  private static String clientText(String submitted, Splice content) {
    return content == null ? submitted : content.apply(submitted);
  }

  @Test
  public void createsANote() throws IOException {
    PushResult result = push(A, 0, BASE);

    assertEquals(PushStatus.APPLIED, result.status());
    assertEquals(1, result.note().revision());
    assertEquals("Title", result.note().title());
    assertEquals(Hashes.sha256(BASE), result.note().contentHash());
    assertNull(result.content());
    assertEquals(BASE, content(A));
  }

  @Test
  public void fastForwards() throws IOException {
    push(A, 0, BASE);
    PushResult result = push(A, 1, BASE + "Third.\n");

    assertEquals(PushStatus.APPLIED, result.status());
    assertEquals(2, result.note().revision());
    assertNull(result.content());
  }

  @Test
  public void pushingTheSameTextAgainCommitsNothing() throws IOException {
    push(A, 0, BASE);
    PushResult result = push(A, 1, BASE);

    assertEquals(PushStatus.APPLIED, result.status());
    assertEquals(1, result.note().revision());
  }

  @Test
  public void mergesAChangeMadeFromAnOlderRevision() throws IOException {
    push(A, 0, BASE);
    pushFrom("laptop", A, 1, BASE.replace("First", "FIRST"));
    String submitted = BASE.replace("Second", "SECOND");
    PushResult result = pushFrom("phone", A, 1, submitted);

    String merged = BASE.replace("First", "FIRST").replace("Second", "SECOND");
    assertEquals(PushStatus.MERGED, result.status());
    assertEquals(3, result.note().revision());
    assertEquals(merged, clientText(submitted, result.content()));
    assertEquals(merged, content(A));
    assertEquals(Hashes.sha256(merged), result.note().contentHash());
  }

  @Test
  public void aConflictKeepsTheOtherSideAndCopiesTheClientsWithCleanChangesInBoth() throws IOException {
    push(A, 0, BASE);
    pushFrom("laptop", A, 1, BASE.replace("First paragraph", "Laptop paragraph"));
    String submitted = BASE.replace("First paragraph", "Phone paragraph").replace("Second", "SECOND");
    PushResult result = pushFrom("phone", A, 1, submitted);

    assertEquals(PushStatus.CONFLICT, result.status());
    String original = BASE.replace("First paragraph", "Laptop paragraph").replace("Second", "SECOND");
    String copy = BASE.replace("First paragraph", "Phone paragraph").replace("Second", "SECOND");
    assertEquals(original, clientText(submitted, result.content()));
    assertEquals(copy, clientText(submitted, result.copyContent()));
    assertEquals(original, content(A));
    assertEquals(copy, content(result.copy().id()));
    assertEquals(3, result.note().revision());
    assertEquals(1, result.copy().revision());
    assertEquals(new ConflictOf(A), result.copy().conflictOf());
    assertNull(result.note().conflictOf());
  }

  @Test
  public void aConflictWithNothingCleanLeavesTheOriginalAlone() throws IOException {
    push(A, 0, "I like cats.");
    pushFrom("laptop", A, 1, "I like dogs.");
    PushResult result = pushFrom("phone", A, 1, "I like birds.");

    assertEquals(PushStatus.CONFLICT, result.status());
    assertEquals(2, result.note().revision());
    assertEquals("I like dogs.", content(A));
    assertEquals("I like birds.", content(result.copy().id()));
  }

  @Test
  public void aRetryReplaysWithoutApplyingTwice() throws IOException {
    NoteChange create = edit("c-create", A, 0, BASE);
    PushResult first = service.push("alice", "device-1", create);
    PushResult again = service.push("alice", "device-1", create);

    assertEquals(first, again);
    assertEquals(BASE, content(A));
    assertEquals(1, service.changes("alice", 0).notes().get(0).revision());
  }

  @Test
  public void aRetriedMergeReplaysTheMerge() throws IOException {
    push(A, 0, BASE);
    pushFrom("laptop", A, 1, BASE.replace("First", "FIRST"));
    NoteChange change = edit("c-merge", A, 1, BASE.replace("Second", "SECOND"));
    PushResult first = service.push("alice", "phone", change);
    pushFrom("laptop", A, 3, BASE.replace("First", "FIRST").replace("Second", "SECOND") + "Later.\n");
    PushResult again = service.push("alice", "phone", change);

    assertEquals(first, again);
  }

  @Test
  public void aRetriedConflictCreatesNoSecondCopy() throws IOException {
    push(A, 0, "I like cats.");
    pushFrom("laptop", A, 1, "I like dogs.");
    NoteChange change = edit("c-conflict", A, 1, "I like birds.");
    PushResult first = service.push("alice", "phone", change);
    PushResult again = service.push("alice", "phone", change);

    assertEquals(first, again);
    assertEquals(2, service.changes("alice", 0).notes().size());
  }

  @Test
  public void editsMadeWhileAMergedPushWasInFlightMergeAgainstWhatWasSent() throws IOException {
    push(A, 0, BASE);
    pushFrom("laptop", A, 1, BASE.replace("First", "FIRST"));
    String sent = BASE.replace("Second", "SECOND");
    PushResult merged = service.push("alice", "phone", edit("c-sent", A, 1, sent));
    assertEquals(PushStatus.MERGED, merged.status());

    // The phone kept typing on top of what it sent, right next to its own change.
    String typed = sent.replace("SECOND paragraph.", "SECOND paragraph, and more.");
    PushResult result = service.push("alice", "phone",
        new NoteChange(nextChangeId(), A, merged.note().revision(), "c-sent", typed, null, Hashes.sha256(typed), false));

    String expected = BASE.replace("First", "FIRST").replace("Second paragraph.", "SECOND paragraph, and more.");
    assertEquals(PushStatus.MERGED, result.status());
    assertEquals(expected, content(A));
    assertEquals(expected, clientText(typed, result.content()));
  }

  @Test
  public void editsMadeWhileAConflictingPushWasInFlightContinueOnTheCopy() throws IOException {
    push(A, 0, "I like cats.");
    pushFrom("laptop", A, 1, "I like dogs.");
    PushResult conflict = service.push("alice", "phone", edit("c-sent", A, 1, "I like birds."));
    String copyId = conflict.copy().id();

    String typed = "I like birds a lot.";
    PushResult result = service.push("alice", "phone",
        new NoteChange(nextChangeId(), copyId, conflict.copy().revision(), "c-sent", typed, null, Hashes.sha256(typed), false));

    assertEquals(PushStatus.APPLIED, result.status());
    assertEquals(typed, content(copyId));
    assertEquals(new ConflictOf(A), result.note().conflictOf());
  }

  @Test
  public void acceptsASpliceOfTheBase() throws IOException {
    push(A, 0, BASE);
    String text = BASE.replace("First", "Better first");
    Splice splice = Splice.compute(BASE, text);
    PushResult result = service.push("alice", "device-1", new NoteChange(nextChangeId(), A, 1, null, null, splice, Hashes.sha256(text), false));

    assertEquals(PushStatus.APPLIED, result.status());
    assertEquals(text, content(A));
  }

  @Test
  public void rejectsASpliceWhoseResultDoesNotMatchTheHash() throws IOException {
    push(A, 0, BASE);
    Splice splice = Splice.compute(BASE, BASE + "x");
    PushResult result = service.push("alice", "device-1",
        new NoteChange(nextChangeId(), A, 1, null, null, splice, Hashes.sha256(BASE + "y"), false));

    assertEquals(PushStatus.REJECTED, result.status());
    assertEquals(RejectReason.BASE_MISMATCH, result.reason());
    assertEquals(BASE, content(A));
  }

  @Test
  public void rejectsASpliceThatDoesNotFitTheBase() throws IOException {
    push(A, 0, "short");
    PushResult result = service.push("alice", "device-1",
        new NoteChange(nextChangeId(), A, 1, null, null, new Splice(10, 10, "x"), Hashes.sha256("x"), false));

    assertEquals(RejectReason.BASE_MISMATCH, result.reason());
  }

  @Test
  public void rejectsATooLargeNote() throws IOException {
    PushResult result = push(A, 0, "x".repeat(1024 * 1024 + 1));

    assertEquals(PushStatus.REJECTED, result.status());
    assertEquals(RejectReason.TOO_LARGE, result.reason());
    assertNull(service.getNotes("alice", List.of(new NoteRequest(A, null))).notes().stream().findFirst().orElse(null));
  }

  @Test
  public void rejectsInvalidChanges() throws IOException {
    assertEquals(RejectReason.INVALID, push("../a", 0, "x").reason());
    assertEquals(RejectReason.INVALID, service.push("alice", "d",
        new NoteChange(nextChangeId(), A, 0, null, "x", new Splice(0, 0, "x"), Hashes.sha256("x"), false)).reason());
    assertEquals(RejectReason.INVALID, service.push("alice", "d",
        new NoteChange(nextChangeId(), A, 0, null, "x", null, null, false)).reason());
    assertEquals(RejectReason.INVALID, service.push("alice", "d",
        new NoteChange(null, A, 0, null, "x", null, Hashes.sha256("x"), false)).reason());
    assertEquals(RejectReason.INVALID, push(A, -1, "x").reason());
  }

  @Test
  public void normalizesLineEndingsAndSendsTheStoredTextBack() throws IOException {
    String submitted = "one\r\ntwo\rthree\n";
    PushResult result = push(A, 0, submitted);

    assertEquals(PushStatus.APPLIED, result.status());
    assertEquals("one\ntwo\nthree\n", clientText(submitted, result.content()));
    assertEquals("one\ntwo\nthree\n", content(A));
  }

  @Test
  public void deletesKeepingTheContent() throws IOException {
    push(A, 0, BASE);
    PushResult result = delete(A, 1);

    assertEquals(PushStatus.APPLIED, result.status());
    assertTrue(result.note().deleted());
    NoteContent read = service.getNotes("alice", List.of(new NoteRequest(A, null))).notes().get(0);
    assertTrue(read.note().deleted());
    assertNull(read.content());
    assertEquals(Hashes.sha256(BASE), read.note().contentHash());
  }

  @Test
  public void aDeleteOfANoteEditedSinceIsRefused() throws IOException {
    push(A, 0, BASE);
    pushFrom("laptop", A, 1, BASE + "Edited.\n");
    PushResult result = delete(A, 1);

    assertEquals(PushStatus.KEPT, result.status());
    assertFalse(result.note().deleted());
    assertEquals(BASE + "Edited.\n", clientText(BASE, result.content()));
  }

  @Test
  public void anEditMadeBeforeADeleteBringsTheNoteBack() throws IOException {
    push(A, 0, BASE);
    pushFrom("laptop", A, 1, BASE);
    delete(A, 1);
    PushResult result = pushFrom("phone", A, 1, BASE + "Phone edit.\n");

    assertEquals(PushStatus.APPLIED, result.status());
    assertFalse(result.note().deleted());
    assertEquals(BASE + "Phone edit.\n", content(A));
  }

  @Test
  public void deletingAnUnknownOrDeletedNoteIsANoOp() throws IOException {
    assertEquals(PushStatus.APPLIED, delete(A, 3).status());
    assertNull(delete(A, 3).note());
    push(B, 0, "b");
    delete(B, 1);
    PushResult again = delete(B, 1);
    assertEquals(PushStatus.APPLIED, again.status());
    assertEquals(2, again.note().revision());
  }

  @Test
  public void creatingANoteThatAlreadyExistsKeepsBothTexts() throws IOException {
    push(A, 0, "From the laptop.");
    PushResult result = pushFrom("phone", A, 0, "From the phone.");

    assertEquals(PushStatus.CONFLICT, result.status());
    assertEquals("From the laptop.", content(A));
    assertEquals("From the phone.", content(result.copy().id()));
  }

  @Test
  public void aNoteTheServerLostIsRecreatedFromTheClient() throws IOException {
    PushResult result = push(A, 7, "Only the client still has this.");

    assertEquals(PushStatus.APPLIED, result.status());
    assertEquals(1, result.note().revision());
    assertEquals("Only the client still has this.", content(A));
  }

  @Test
  public void aBaseAheadOfTheServerGoesToAConflictCopy() throws IOException {
    push(A, 0, "Server text.");
    PushResult result = push(A, 5, "Client text.");

    assertEquals(PushStatus.CONFLICT, result.status());
    assertEquals("Server text.", content(A));
    assertEquals("Client text.", content(result.copy().id()));
    assertEquals(1, result.note().revision());
  }

  @Test
  public void pagesChangesWithACursorThatResumesWhereThePageEnded() throws IOException {
    for (int i = 1; i <= 5; i++) push(id(100 + i), 0, "note " + i);

    ChangesPage first = service.changes("alice", 0);
    assertTrue(first.more());
    assertEquals(3, first.notes().size());
    assertEquals(3, first.seq());
    ChangesPage second = service.changes("alice", first.seq());
    assertFalse(second.more());
    assertEquals(List.of(id(104), id(105)), second.notes().stream().map(NoteInfo::id).toList());
    assertEquals(5, second.seq());
    ChangesPage none = service.changes("alice", second.seq());
    assertEquals(List.of(), none.notes());
    assertEquals(5, none.seq());
    assertEquals(first.storeId(), none.storeId());
  }

  @Test
  public void servesContentAsASpliceOfTheRevisionTheClientHas() throws IOException {
    String big = "line\n".repeat(1000);
    push(A, 0, big);
    push(A, 1, big + "added\n");

    NoteContent fromRevision1 = service.getNotes("alice", List.of(new NoteRequest(A, 1))).notes().get(0);
    assertEquals(new Splice(big.length(), 0, "added\n"), fromRevision1.content());
    assertEquals(big + "added\n", fromRevision1.content().apply(big));

    NoteContent full = service.getNotes("alice", List.of(new NoteRequest(A, null))).notes().get(0);
    assertEquals(new Splice(0, 0, big + "added\n"), full.content());
    NoteContent unknownRevision = service.getNotes("alice", List.of(new NoteRequest(A, 9))).notes().get(0);
    assertEquals(0, unknownRevision.content().prefixLength());
  }

  @Test
  public void sendsTheFullTextWhenItIsSmallerThanASplice() throws IOException {
    push(A, 0, "abc");
    push(A, 1, "xyz");

    assertEquals(new Splice(0, 0, "xyz"), service.getNotes("alice", List.of(new NoteRequest(A, 1))).notes().get(0).content());
  }

  @Test
  public void listsMissingNotesAndLimitsTheRequest() throws IOException {
    push(A, 0, "a");
    NotesResult result = service.getNotes("alice", List.of(new NoteRequest(A, null), new NoteRequest(B, null)));
    assertEquals(1, result.notes().size());
    assertEquals(List.of(B), result.missing());

    List<NoteRequest> tooMany = new ArrayList<>(Collections.nCopies(NoteService.MAX_NOTES_PER_REQUEST + 1, new NoteRequest(A, null)));
    assertThrows(InvalidInputException.class, () -> service.getNotes("alice", tooMany));
  }

  @Test
  public void keepsUsersApart() throws IOException {
    push(A, 0, "alice's");
    service.push("bob", "device-9", edit(nextChangeId(), A, 0, "bob's"));

    assertEquals("alice's", content(A));
    assertEquals("bob's", service.getNotes("bob", List.of(new NoteRequest(A, null))).notes().get(0).content().apply(""));
    assertNotEquals(service.changes("alice", 0).storeId(), service.changes("bob", 0).storeId());
    assertNotNull(stores.getStore("bob"));
  }
}
