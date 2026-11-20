package net.pieroxy.notes.services;

import net.pieroxy.notes.api.metadata.InvalidInputException;
import net.pieroxy.notes.storage.ChangeRecord;
import net.pieroxy.notes.storage.ConflictOf;
import net.pieroxy.notes.storage.MetaWrite;
import net.pieroxy.notes.storage.NoteMeta;
import net.pieroxy.notes.storage.NoteWrite;
import net.pieroxy.notes.storage.Revision;
import net.pieroxy.notes.storage.StaleRevisionException;
import net.pieroxy.notes.storage.UserNoteStore;
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
import net.pieroxy.notes.utils.Hashes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The sync rules, on top of each user's {@link UserNoteStore}. A push is decided and committed
 * while holding the user's store lock, so that no other change of that user lands in between.
 * <p>
 * A push's text is merged with what happened since the text it derives from (its ancestor): a
 * clean merge is stored, a conflict leaves the other side in the note and puts the client's side
 * in a new copy (see {@link ConflictOf}). Edits beat deletes, both ways. Nothing is ever dropped.
 * <p>
 * Tags and options change on their own (see {@link MetaChange}), never conflicting with the text.
 */
public class NoteService implements Service {
  private final static Logger LOGGER = Logger.getLogger(NoteService.class.getName());
  static final int CHANGES_PAGE_SIZE = 500;
  static final int MAX_NOTES_PER_REQUEST = 200;
  private final static int MAX_ID_LENGTH = 64;
  static final int MAX_TAGS = 100;
  static final int MAX_TAG_LENGTH = 64;
  static final int MAX_OPTIONS = 32;
  static final int MAX_OPTION_VALUE_LENGTH = 256;
  private static final Pattern OPTION_KEY_PATTERN = Pattern.compile("[A-Za-z0-9-]{1,64}");

  private final int changesPageSize;
  private SettingsService settings;
  private NoteStoreService noteStores;
  private ScheduledExecutorService timer;

  public NoteService() {
    this(CHANGES_PAGE_SIZE);
  }

  NoteService(int changesPageSize) {
    this.changesPageSize = changesPageSize;
  }

  @Override
  public List<Class<? extends Service>> getDependencies() {
    return List.of(SettingsService.class, NoteStoreService.class);
  }

