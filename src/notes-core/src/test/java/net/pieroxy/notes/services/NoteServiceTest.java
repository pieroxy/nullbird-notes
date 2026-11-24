package net.pieroxy.notes.services;

import net.pieroxy.notes.api.metadata.InvalidInputException;
import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.sync.ChangesPage;
import net.pieroxy.notes.sync.Merge3;
import net.pieroxy.notes.sync.MetaChange;
import net.pieroxy.notes.sync.NoteChange;
import net.pieroxy.notes.sync.NoteContent;
import net.pieroxy.notes.sync.NoteInfo;
import net.pieroxy.notes.sync.NoteRequest;
import net.pieroxy.notes.sync.NotesResult;
import net.pieroxy.notes.sync.PushResult;
import net.pieroxy.notes.sync.PushStatus;
import net.pieroxy.notes.sync.RejectReason;
import net.pieroxy.notes.sync.Splice;
import net.pieroxy.notes.sync.TagEvent;
import net.pieroxy.notes.sync.TagOrderChange;
import net.pieroxy.notes.storage.UserNoteStore;
import net.pieroxy.notes.utils.Hashes;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
        new ImportService(), new FontService(), new WebServerService());
    stores.init(provider);
    service.init(provider);
  }

  @After
  public void tearDown() {
    service.destroy();
  }

  private String nextChangeId() {
    return "change-" + (++changeCount);
  }

  private static NoteChange edit(String changeId, String noteId, int baseRevision, String content) {
    return new NoteChange(changeId, noteId, baseRevision, null, content, null, Hashes.sha256(content), false, null);
  }

  private PushResult push(String noteId, int baseRevision, String content) throws IOException {
    return service.push("alice", "device-1", edit(nextChangeId(), noteId, baseRevision, content));
  }

  private PushResult pushFrom(String device, String noteId, int baseRevision, String content) throws IOException {
    return service.push("alice", device, edit(nextChangeId(), noteId, baseRevision, content));
  }

  private PushResult delete(String noteId, int baseRevision) throws IOException {
    return service.push("alice", "device-1", new NoteChange(nextChangeId(), noteId, baseRevision, null, null, null, null, true, null));
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
  public void changesToTheSameWordsKeepBothVersionsUnderAMarkerWithCleanChangesApplied() throws IOException {
    push(A, 0, BASE);
    pushFrom("laptop", A, 1, BASE.replace("First paragraph", "Laptop paragraph"));
    String submitted = BASE.replace("First paragraph", "Phone paragraph").replace("Second", "SECOND");
    PushResult result = pushFrom("phone", A, 1, submitted);

    String merged = "Title\n\n" + Merge3.MARKER + "\n" + "Laptop Phone paragraph.\n\nSECOND paragraph.\n";
    assertEquals(PushStatus.MERGED, result.status());
    assertEquals(merged, clientText(submitted, result.content()));
    assertEquals(merged, content(A));
    assertEquals(3, result.note().revision());
    assertEquals(1, service.changes("alice", 0).notes().size());
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
  public void aRetriedClashingMergeIsNotAppliedTwice() throws IOException {
    push(A, 0, "I like cats.");
    pushFrom("laptop", A, 1, "I like dogs.");
    NoteChange change = edit("c-clash", A, 1, "I like birds.");
    PushResult first = service.push("alice", "phone", change);
    PushResult again = service.push("alice", "phone", change);

    assertEquals(first, again);
    assertEquals(Merge3.MARKER + "\n" + "I like dogs birds.", content(A));
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
        new NoteChange(nextChangeId(), A, merged.note().revision(), "c-sent", typed, null, Hashes.sha256(typed), false, null));

    String expected = BASE.replace("First", "FIRST").replace("Second paragraph.", "SECOND paragraph, and more.");
    assertEquals(PushStatus.MERGED, result.status());
    assertEquals(expected, content(A));
    assertEquals(expected, clientText(typed, result.content()));
  }

  @Test
  public void acceptsASpliceOfTheBase() throws IOException {
    push(A, 0, BASE);
    String text = BASE.replace("First", "Better first");
    Splice splice = Splice.compute(BASE, text);
    PushResult result = service.push("alice", "device-1", new NoteChange(nextChangeId(), A, 1, null, null, splice, Hashes.sha256(text), false, null));

    assertEquals(PushStatus.APPLIED, result.status());
    assertEquals(text, content(A));
  }

  @Test
  public void rejectsASpliceWhoseResultDoesNotMatchTheHash() throws IOException {
    push(A, 0, BASE);
    Splice splice = Splice.compute(BASE, BASE + "x");
    PushResult result = service.push("alice", "device-1",
        new NoteChange(nextChangeId(), A, 1, null, null, splice, Hashes.sha256(BASE + "y"), false, null));

    assertEquals(PushStatus.REJECTED, result.status());
    assertEquals(RejectReason.BASE_MISMATCH, result.reason());
    assertEquals(BASE, content(A));
  }

  @Test
  public void rejectsASpliceThatDoesNotFitTheBase() throws IOException {
    push(A, 0, "short");
    PushResult result = service.push("alice", "device-1",
        new NoteChange(nextChangeId(), A, 1, null, null, new Splice(10, 10, "x"), Hashes.sha256("x"), false, null));

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
        new NoteChange(nextChangeId(), A, 0, null, "x", new Splice(0, 0, "x"), Hashes.sha256("x"), false, null)).reason());
    assertEquals(RejectReason.INVALID, service.push("alice", "d",
        new NoteChange(nextChangeId(), A, 0, null, "x", null, null, false, null)).reason());
    assertEquals(RejectReason.INVALID, service.push("alice", "d",
        new NoteChange(null, A, 0, null, "x", null, Hashes.sha256("x"), false, null)).reason());
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

    assertEquals(PushStatus.MERGED, result.status());
    assertEquals(Merge3.MARKER + "\n" + "From the laptop. From the phone.", content(A));
  }

  @Test
  public void aNoteTheServerLostIsRecreatedFromTheClient() throws IOException {
    PushResult result = push(A, 7, "Only the client still has this.");

    assertEquals(PushStatus.APPLIED, result.status());
    assertEquals(1, result.note().revision());
    assertEquals("Only the client still has this.", content(A));
  }

  @Test
  public void aBaseTheServerDoesntHaveIsAskedForThenMergedWith() throws IOException {
    push(A, 0, BASE);
    push(A, 1, BASE.replace("First", "FIRST"));
    String typed = BASE.replace("Second", "SECOND");
    Splice splice = Splice.compute(BASE, typed);
    PushResult gone = service.push("alice", "phone", new NoteChange(nextChangeId(), A, 9, null, null, splice, Hashes.sha256(typed), false, null));
    assertEquals(PushStatus.REJECTED, gone.status());
    assertEquals(RejectReason.BASE_GONE, gone.reason());
    gone = pushFrom("phone", A, 9, typed);
    assertEquals(RejectReason.BASE_GONE, gone.reason());

    PushResult result = service.push("alice", "phone", new NoteChange(nextChangeId(), A, 9, null, typed, null, Hashes.sha256(typed), false, BASE));

    String merged = BASE.replace("First", "FIRST").replace("Second", "SECOND");
    assertEquals(PushStatus.MERGED, result.status());
    assertEquals(merged, content(A));
    assertEquals(merged, clientText(typed, result.content()));
  }

  @Test
  public void aGoneBaseDoesntMatterWhenTheTextIsTheServersAlready() throws IOException {
    push(A, 0, "Server text.");
    assertEquals(PushStatus.APPLIED, push(A, 5, "Server text.").status());
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
  public void awaitingChangesAnswersRightAwayWhenThereAreSome() throws Exception {
    push(A, 0, "a");
    CompletableFuture<ChangesPage> future = service.awaitChanges("alice", 0, 60_000);

    assertTrue(future.isDone());
    assertEquals(List.of(A), future.get().notes().stream().map(NoteInfo::id).toList());
  }

  @Test
  public void awaitingChangesWithACursorAheadOfTheServerAnswersRightAway() throws Exception {
    push(A, 0, "a");
    CompletableFuture<ChangesPage> future = service.awaitChanges("alice", 9, 60_000);

    assertTrue(future.isDone());
    assertEquals(1, future.get().seq());
  }

  @Test
  public void awaitingChangesWakesUpOnTheNextChange() throws Exception {
    push(A, 0, "a");
    CompletableFuture<ChangesPage> future = service.awaitChanges("alice", 1, 60_000);
    assertFalse(future.isDone());

    push(B, 0, "b");
    ChangesPage page = future.get(5, TimeUnit.SECONDS);
    assertEquals(List.of(B), page.notes().stream().map(NoteInfo::id).toList());
    assertEquals(2, page.seq());
  }

  @Test
  public void awaitingChangesTimesOutWithNothingNew() throws Exception {
    push(A, 0, "a");
    long start = System.nanoTime();
    ChangesPage page = service.awaitChanges("alice", 1, 200).get(5, TimeUnit.SECONDS);

    assertTrue(System.nanoTime() - start >= 200_000_000L);
    assertEquals(List.of(), page.notes());
    assertEquals(1, page.seq());
  }

  @Test
  public void awaitingChangesOnlyWakesUpForThatUser() throws Exception {
    CompletableFuture<ChangesPage> alice = service.awaitChanges("alice", 0, 300);
    service.push("bob", "device-9", edit(nextChangeId(), A, 0, "bob's"));

    assertEquals(List.of(), alice.get(5, TimeUnit.SECONDS).notes());
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

  private PushResult pushMeta(String noteId, List<String> addTags, List<String> removeTags, Map<String, String> setOptions,
                              List<String> unsetOptions) throws IOException {
    return service.pushMeta("alice", "device-1", new MetaChange(nextChangeId(), noteId, addTags, removeTags, setOptions, unsetOptions));
  }

  @Test
  public void tagsAndOptionsChangeWithoutTouchingTheText() throws IOException {
    PushResult created = push(A, 0, BASE);
    PushResult result = pushMeta(A, List.of("work", "todo"), null, Map.of("monospace", "true"), null);

    assertEquals(PushStatus.APPLIED, result.status());
    assertEquals(List.of("work", "todo"), result.note().tags());
    assertEquals(Map.of("monospace", "true"), result.note().options());
    assertEquals(1, result.note().revision());
    assertEquals(created.note().modifiedAt(), result.note().modifiedAt());
    assertEquals(List.of(result.note()), service.changes("alice", created.note().seq()).notes());
  }

  @Test
  public void aTextPushBasedOnTheRevisionBeforeAMetadataChangeFastForwards() throws IOException {
    push(A, 0, BASE);
    pushMeta(A, List.of("work"), null, null, null);
    PushResult result = push(A, 1, BASE + "More.\n");

    assertEquals(PushStatus.APPLIED, result.status());
    assertEquals(List.of("work"), result.note().tags());
  }

  @Test
  public void concurrentMetadataChangesAllApply() throws IOException {
    push(A, 0, BASE);
    pushMeta(A, List.of("work"), null, Map.of("monospace", "true"), null);
    PushResult result = service.pushMeta("alice", "phone", new MetaChange(nextChangeId(), A, List.of("home"), null, Map.of("nowrap", "true"), null));

    assertEquals(List.of("work", "home"), result.note().tags());
    assertEquals(Map.of("monospace", "true", "nowrap", "true"), result.note().options());
  }

  @Test
  public void aMetadataChangeThatChangesNothingCommitsNothing() throws IOException {
    PushResult created = push(A, 0, BASE);
    PushResult result = pushMeta(A, null, List.of("absent"), null, List.of("absent"));

    assertEquals(PushStatus.APPLIED, result.status());
    assertEquals(created.note().seq(), result.note().seq());
  }

  @Test
  public void aRetriedMetadataChangeIsNotAppliedAgain() throws IOException {
    push(A, 0, BASE);
    MetaChange change = new MetaChange("meta-1", A, List.of("work"), null, null, null);
    service.pushMeta("alice", "device-1", change);
    pushMeta(A, null, List.of("work"), null, null);
    PushResult replayed = service.pushMeta("alice", "device-1", change);

    assertEquals(PushStatus.APPLIED, replayed.status());
    assertEquals(List.of(), replayed.note().tags());
  }

  @Test
  public void aMetadataChangeToADeletedNoteIsIgnored() throws IOException {
    push(A, 0, BASE);
    PushResult deleted = delete(A, 1);
    PushResult result = pushMeta(A, List.of("work"), null, null, null);

    assertEquals(PushStatus.APPLIED, result.status());
    assertEquals(List.of(), result.note().tags());
    assertEquals(deleted.note().seq(), result.note().seq());
  }

  @Test
  public void aMetadataChangeToAnUnknownNoteIsRejected() throws IOException {
    PushResult result = pushMeta(A, List.of("work"), null, null, null);

    assertEquals(PushStatus.REJECTED, result.status());
    assertEquals(RejectReason.UNKNOWN_NOTE, result.reason());
  }

  @Test
  public void rejectsInvalidTagsAndOptions() throws IOException {
    push(A, 0, BASE);

    for (List<String> tags : List.of(List.of(""), List.of("two words"), List.of("tab\there"), List.of("nbsp\u00a0here"),
        List.of("x".repeat(NoteService.MAX_TAG_LENGTH + 1)))) {
      assertEquals(tags.toString(), RejectReason.INVALID, pushMeta(A, tags, null, null, null).reason());
    }
    assertEquals(RejectReason.INVALID, pushMeta(A, null, null, Map.of("bad key", "true"), null).reason());
    assertEquals(RejectReason.INVALID, pushMeta(A, null, null, Map.of("k", "v".repeat(NoteService.MAX_OPTION_VALUE_LENGTH + 1)), null).reason());
    assertEquals(RejectReason.INVALID, pushMeta(A, null, null, null, List.of("bad key")).reason());
    assertEquals(PushStatus.APPLIED, pushMeta(A, List.of("x".repeat(NoteService.MAX_TAG_LENGTH), "Émoji🙂"), null, null, null).status());
  }

  @Test
  public void limitsTheNumberOfTagsAndOptionsOnANote() throws IOException {
    push(A, 0, BASE);
    List<String> tags = new ArrayList<>();
    for (int i = 0; i < NoteService.MAX_TAGS; i++) tags.add("tag" + i);
    assertEquals(PushStatus.APPLIED, pushMeta(A, tags, null, null, null).status());

    assertEquals(RejectReason.INVALID, pushMeta(A, List.of("one-more"), null, null, null).reason());
    assertEquals(PushStatus.APPLIED, pushMeta(A, List.of("one-more"), List.of("tag0"), null, null).status());
  }

  @Test
  public void aMergeKeepsTheTagsAndOptions() throws IOException {
    push(A, 0, BASE);
    pushMeta(A, List.of("work"), null, Map.of("monospace", "true"), null);
    pushFrom("laptop", A, 1, BASE.replace("First", "Laptop"));
    PushResult result = pushFrom("phone", A, 1, BASE.replace("First", "Phone"));

    assertEquals(PushStatus.MERGED, result.status());
    assertEquals(List.of("work"), result.note().tags());
    assertEquals(Map.of("monospace", "true"), result.note().options());
  }

  @Test
  public void aRetryOfAChangeWhoseRevisionWasThinnedOutGetsTheNoteAsItIsNow() throws IOException {
    push(A, 0, BASE);
    PushResult second = push(A, 1, BASE + "1");
    push(A, 2, BASE + "2");
    push(A, 3, BASE + "3");
    // All on the same day, 200 days ago by then: only the first revision and the head stay.
    stores.getStore("alice").thin(System.currentTimeMillis() + Duration.ofDays(200).toMillis());
    assertNull(stores.getStore("alice").readRevision(A, second.note().revision()));

    PushResult replay = service.push("alice", "device-1", edit(second.changeId(), A, 1, BASE + "1"));
    assertEquals(PushStatus.APPLIED, replay.status());
    assertEquals(4, replay.note().revision());
    assertEquals(BASE + "3", replay.content().apply(""));
  }

  // ---- Renaming, merging and removing tags ----

  private List<String> tags(String noteId) throws IOException {
    return stores.getStore("alice").get(noteId).tags();
  }

  private static final String C = id(3);

  @Test
  public void renamingATagChangesItOnEveryNoteDeletedOnesIncludedAndKeepsItsPlace() throws Exception {
    push(A, 0, "a");
    push(B, 0, "b");
    push(C, 0, "c");
    pushMeta(A, List.of("work", "home"), null, null, null);
    pushMeta(B, List.of("work"), null, null, null);
    pushMeta(C, List.of("home"), null, null, null);
    delete(B, 1);
    service.pushTagOrder("alice", new TagOrderChange(0, List.of("work", "home")));

    assertEquals(List.of(A, B), service.renameTag("alice", "device-1", "work", "job"));
    assertEquals(List.of("home", "job"), tags(A));
    assertEquals(List.of("job"), tags(B));
    assertEquals(List.of("home"), tags(C));
    assertEquals(List.of("job", "home"), stores.getStore("alice").getTagOrder().tags());
  }

  @Test
  public void renamingToATagInUseMergesThemTheTargetKeepingItsPlace() throws Exception {
    push(A, 0, "a");
    push(B, 0, "b");
    pushMeta(A, List.of("Work", "work"), null, null, null);
    pushMeta(B, List.of("Work"), null, null, null);
    service.pushTagOrder("alice", new TagOrderChange(0, List.of("work", "other", "Work")));
    pushMeta(B, List.of("other"), null, null, null);

    assertEquals(List.of(A, B), service.renameTag("alice", "device-1", "Work", "work"));
    assertEquals(List.of("work"), tags(A));
    assertEquals(List.of("other", "work"), tags(B));
    assertEquals(List.of("work", "other"), stores.getStore("alice").getTagOrder().tags());
  }

  @Test
  public void removingATagTakesItOffEveryNote() throws Exception {
    push(A, 0, "a");
    push(B, 0, "b");
    pushMeta(A, List.of("drafts", "work"), null, null, null);
    pushMeta(B, List.of("drafts"), null, null, null);

    assertEquals(List.of(A, B), service.deleteTag("alice", "device-1", "drafts"));
    assertEquals(List.of("work"), tags(A));
    assertEquals(List.of(), tags(B));
    assertEquals(List.of(), service.deleteTag("alice", "device-1", "drafts"));
    assertThrows(InvalidInputException.class, () -> service.renameTag("alice", "device-1", "work", "has space"));
    assertEquals(List.of(), service.renameTag("alice", "device-1", "work", "work"));
  }

  @Test
  public void clientsGetTheEventsWithTheChanges() throws Exception {
    push(A, 0, "a");
    pushMeta(A, List.of("work"), null, null, null);
    long cursor = service.changes("alice", 0).seq();
    service.renameTag("alice", "device-1", "work", "job");
    service.deleteTag("alice", "device-1", "job");
    ChangesPage page = service.changes("alice", cursor);
    assertEquals(List.of(new TagEvent("work", "job"), new TagEvent("job", null)), page.tagEvents());
    assertNull(service.changes("alice", page.seq()).tagEvents());
  }

  @Test
  public void aChangeMadeBeforeTheClientKnewOfARenameFollowsIt() throws Exception {
    push(A, 0, "a");
    push(B, 0, "b");
    pushMeta(A, List.of("work"), null, null, null);
    long cursor = service.changes("alice", 0).seq();
    service.renameTag("alice", "device-1", "work", "job");

    // An offline device adds the old name to another note, and removes it from the first.
    service.pushMeta("alice", "phone", new MetaChange(nextChangeId(), B, List.of("work", "home"), null, null, null), cursor);
    assertEquals(List.of("job", "home"), tags(B));
    service.pushMeta("alice", "phone", new MetaChange(nextChangeId(), A, null, List.of("work"), null, null), cursor);
    assertEquals(List.of(), tags(A));

    // One that knew: a new tag of that name.
    long now = service.changes("alice", 0).seq();
    service.pushMeta("alice", "phone", new MetaChange(nextChangeId(), A, List.of("work"), null, null, null), now);
    assertEquals(List.of("work"), tags(A));
  }

  @Test
  public void aChangeMadeBeforeARemovalDoesntBringTheTagBack() throws Exception {
    push(A, 0, "a");
    pushMeta(A, List.of("drafts"), null, null, null);
    long cursor = service.changes("alice", 0).seq();
    service.deleteTag("alice", "device-1", "drafts");
    service.pushMeta("alice", "phone", new MetaChange(nextChangeId(), A, List.of("drafts", "home"), null, null, null), cursor);
    assertEquals(List.of("home"), tags(A));
  }

  @Test
  public void theEventsSurviveARestart() throws Exception {
    push(A, 0, "a");
    pushMeta(A, List.of("work"), null, null, null);
    long cursor = service.changes("alice", 0).seq();
    service.renameTag("alice", "device-1", "work", "job");
    long seq = stores.getStore("alice").getLastSeq();
    UserNoteStore reopened = UserNoteStore.open(tmp.getRoot().toPath().resolve("users").resolve("alice"), java.time.Clock.systemUTC());
    assertEquals(seq, reopened.getLastSeq());
    assertEquals(1, reopened.tagEventsAfter(cursor).size());
  }
}