  @Override
  public void init(IServiceProvider serviceProvider) {
    settings = serviceProvider.getSettingsService();
    noteStores = serviceProvider.getNoteStoreService();
    timer = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "long-poll-timer");
      t.setDaemon(true);
      return t;
    });
  }

  @Override
  public void destroy() {
    if (timer != null) timer.shutdownNow();
  }

  public PushResult push(String username, String deviceId, NoteChange change) throws IOException {
    String invalid = validate(deviceId, change);
    if (invalid != null) return rejected(change, RejectReason.INVALID, invalid);
    UserNoteStore store = noteStores.getStore(username);
    synchronized (store) {
      ChangeRecord done = store.findChange(change.changeId());
      if (done != null) return replay(store, change, done);
      NoteMeta head = store.get(change.noteId());
      String ancestor = ancestor(store, change, head);
      return change.deleted() ? delete(store, deviceId, change, head, ancestor) : edit(store, username, deviceId, change, head, ancestor);
    }
  }

  /** A metadata change to a deleted note is accepted, and ignored. */
  public PushResult pushMeta(String username, String deviceId, MetaChange change) throws IOException {
    String invalid = validate(deviceId, change);
    if (invalid != null) return rejected(change.changeId(), change.noteId(), RejectReason.INVALID, invalid);
    UserNoteStore store = noteStores.getStore(username);
    synchronized (store) {
      NoteMeta head = store.get(change.noteId());
      if (head == null) return rejected(change.changeId(), change.noteId(), RejectReason.UNKNOWN_NOTE, "No such note.");
      if (head.deleted() || change.noteId().equals(store.findMetaChange(change.changeId()))) return applied(change.changeId(), head);
      MetaWrite write = new MetaWrite(change.changeId(), deviceId, change.addTags(), change.removeTags(), change.setOptions(),
          change.unsetOptions());
      List<String> tags = UserNoteStore.tagsAfter(head, write);
      Map<String, String> options = UserNoteStore.optionsAfter(head, write);
      if (tags.size() > MAX_TAGS) return rejected(change.changeId(), change.noteId(), RejectReason.INVALID, "At most " + MAX_TAGS + " tags.");
      if (options.size() > MAX_OPTIONS) {
        return rejected(change.changeId(), change.noteId(), RejectReason.INVALID, "At most " + MAX_OPTIONS + " options.");
      }
      if (tags.equals(head.tags()) && options.equals(head.options())) return applied(change.changeId(), head);
      return applied(change.changeId(), store.commitMeta(head.id(), write));
    }
  }

  public ChangesPage changes(String username, long since) throws IOException {
    UserNoteStore store = noteStores.getStore(username);
    synchronized (store) {
      List<NoteMeta> changed = store.changesSince(since);
      boolean more = changed.size() > changesPageSize;
      List<NoteMeta> page = more ? changed.subList(0, changesPageSize) : changed;
      long seq = more ? page.get(page.size() - 1).seq() : store.getLastSeq();
      return new ChangesPage(store.getStoreId(), seq, page.stream().map(NoteService::info).toList(), more);
    }
  }

  /**
   * {@link #changes} as soon as there's something to report: right away if the user's seq isn't
   * {@code since} (including a cursor ahead of the server, which the client must learn about),
   * otherwise on their next change, or after {@code timeoutMillis} with nothing new. Waiting holds
   * no thread.
   */
  public CompletableFuture<ChangesPage> awaitChanges(String username, long since, long timeoutMillis) throws IOException {
    UserNoteStore store = noteStores.getStore(username);
    CompletableFuture<Void> signal = new CompletableFuture<>();
    Runnable cancel = store.awaitSeqChange(since, () -> signal.complete(null));
    if (signal.isDone()) return CompletableFuture.completedFuture(changes(username, since));

    ScheduledFuture<?> timeout = timer.schedule(() -> signal.complete(null), timeoutMillis, TimeUnit.MILLISECONDS);
    signal.whenComplete((ignored, error) -> {
      timeout.cancel(false);
      cancel.run();
    });
    // Off the thread that completed the signal: possibly a commit, holding the store's lock.
    return signal.thenApplyAsync(ignored -> {
      try {
        return changes(username, since);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  /** @throws InvalidInputException past {@link #MAX_NOTES_PER_REQUEST}. */
  public NotesResult getNotes(String username, List<NoteRequest> requests) throws IOException {
    if (requests.size() > MAX_NOTES_PER_REQUEST) {
      throw new InvalidInputException("At most " + MAX_NOTES_PER_REQUEST + " notes per request.");
    }
    UserNoteStore store = noteStores.getStore(username);
    synchronized (store) {
      List<NoteContent> notes = new ArrayList<>();
      List<String> missing = new ArrayList<>();
      for (NoteRequest request : requests) {
        NoteMeta meta = store.get(request.id());
        if (meta == null) {
          missing.add(request.id());
        } else if (meta.deleted()) {
          notes.add(new NoteContent(info(meta), null));
        } else {
          String content = store.readContent(meta.id());
          Revision have = request.haveRevision() == null ? null : store.readRevision(meta.id(), request.haveRevision());
          notes.add(new NoteContent(info(meta), have == null ? full(content) : smallest(have.content(), content)));
        }
      }
      return new NotesResult(notes, missing);
    }
  }

  private String validate(String deviceId, NoteChange change) {
    if (change.changeId() == null || change.changeId().isEmpty() || change.changeId().length() > MAX_ID_LENGTH) return "Invalid changeId.";
    if (change.noteId() == null || !UserNoteStore.NOTE_ID_PATTERN.matcher(change.noteId()).matches()) return "Invalid noteId.";
    if (deviceId != null && deviceId.length() > MAX_ID_LENGTH) return "Invalid deviceId.";
    if (change.baseRevision() < 0) return "Invalid baseRevision.";
    if (change.deleted()) return null;
    if ((change.content() == null) == (change.splice() == null)) return "Exactly one of content and splice is expected.";
    if (change.contentHash() == null) return "contentHash is required.";
    return null;
  }

  private String validate(String deviceId, MetaChange change) {
    if (change.changeId() == null || change.changeId().isEmpty() || change.changeId().length() > MAX_ID_LENGTH) return "Invalid changeId.";
    if (change.noteId() == null || !UserNoteStore.NOTE_ID_PATTERN.matcher(change.noteId()).matches()) return "Invalid noteId.";
    if (deviceId != null && deviceId.length() > MAX_ID_LENGTH) return "Invalid deviceId.";
    for (List<String> tags : Arrays.asList(change.addTags(), change.removeTags())) {
      if (tags == null) continue;
      if (tags.size() > MAX_TAGS) return "At most " + MAX_TAGS + " tags.";
      for (String tag : tags) {
        if (!isValidTag(tag)) return "Invalid tag: tags are 1 to " + MAX_TAG_LENGTH + " characters, without whitespace.";
      }
    }
    if (change.setOptions() != null) {
      if (change.setOptions().size() > MAX_OPTIONS) return "At most " + MAX_OPTIONS + " options.";
      for (Map.Entry<String, String> option : change.setOptions().entrySet()) {
        if (!isValidOptionKey(option.getKey())) return "Invalid option name.";
        if (option.getValue() == null || option.getValue().length() > MAX_OPTION_VALUE_LENGTH) return "Invalid value for option " + option.getKey() + ".";
      }
    }
    if (change.unsetOptions() != null) {
      if (change.unsetOptions().size() > MAX_OPTIONS) return "At most " + MAX_OPTIONS + " options.";
      for (String key : change.unsetOptions()) {
        if (!isValidOptionKey(key)) return "Invalid option name.";
      }
    }
    return null;
  }

  static boolean isValidTag(String tag) {
    if (tag == null || tag.isEmpty() || tag.codePointCount(0, tag.length()) > MAX_TAG_LENGTH) return false;
    return tag.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
  }

  private static boolean isValidOptionKey(String key) {
    return key != null && OPTION_KEY_PATTERN.matcher(key).matches();
  }

  /** The text the client's change derives from; null if it can't be found. */
  private static String ancestor(UserNoteStore store, NoteChange change, NoteMeta head) throws IOException {
    if (change.basedOnChangeId() != null) {
      ChangeRecord based = store.findChange(change.basedOnChangeId());
      if (based != null && based.noteId().equals(change.noteId())) {
        Revision revision = store.readRevision(based.noteId(), based.revision());
        if (revision != null) return revision.submittedContent() != null ? revision.submittedContent() : revision.content();
      }
    }
    if (change.baseRevision() == 0) return "";
    if (head == null) return null;
    Revision base = store.readRevision(change.noteId(), change.baseRevision());
    return base == null ? null : base.content();
  }

  private PushResult delete(UserNoteStore store, String deviceId, NoteChange change, NoteMeta head, String ancestor) throws IOException {
    if (head == null) return new PushResult(change.changeId(), PushStatus.APPLIED, null, null, null, null, null, null);
    if (head.deleted()) return applied(change, head, null);
    String content = store.readContent(head.id());
    if (change.baseRevision() != head.revision()) {
      LOGGER.info("Delete of note " + head.id() + " from device " + deviceId + " refused: edited since revision " + change.baseRevision());
      return new PushResult(change.changeId(), PushStatus.KEPT, null, null, info(head), splice(ancestor, content), null, null);
    }
    NoteMeta deleted = commit(store, head, new NoteWrite(content, true, change.changeId(), deviceId, null, head.conflictOf()));
    return applied(change, deleted, null);
  }

  private PushResult edit(UserNoteStore store, String username, String deviceId, NoteChange change, NoteMeta head, String ancestor)
      throws IOException {
    String submitted;
    if (change.splice() != null) {
      if (ancestor == null) return rejected(change, RejectReason.BASE_MISMATCH, "The splice's base text is unknown.");
      try {
        submitted = change.splice().apply(ancestor);
      } catch (IllegalArgumentException e) {
        return rejected(change, RejectReason.BASE_MISMATCH, e.getMessage());
      }
    } else {
      submitted = change.content();
    }
    if (!Hashes.sha256(submitted).equals(change.contentHash())) {
      return rejected(change, RejectReason.BASE_MISMATCH, "The text doesn't match contentHash.");
    }
    String text = normalize(submitted);
    long size = text.getBytes(StandardCharsets.UTF_8).length;
    if (size > settings.getMaxNoteSize()) {
      return rejected(change, RejectReason.TOO_LARGE, "The note is " + size + " bytes, the limit is " + settings.getMaxNoteSize() + ".");
    }

    if (head == null) {
      if (change.baseRevision() > 0) {
        LOGGER.warning("Note " + change.noteId() + " of " + username + " unknown at revision " + change.baseRevision()
            + ", recreated from device " + deviceId);
      }
      NoteMeta created = commit(store, null, new NoteWrite(text, false, change.changeId(), deviceId, submitted, null), change.noteId());
      return applied(change, created, splice(submitted, text));
    }

    String current = store.readContent(head.id());
    if (ancestor == null) {
      if (text.equals(current)) {
        if (!head.deleted()) return applied(change, head, splice(submitted, text));
        NoteMeta undeleted = commit(store, head, new NoteWrite(text, false, change.changeId(), deviceId, submitted, head.conflictOf()));
        return applied(change, undeleted, splice(submitted, text));
      }
      LOGGER.info("Note " + head.id() + " of " + username + ": base of device " + deviceId + " unknown, its text goes to a conflict copy");
      return conflict(store, deviceId, change, head, current, current, text, submitted);
    }
    Merge3.Result merge = Merge3.merge(ancestor, current, text);
    if (!merge.isClean()) {
      LOGGER.info("Note " + head.id() + " of " + username + ": " + merge.conflicts() + " conflict(s) with device " + deviceId);
      return conflict(store, deviceId, change, head, current, merge.preferringOurs(), merge.preferringTheirs(), submitted);
    }
    String merged = merge.preferringOurs();
    boolean wasMerged = !ancestor.equals(current);
    if (wasMerged) LOGGER.info("Note " + head.id() + " of " + username + ": merged a change from device " + deviceId);
    if (merged.equals(current) && !head.deleted()) {
      return new PushResult(change.changeId(), wasMerged ? PushStatus.MERGED : PushStatus.APPLIED, null, null, info(head),
          splice(submitted, current), null, null);
    }
    NoteMeta committed = commit(store, head, new NoteWrite(merged, false, change.changeId(), deviceId, submitted, head.conflictOf()));
    return new PushResult(change.changeId(), wasMerged ? PushStatus.MERGED : PushStatus.APPLIED, null, null, info(committed),
        splice(submitted, merged), null, null);
  }

  /** The original gets {@code original}, undeleted; a new copy gets {@code copy} and the change's ID. */
  private PushResult conflict(UserNoteStore store, String deviceId, NoteChange change, NoteMeta head, String current,
                              String original, String copy, String submitted) throws IOException {
    NoteMeta originalMeta = head;
    if (!original.equals(current) || head.deleted()) {
      originalMeta = commit(store, head, new NoteWrite(original, false, null, deviceId, null, head.conflictOf()));
    }
    String copyId = UUID.randomUUID().toString();
    NoteMeta copyMeta = commit(store, null,
        new NoteWrite(copy, false, change.changeId(), deviceId, submitted, new ConflictOf(head.id()), head.tags(), head.options()), copyId);
    return new PushResult(change.changeId(), PushStatus.CONFLICT, null, null, info(originalMeta), splice(submitted, original),
        info(copyMeta), splice(submitted, copy));
  }

  /**
   * What the change led to, as recorded: the revision it made (or, for a conflict, the copy it
   * made and the original's current state).
   */
  private static PushResult replay(UserNoteStore store, NoteChange change, ChangeRecord done) throws IOException {
    NoteMeta meta = store.get(done.noteId());
    Revision revision = store.readRevision(done.noteId(), done.revision());
    if (revision == null) {
      // Thinned out: long applied, its details are gone. The client gets the note as it is now
      // (a conflict copy it made reaches it as any other change).
      NoteMeta now = store.get(change.noteId());
      return new PushResult(change.changeId(), PushStatus.APPLIED, null, null, info(now), now.deleted() ? null
          : full(store.readContent(now.id())), null, null);
    }
    String submitted = revision.submittedContent() != null ? revision.submittedContent() : revision.content();
    NoteInfo info = info(meta, revision);
    if (done.noteId().equals(change.noteId())) {
      PushStatus status = revision.submittedContent() == null ? PushStatus.APPLIED : PushStatus.MERGED;
      return new PushResult(change.changeId(), status, null, null, info, splice(submitted, revision.content()), null, null);
    }
    NoteMeta original = store.get(change.noteId());
    String originalContent = store.readContent(original.id());
    return new PushResult(change.changeId(), PushStatus.CONFLICT, null, null, info(original), splice(submitted, originalContent),
        info, splice(submitted, revision.content()));
  }

  /** Commits the next revision of an existing note. */
  private static NoteMeta commit(UserNoteStore store, NoteMeta head, NoteWrite write) throws IOException {
    return commit(store, head, write, head.id());
  }

  /** Never stale: the caller holds the store's lock since it read {@code head}. */
  private static NoteMeta commit(UserNoteStore store, NoteMeta head, NoteWrite write, String noteId) throws IOException {
    try {
      return store.commit(noteId, head == null ? 0 : head.revision(), write);
    } catch (StaleRevisionException e) {
      throw new IllegalStateException("Note changed while its store was locked", e);
    }
  }

  private static PushResult applied(NoteChange change, NoteMeta meta, Splice content) {
    return new PushResult(change.changeId(), PushStatus.APPLIED, null, null, info(meta), content, null, null);
  }

  private static PushResult applied(String changeId, NoteMeta meta) {
    return new PushResult(changeId, PushStatus.APPLIED, null, null, info(meta), null, null, null);
  }

  private static PushResult rejected(NoteChange change, RejectReason reason, String message) {
    return rejected(change.changeId(), change.noteId(), reason, message);
  }

  private static PushResult rejected(String changeId, String noteId, RejectReason reason, String message) {
    LOGGER.warning("Change " + changeId + " to note " + noteId + " rejected (" + reason + "): " + message);
    return new PushResult(changeId, PushStatus.REJECTED, reason, message, null, null, null, null);
  }

  /** CRLF and lone CR become LF: a client on another platform mustn't turn every line into a change. */
  static String normalize(String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n');
  }

  /** Null when {@code from} is already {@code to}; the full text when {@code from} is unknown. */
  private static Splice splice(String from, String to) {
    if (from == null) return full(to);
    return from.equals(to) ? null : Splice.compute(from, to);
  }

  private static Splice smallest(String from, String to) {
    Splice splice = Splice.compute(from, to);
    return splice.middle().length() < to.length() ? splice : full(to);
  }

  private static Splice full(String text) {
    return new Splice(0, 0, text);
  }

  static NoteInfo info(NoteMeta meta) {
    return new NoteInfo(meta.id(), meta.revision(), meta.seq(), meta.createdAt(), meta.modifiedAt(), meta.deleted(), meta.title(),
        meta.size(), meta.contentHash(), meta.conflictOf(), meta.tags(), meta.options());
  }

  /** The note as it was at {@code revision}. */
  private static NoteInfo info(NoteMeta meta, Revision revision) {
    byte[] content = revision.content().getBytes(StandardCharsets.UTF_8);
    return new NoteInfo(meta.id(), revision.revision(), revision.seq(), meta.createdAt(), revision.at(), revision.deleted(),
        UserNoteStore.title(revision.content()), content.length, Hashes.sha256(content), meta.conflictOf(), meta.tags(), meta.options());
  }
}
