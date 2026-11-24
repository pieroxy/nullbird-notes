package net.pieroxy.notes.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.pieroxy.notes.sync.Merge3;
import net.pieroxy.notes.sync.Splice;
import net.pieroxy.notes.sync.TagEvent;
import net.pieroxy.notes.sync.TagOptions;
import net.pieroxy.notes.sync.TagOptionsChange;
import net.pieroxy.notes.utils.AtomicFiles;
import net.pieroxy.notes.utils.Hashes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One user's notes on disk:
 * <pre>
 * user.json                      {"lastSeq": N, "storeId": "uuid"}
 * notes/&lt;id&gt;/note.json           {@link NoteMeta}
 * notes/&lt;id&gt;/content.txt         the head revision's content, plain UTF-8
 * notes/&lt;id&gt;/revisions/NNNNNN.json.lz4   every {@link Revision}
 * notes/&lt;id&gt;/meta/NNNNNN.json  every {@link MetaRevision}
 * tag-order.json                 {@link StoredTagOrder}: absent until the user first reorders their tags
 * tag-events.json                the last {@link StoredTagEvent}s: tags renamed or removed from every note
 * tag-options.json               {@link StoredTagOptions}: absent until the user first sets a tag's options
 * purged.json                    the last {@link StoredPurge}s: deleted notes deleted for good, for the clients to forget
 * </pre>
 * The head revision's file holds its text; an older one's, a splice turning the next revision's
 * text back into its own (a reverse delta), except every {@link #FULL_REVISION_INTERVAL}th one
 * and those the splice wouldn't make much smaller. Reading a revision applies the splices from
 * the nearest complete one: recent revisions, the ones sync reads, are the cheapest. Old
 * revisions are thinned out (see {@link ThinningPolicy}): numbers of revisions gone are gaps.
 * Every note's note.json is loaded into memory when the store opens: listing and change queries
 * never touch the disk. Methods are synchronized: commits for a user are serialized.
 * <p>
 * A commit writes, each atomically, the revision, content.txt, note.json (the commit point) and
 * user.json, then turns the previous revision into a delta; a metadata commit, the metadata
 * revision, note.json and user.json. What an interruption leaves behind never needs a repair pass
 * on startup:
 * <ul>
 *   <li>a revision (or metadata revision) past note.json's is never read, and the next commit overwrites it;</li>
 *   <li>a content.txt not matching note.json's hash is rewritten from the head revision when read;</li>
 *   <li>a stale user.json is superseded by the notes' own seq on load;</li>
 *   <li>a previous revision not turned into a delta stays complete: only bigger;</li>
 *   <li>a note folder without note.json (an interrupted creation) is ignored;</li>
 *   <li>a tag missing from tag-order.json (a metadata commit interrupted before it) is added at
 *   its end on load.</li>
 * </ul>
 * Once there's a tag order, a metadata commit keeps it complete: a tag new to the user goes at its
 * end, and one no note has any more leaves it.
 */
public class UserNoteStore {
  private final static Logger LOGGER = Logger.getLogger(UserNoteStore.class.getName());
  /** Lowercase UUID: note IDs name folders. */
  public static final Pattern NOTE_ID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  static final int TITLE_MAX_LENGTH = 100;
  private static final Pattern REVISION_FILE_PATTERN = Pattern.compile("(\\d+)\\.json\\.lz4");
  /** Bounds the splices applied to read an old revision. */
  static final int FULL_REVISION_INTERVAL = 200;
  private final static Gson GSON = new Gson();
  private final static Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  /** The steps of a commit, in order. */
  enum CommitStep { REVISION, CONTENT, META, USER_STATE, PREVIOUS_AS_DELTA, THINNING_REWRITES }

  /** Visible for tests: throwing from it simulates a crash right after that step. */
  interface CommitHook {
    void after(CommitStep step) throws IOException;
  }

  private record UserState(long lastSeq, String storeId) {}

  /**
   * The order the user gave their tags.
   *
   * @param seq when it last changed, from the same sequence as the notes: change queries report it.
   */
  public record StoredTagOrder(int revision, long seq, List<String> tags) {}

  /** A {@link TagEvent}, and when it happened, from the same sequence as the notes. */
  public record StoredTagEvent(long seq, String from, String to) {
    public TagEvent event() {
      return new TagEvent(from, to);
    }
  }

  /**
   * Each tag's options, by tag, those of tags no note has any more included: adding the tag back
   * brings them back.
   *
   * @param seq when they last changed, from the same sequence as the notes: change queries report it.
   */
  public record StoredTagOptions(long seq, Map<String, TagOptions> tags) {}

  /** A deleted note deleted for good, and when, from the same sequence as the notes. */
  public record StoredPurge(long seq, String noteId) {}

  /** As {@link #MAX_TAG_EVENTS}: a device offline longer keeps the note in its trash, until it resyncs. */
  static final int MAX_PURGES = 5000;

  /** Enough for a device offline a long time, renames being rare: beyond, its old changes aren't rewritten. */
  static final int MAX_TAG_EVENTS = 1000;

  /** A tag order sent with another base revision than the current one. */
  public static class StaleTagOrderException extends Exception {
    public StaleTagOrderException(int expected, int actual) {
      super("The tag order is at revision " + actual + ", expected " + expected);
    }
  }

  /** Case-insensitive first, as the web app sorts tags. */
  private static final Comparator<String> ALPHABETICAL = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

  /**
   * A revision file: {@code content}, or {@code delta} applied to revision {@code deltaBase}'s
   * text, which must give {@code contentHash}. The submitted text is {@code submittedDelta} applied
   * to the revision's text; format 1 files hold it whole, in {@code submittedContent}.
   */
  private record StoredRevision(int revision, long seq, long at, String changeId, String deviceId, boolean deleted, String content,
                                Integer deltaBase, Splice delta, String contentHash, String submittedContent, Splice submittedDelta) {
    Revision toRevision(String text) {
      String submitted = submittedDelta != null ? submittedDelta.apply(text) : submittedContent;
      return new Revision(revision, seq, at, changeId, deviceId, deleted, text, submitted);
    }
  }

  private final Path root;
  private final Path notesDir;
  private final Clock clock;
  private final Map<String, NoteMeta> index = new HashMap<>();
  // Only each note's last change survives a restart (see NoteMeta#lastChangeId).
  private final Map<String, ChangeRecord> changes = new HashMap<>();
  // Metadata change ID -> note ID; likewise, only each note's last one survives a restart.
  private final Map<String, String> metaChanges = new HashMap<>();
  private long lastSeq;
  private String storeId;
  /** Null until the user first reorders their tags. */
  private StoredTagOrder tagOrder;
  private final List<Runnable> seqWaiters = new ArrayList<>();
  private List<StoredTagEvent> tagEvents = List.of();
  private StoredTagOptions tagOptions;
  private List<StoredPurge> purges = List.of();
  CommitHook commitHook = step -> {};
  /** Visible for tests. */
  int fullRevisionInterval = FULL_REVISION_INTERVAL;

  private UserNoteStore(Path root, Clock clock) {
    this.root = root;
    this.notesDir = root.resolve("notes");
    this.clock = clock;
  }

  public static UserNoteStore open(Path root, Clock clock) throws IOException {
    UserNoteStore store = new UserNoteStore(root, clock);
    store.load();
    return store;
  }

  public synchronized List<NoteMeta> list() {
    return new ArrayList<>(index.values());
  }

  /** @return the notes changed after {@code seq}, in seq order. */
  public synchronized List<NoteMeta> changesSince(long seq) {
    return index.values().stream()
        .filter(meta -> meta.seq() > seq)
        .sorted(Comparator.comparingLong(NoteMeta::seq))
        .collect(Collectors.toList());
  }

  public synchronized long getLastSeq() {
    return lastSeq;
  }

  /**
   * Random, created with user.json and never changed: a client whose cursor came with another
   * storeId must resync from scratch (the data was restored from a backup, or user.json was lost).
   */
  public synchronized String getStoreId() {
    return storeId;
  }

  /**
   * Runs {@code callback} once, as soon as {@link #getLastSeq()} differs from {@code seq}: right
   * away if it already does, otherwise on the next commit. It runs while this store is locked: it
   * must return quickly, and hand any real work to another thread.
   *
   * @return cancels the wait; a no-op once {@code callback} has run.
   */
  public synchronized Runnable awaitSeqChange(long seq, Runnable callback) {
    if (lastSeq != seq) {
      callback.run();
      return () -> {};
    }
    // A wrapper per call: removing by identity, even if the same callback waits twice.
    Runnable waiter = callback::run;
    seqWaiters.add(waiter);
    return () -> {
      synchronized (this) {
        seqWaiters.remove(waiter);
      }
    };
  }

  /** @return null if there's no such note. */
  public synchronized NoteMeta get(String noteId) {
    return index.get(noteId);
  }

  /** @return where the push with this ID landed, null if unknown. */
  public synchronized ChangeRecord findChange(String changeId) {
    return changeId == null ? null : changes.get(changeId);
  }

  /** @return the note the metadata change with this ID was applied to, null if unknown. */
  public synchronized String findMetaChange(String changeId) {
    return changeId == null ? null : metaChanges.get(changeId);
  }

  /** @return the head revision's content, null if there's no such note. */
  public synchronized String readContent(String noteId) throws IOException {
    NoteMeta meta = index.get(noteId);
    if (meta == null) return null;
    Path contentFile = noteDir(noteId).resolve("content.txt");
    if (Files.isRegularFile(contentFile)) {
      byte[] bytes = Files.readAllBytes(contentFile);
      if (Hashes.sha256(bytes).equals(meta.contentHash())) return new String(bytes, StandardCharsets.UTF_8);
    }
    LOGGER.warning("Note " + noteId + " of " + root.getFileName() + ": content.txt doesn't match revision " + meta.revision()
        + ", restoring it from that revision");
    String content = readRevisionFile(noteId, meta.revision()).content();
    AtomicFiles.write(contentFile, content.getBytes(StandardCharsets.UTF_8));
    return content;
  }

  /** @return null if there's no such note or revision (thinned out, see {@link #thin}). */
  public synchronized Revision readRevision(String noteId, int revision) throws IOException {
    NoteMeta meta = index.get(noteId);
    if (meta == null || revision < 1 || revision > meta.revision() || !Files.exists(revisionFile(noteId, revision))) return null;
    return readRevisionFile(noteId, revision);
  }

  /**
   * Thins out every note's old revisions (see {@link ThinningPolicy}), one note at a time: commits
   * to other notes go on meanwhile.
   *
   * @return how many revisions were deleted.
   */
  public int thin(long now) throws IOException {
    List<String> noteIds;
    synchronized (this) {
      noteIds = new ArrayList<>(index.keySet());
    }
    int deleted = 0;
    for (String noteId : noteIds) deleted += thinNote(noteId, now);
    return deleted;
  }

  /**
   * Kept revisions whose delta applies to one that goes are first rewritten as deltas of the next
   * kept one, each atomically; only then are the others deleted. Interrupted, it leaves complete
   * chains, plus files of revisions that should have gone, deleted next time.
   */
  synchronized int thinNote(String noteId, long now) throws IOException {
    NoteMeta meta = index.get(noteId);
    if (meta == null) return 0;
    List<StoredRevision> stored = new ArrayList<>();
    for (int revision : existingRevisions(noteId, meta.revision())) stored.add(readStored(noteId, revision));
    if (stored.isEmpty()) return 0;
    Set<Integer> keep = ThinningPolicy.keep(stored.stream().map(s -> new ThinningPolicy.Entry(s.revision(), s.at(), s.deleted())).toList(), now);
    int head = stored.get(stored.size() - 1).revision();
    boolean submittedToDrop = stored.stream().anyMatch(s -> s.revision() != head && hasSubmitted(s) && ThinningPolicy.dropsSubmitted(s.at(), now));
    if (keep.size() == stored.size() && !submittedToDrop) return 0;

    // From the head down, each text from the one above: one splice per revision.
    int above = 0;
    String aboveText = null;
    int nextKept = 0;
    String nextKeptText = null;
    for (int i = stored.size() - 1; i >= 0; i--) {
      StoredRevision revision = stored.get(i);
      String text;
      if (revision.content() != null) text = revision.content();
      else if (revision.deltaBase() != null && revision.deltaBase() == above) text = revision.delta().apply(aboveText);
      else text = readRevisionFile(noteId, revision.revision()).content();
      if (keep.contains(revision.revision())) {
        if (revision.revision() != head) rewriteIfNeeded(noteId, revision, text, keep, nextKept, nextKeptText, now);
        nextKept = revision.revision();
        nextKeptText = text;
      }
      above = revision.revision();
      aboveText = text;
    }
    commitHook.after(CommitStep.THINNING_REWRITES);
    int deleted = 0;
    for (StoredRevision revision : stored) {
      if (!keep.contains(revision.revision())) {
        Files.deleteIfExists(revisionFile(noteId, revision.revision()));
        deleted++;
      }
    }
    return deleted;
  }

  /** A kept revision: rewritten if its delta's base goes, or to drop an old submitted text. */
  private void rewriteIfNeeded(String noteId, StoredRevision revision, String text, Set<Integer> keep, int nextKept, String nextKeptText,
                               long now) throws IOException {
    boolean baseGoes = revision.content() == null && !keep.contains(revision.deltaBase());
    boolean dropSubmitted = hasSubmitted(revision) && ThinningPolicy.dropsSubmitted(revision.at(), now);
    if (!baseGoes && !dropSubmitted) return;
    Splice submitted = dropSubmitted ? null : revision.submittedDelta() != null ? revision.submittedDelta()
        : revision.submittedContent() != null ? Splice.compute(text, revision.submittedContent()) : null;
    StoredRevision rewritten;
    Splice delta = revision.content() == null ? Splice.compute(nextKeptText, text) : null;
    if (delta == null || delta.middle().length() > text.length() / 2) {
      rewritten = new StoredRevision(revision.revision(), revision.seq(), revision.at(), revision.changeId(), revision.deviceId(),
          revision.deleted(), text, null, null, null, null, submitted);
    } else {
      rewritten = new StoredRevision(revision.revision(), revision.seq(), revision.at(), revision.changeId(), revision.deviceId(),
          revision.deleted(), null, nextKept, delta, Hashes.sha256(text), null, submitted);
    }
    writeRevisionFile(noteId, rewritten);
  }

  private static boolean hasSubmitted(StoredRevision revision) {
    return revision.submittedDelta() != null || revision.submittedContent() != null;
  }

  /** In revision order, up to {@code head}: past it are leftovers of interrupted commits. */
  private List<Integer> existingRevisions(String noteId, int head) throws IOException {
    Path dir = noteDir(noteId).resolve("revisions");
    List<Integer> revisions = new ArrayList<>();
    if (!Files.isDirectory(dir)) return revisions;
    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
      for (Path file : files) {
        Matcher matcher = REVISION_FILE_PATTERN.matcher(file.getFileName().toString());
        if (!matcher.matches()) continue;
        int revision = Integer.parseInt(matcher.group(1));
        if (revision >= 1 && revision <= head) revisions.add(revision);
      }
    }
    revisions.sort(null);
    return revisions;
  }

  /**
   * Writes {@code write} as the note's next revision, creating the note if {@code expectedRevision}
   * is 0. Once note.json is written the change is committed, even if writing user.json then fails.
   *
   * @throws StaleRevisionException if the note's head isn't {@code expectedRevision}; nothing is written then.
   * @throws IOException if the change couldn't be committed.
   */
  public synchronized NoteMeta commit(String noteId, int expectedRevision, NoteWrite write) throws IOException, StaleRevisionException {
    return commit(noteId, expectedRevision, write, null, null);
  }

  /**
   * Creates a note brought in by an import, as its revision 1, with the dates it had where it comes
   * from rather than now's: the note list keeps its order.
   *
   * @throws StaleRevisionException if there's already a note {@code noteId}; nothing is written then.
   */
  public synchronized NoteMeta importNote(String noteId, NoteWrite write, long createdAt, long modifiedAt)
      throws IOException, StaleRevisionException {
    return commit(noteId, 0, write, createdAt, modifiedAt);
  }

  private NoteMeta commit(String noteId, int expectedRevision, NoteWrite write, Long createdAt, Long at)
      throws IOException, StaleRevisionException {
    if (noteId == null || !NOTE_ID_PATTERN.matcher(noteId).matches()) {
      throw new IllegalArgumentException("Invalid note ID: " + noteId);
    }
    if (write.content() == null) throw new IllegalArgumentException("No content for note " + noteId);
    NoteMeta current = index.get(noteId);
    int head = current == null ? 0 : current.revision();
    if (head != expectedRevision) throw new StaleRevisionException(noteId, expectedRevision, head);

    long now = at != null ? at : clock.millis();
    long seq = lastSeq + 1;
    int revision = head + 1;
    byte[] content = write.content().getBytes(StandardCharsets.UTF_8);
    Path noteDir = noteDir(noteId);

    Path revisionFile = revisionFile(noteId, revision);
    if (Files.exists(revisionFile)) {
      LOGGER.info("Note " + noteId + " of " + root.getFileName() + ": overwriting revision " + revision + " left by an interrupted commit");
    }
    Splice submitted = write.submittedContent() == null || write.content().equals(write.submittedContent()) ? null
        : Splice.compute(write.content(), write.submittedContent());
    writeRevisionFile(noteId, new StoredRevision(revision, seq, now, write.changeId(), write.deviceId(), write.deleted(), write.content(),
        null, null, null, null, submitted));
    commitHook.after(CommitStep.REVISION);
    AtomicFiles.write(noteDir.resolve("content.txt"), content);
    commitHook.after(CommitStep.CONTENT);
    NoteMeta meta = current == null
        ? new NoteMeta(noteId, revision, seq, createdAt != null ? createdAt : now, now, write.deleted(), title(write.content()),
            content.length, Hashes.sha256(content),
            write.changeId(), write.tags(), write.options(), 0, null)
        : new NoteMeta(noteId, revision, seq, current.createdAt(), now, write.deleted(), title(write.content()), content.length,
            Hashes.sha256(content), write.changeId(), current.tags(), current.options(), current.metaRevision(),
            current.lastMetaChangeId());
    writeMeta(meta);
    if (write.changeId() != null) changes.put(write.changeId(), new ChangeRecord(noteId, revision));
    // A note created with tags: they may be new to the user.
    if (current == null && !meta.tags().isEmpty()) keepTagOrderComplete(meta.tags(), null, seq);
    afterMetaWritten();
    storeAsDelta(noteId, head, write.content());
    commitHook.after(CommitStep.PREVIOUS_AS_DELTA);
    return meta;
  }

  /** Turns {@code revision}, until now the head, into a splice of the new head's text. Failing leaves it complete. */
  private void storeAsDelta(String noteId, int revision, String nextText) {
    if (revision < 1 || revision % fullRevisionInterval == 0) return;
    try {
      StoredRevision stored = readStored(noteId, revision);
      if (stored.content() == null) return;
      String text = stored.content();
      Splice delta = Splice.compute(nextText, text);
      if (delta.middle().length() > text.length() / 2) return;
      Splice submitted = stored.submittedDelta() != null ? stored.submittedDelta()
          : stored.submittedContent() != null ? Splice.compute(text, stored.submittedContent()) : null;
      writeRevisionFile(noteId, new StoredRevision(stored.revision(), stored.seq(), stored.at(), stored.changeId(), stored.deviceId(),
          stored.deleted(), null, revision + 1, delta, Hashes.sha256(text), null, submitted));
    } catch (IOException | RuntimeException e) {
      LOGGER.log(Level.WARNING, "Note " + noteId + " of " + root.getFileName() + ": revision " + revision + " stays complete", e);
    }
  }

  /**
   * Applies {@code write}'s operations to the note's tags and options, as a new metadata revision.
   * The note's revision and modifiedAt are left alone: only its seq changes.
   *
   * @return null if there's no such note.
   * @throws IOException if the change couldn't be committed.
   */
  public synchronized NoteMeta commitMeta(String noteId, MetaWrite write) throws IOException {
    NoteMeta current = index.get(noteId);
    if (current == null) return null;
    List<String> tags = tagsAfter(current, write);
    Map<String, String> options = optionsAfter(current, write);
    long now = clock.millis();
    long seq = lastSeq + 1;
    int metaRevision = current.metaRevision() + 1;
    MetaRevision rev = new MetaRevision(metaRevision, seq, now, write.changeId(), write.deviceId(), write.addTags(), write.removeTags(),
        write.setOptions(), write.unsetOptions());
    AtomicFiles.write(metaRevisionFile(noteId, metaRevision), GSON.toJson(rev).getBytes(StandardCharsets.UTF_8));
    commitHook.after(CommitStep.REVISION);
    NoteMeta meta = new NoteMeta(noteId, current.revision(), seq, current.createdAt(), current.modifiedAt(), current.deleted(),
        current.title(), current.size(), current.contentHash(), current.lastChangeId(), tags, options,
        metaRevision, write.changeId());
    writeMeta(meta);
    if (write.changeId() != null) metaChanges.put(write.changeId(), noteId);
    keepTagOrderComplete(tags, write.removeTags(), seq);
    afterMetaWritten();
    return meta;
  }

  /** @return the user's tag order, null if they never reordered their tags. */
  public synchronized StoredTagOrder getTagOrder() {
    return tagOrder;
  }

  /**
   * Replaces the tag order at {@code baseRevision} (0 when there's none yet) with {@code tags},
   * duplicates dropped, tags in use but missing added at the end alphabetically. Left as is if
   * that's already the order.
   *
   * @throws StaleTagOrderException if the order isn't at {@code baseRevision} any more.
   */
  public synchronized StoredTagOrder setTagOrder(int baseRevision, List<String> tags) throws IOException, StaleTagOrderException {
    int current = tagOrder == null ? 0 : tagOrder.revision();
    if (baseRevision != current) throw new StaleTagOrderException(baseRevision, current);
    LinkedHashSet<String> order = new LinkedHashSet<>(tags);
    usedTags().stream().filter(tag -> !order.contains(tag)).sorted(ALPHABETICAL).forEach(order::add);
    List<String> next = List.copyOf(order);
    if (tagOrder != null && next.equals(tagOrder.tags())) return tagOrder;
    long seq = lastSeq + 1;
    writeTagOrder(new StoredTagOrder(current + 1, seq, next));
    lastSeq = seq;
    wakeSeqWaiters();
    try {
      writeUserState();
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, "Could not write user.json of " + root.getFileName() + " (recovered from tag-order.json on next load)", e);
    }
    return tagOrder;
  }

  /**
   * After a metadata commit leaving a note with {@code tags}: a tag new to the user goes at the end
   * of the order, and a removed one no note has any more leaves it. Nothing while there's no order.
   */
  private void keepTagOrderComplete(List<String> tags, List<String> removed, long seq) throws IOException {
    if (tagOrder == null) return;
    LinkedHashSet<String> order = new LinkedHashSet<>(tagOrder.tags());
    boolean changed = false;
    for (String tag : tags) changed |= order.add(tag);
    if (removed != null) {
      for (String tag : removed) {
        if (order.contains(tag) && index.values().stream().noneMatch(note -> note.tags().contains(tag))) changed |= order.remove(tag);
      }
    }
    if (changed) writeTagOrder(new StoredTagOrder(tagOrder.revision() + 1, seq, List.copyOf(order)));
  }

  /**
   * Records that {@code from} was renamed to {@code to} (null: removed) on every note, once their
   * metadata commits are done. The tag order, if there's one, is {@code before} (as it was before
   * those commits) with {@code from} in place of {@code to}; merged, {@code to} keeps its own place.
   */
  public synchronized void recordTagEvent(String from, String to, StoredTagOrder before) throws IOException {
    // Its options go along: merged, the target keeps its own if it has some. Before the event, which is the last word.
    if (tagOptions != null && tagOptions.tags().containsKey(from)) {
      Map<String, TagOptions> tags = new TreeMap<>(tagOptions.tags());
      TagOptions moved = tags.remove(from);
      if (to != null) tags.putIfAbsent(to, moved);
      writeTagOptions(tags);
    }
    long seq = lastSeq + 1;
    if (before != null) {
      List<String> order = new ArrayList<>();
      for (String tag : before.tags()) {
        if (!tag.equals(from)) order.add(tag);
        else if (to != null && !before.tags().contains(to)) order.add(to);
      }
      LinkedHashSet<String> complete = new LinkedHashSet<>(order);
      Set<String> used = usedTags();
      complete.removeIf(tag -> !used.contains(tag));
      used.stream().filter(tag -> !complete.contains(tag)).sorted(ALPHABETICAL).forEach(complete::add);
      int revision = Math.max(before.revision(), tagOrder == null ? 0 : tagOrder.revision()) + 1;
      writeTagOrder(new StoredTagOrder(revision, seq, List.copyOf(complete)));
    }
    List<StoredTagEvent> events = new ArrayList<>(tagEvents);
    events.add(new StoredTagEvent(seq, from, to));
    if (events.size() > MAX_TAG_EVENTS) events = events.subList(events.size() - MAX_TAG_EVENTS, events.size());
    AtomicFiles.write(root.resolve("tag-events.json"), PRETTY_GSON.toJson(events).getBytes(StandardCharsets.UTF_8));
    tagEvents = List.copyOf(events);
    lastSeq = seq;
    wakeSeqWaiters();
    try {
      writeUserState();
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, "Could not write user.json of " + root.getFileName() + " (recovered from tag-events.json on next load)", e);
    }
  }

  /** @return the tag events after {@code seq}, oldest first. */
  public synchronized List<StoredTagEvent> tagEventsAfter(long seq) {
    return tagEvents.stream().filter(event -> event.seq() > seq).toList();
  }

  /** Every tag of every note, deleted ones included: they come back with their tags if restored. */
  private Set<String> usedTags() {
    Set<String> used = new HashSet<>();
    for (NoteMeta note : index.values()) used.addAll(note.tags());
    return used;
  }

  private void writeTagOrder(StoredTagOrder order) throws IOException {
    AtomicFiles.write(root.resolve("tag-order.json"), PRETTY_GSON.toJson(order).getBytes(StandardCharsets.UTF_8));
    tagOrder = order;
  }

  /** The note's tags once {@code write} is applied: removals first, additions at the end. */
  public static List<String> tagsAfter(NoteMeta note, MetaWrite write) {
    Set<String> tags = new LinkedHashSet<>(note.tags());
    if (write.removeTags() != null) write.removeTags().forEach(tags::remove);
    if (write.addTags() != null) tags.addAll(write.addTags());
    return List.copyOf(tags);
  }

  /** The note's options once {@code write} is applied: unset first. */
  public static Map<String, String> optionsAfter(NoteMeta note, MetaWrite write) {
    Map<String, String> options = new TreeMap<>(note.options());
    if (write.unsetOptions() != null) write.unsetOptions().forEach(options::remove);
    if (write.setOptions() != null) options.putAll(write.setOptions());
    return options;
  }

  /** @return null if there's no such note or metadata revision. */
  public synchronized MetaRevision readMetaRevision(String noteId, int metaRevision) throws IOException {
    NoteMeta meta = index.get(noteId);
    if (meta == null || metaRevision < 1 || metaRevision > meta.metaRevision()) return null;
    return GSON.fromJson(Files.readString(metaRevisionFile(noteId, metaRevision), StandardCharsets.UTF_8), MetaRevision.class);
  }

  /** The commit point: note.json, then the index and seq. */
  private void writeMeta(NoteMeta meta) throws IOException {
    AtomicFiles.write(noteDir(meta.id()).resolve("note.json"), PRETTY_GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
    index.put(meta.id(), meta);
    lastSeq = meta.seq();
  }

  private void afterMetaWritten() throws IOException {
    wakeSeqWaiters();
    commitHook.after(CommitStep.META);
    try {
      writeUserState();
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, "Could not write user.json of " + root.getFileName() + " (recovered from the notes on next load)", e);
    }
    commitHook.after(CommitStep.USER_STATE);
  }

  private void wakeSeqWaiters() {
    List<Runnable> waiters = new ArrayList<>(seqWaiters);
    seqWaiters.clear();
    for (Runnable waiter : waiters) {
      try {
        waiter.run();
      } catch (RuntimeException e) {
        LOGGER.log(Level.SEVERE, "A seq waiter of " + root.getFileName() + " failed", e);
      }
    }
  }

  /**
   * The first non-blank line, trimmed, at most {@link #TITLE_MAX_LENGTH} code points; a merge's mark
   * (see Merge3) isn't one.
   */
  public static String title(String content) {
    for (String line : content.split("\n", -1)) {
      String trimmed = line.strip();
      if (trimmed.isEmpty() || line.startsWith(Merge3.TAG)) continue;
      if (trimmed.codePointCount(0, trimmed.length()) <= TITLE_MAX_LENGTH) return trimmed;
      return trimmed.substring(0, trimmed.offsetByCodePoints(0, TITLE_MAX_LENGTH));
    }
    return "";
  }

  /** A missing storeId is created and saved right away: it must not change once a client has seen it. */
  private void load() throws IOException {
    Path userState = root.resolve("user.json");
    if (Files.isRegularFile(userState)) {
      UserState state = GSON.fromJson(Files.readString(userState, StandardCharsets.UTF_8), UserState.class);
      if (state != null) {
        lastSeq = state.lastSeq();
        storeId = state.storeId();
      }
    }
    loadNotes();
    loadTagOrder();
    loadTagEvents();
    loadTagOptions();
    loadPurges();
    if (storeId == null) {
      storeId = UUID.randomUUID().toString();
      writeUserState();
      LOGGER.info("New store ID for " + root.getFileName() + ": its clients will resync from scratch");
    }
  }

  /** A tag in use but missing (a metadata commit interrupted before the order was written) is added at the end. */
  private void loadTagOrder() throws IOException {
    Path file = root.resolve("tag-order.json");
    if (!Files.isRegularFile(file)) return;
    StoredTagOrder stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), StoredTagOrder.class);
    if (stored == null || stored.tags() == null) return;
    tagOrder = stored;
    lastSeq = Math.max(lastSeq, stored.seq());
    LinkedHashSet<String> order = new LinkedHashSet<>(stored.tags());
    List<String> missing = usedTags().stream().filter(tag -> !order.contains(tag)).sorted(ALPHABETICAL).toList();
    if (missing.isEmpty()) return;
    order.addAll(missing);
    long seq = lastSeq + 1;
    writeTagOrder(new StoredTagOrder(stored.revision() + 1, seq, List.copyOf(order)));
    lastSeq = seq;
    // Without a storeId yet, load() writes user.json next.
    if (storeId != null) writeUserState();
    LOGGER.warning("Tag order of " + root.getFileName() + " completed with " + missing.size() + " tag(s) missing from it");
  }

  private void loadTagEvents() throws IOException {
    Path file = root.resolve("tag-events.json");
    if (!Files.isRegularFile(file)) return;
    StoredTagEvent[] stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), StoredTagEvent[].class);
    if (stored == null) return;
    tagEvents = List.of(stored);
    for (StoredTagEvent event : tagEvents) lastSeq = Math.max(lastSeq, event.seq());
  }

  private void loadTagOptions() throws IOException {
    Path file = root.resolve("tag-options.json");
    if (!Files.isRegularFile(file)) return;
    StoredTagOptions stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), StoredTagOptions.class);
    if (stored == null || stored.tags() == null) return;
    tagOptions = stored;
    lastSeq = Math.max(lastSeq, stored.seq());
  }

  private void loadPurges() throws IOException {
    Path file = root.resolve("purged.json");
    if (!Files.isRegularFile(file)) return;
    StoredPurge[] stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), StoredPurge[].class);
    if (stored == null) return;
    purges = List.of(stored);
    for (StoredPurge purge : purges) lastSeq = Math.max(lastSeq, purge.seq());
  }

  /** @return the notes deleted for good after {@code seq}, oldest first. */
  public synchronized List<StoredPurge> purgesAfter(long seq) {
    return purges.stream().filter(purge -> purge.seq() > seq).toList();
  }

  /**
   * Deletes for good those of {@code noteIds} that are deleted, with every revision: the others are
   * left alone. Recorded first, then the folders go: interrupted, a folder left is removed again on
   * the next purge of it, or stays a deleted note.
   *
   * @return the IDs of the notes deleted for good.
   */
  public synchronized List<String> purge(List<String> noteIds) throws IOException {
    List<String> gone = noteIds.stream().distinct().filter(id -> index.containsKey(id) && index.get(id).deleted()).toList();
    if (gone.isEmpty()) return gone;
    long seq = lastSeq + 1;
    List<StoredPurge> next = new ArrayList<>(purges);
    for (String id : gone) next.add(new StoredPurge(seq, id));
    if (next.size() > MAX_PURGES) next = next.subList(next.size() - MAX_PURGES, next.size());
    AtomicFiles.write(root.resolve("purged.json"), PRETTY_GSON.toJson(next).getBytes(StandardCharsets.UTF_8));
    purges = List.copyOf(next);
    for (String id : gone) {
      // Out of note.json first: a folder without it is ignored on load.
      Files.deleteIfExists(noteDir(id).resolve("note.json"));
      index.remove(id);
      changes.values().removeIf(record -> record.noteId().equals(id));
      metaChanges.values().removeIf(id::equals);
      try (var paths = Files.walk(noteDir(id))) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
      } catch (IOException e) {
        LOGGER.log(Level.WARNING, "Note " + id + " of " + root.getFileName() + " purged, its folder not removed entirely", e);
      }
    }
    lastSeq = seq;
    wakeSeqWaiters();
    try {
      writeUserState();
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, "Could not write user.json of " + root.getFileName() + " (recovered from purged.json on next load)", e);
    }
    return gone;
  }

  /** @return each tag's options; null if the user never set any. */
  public synchronized StoredTagOptions getTagOptions() {
    return tagOptions;
  }

  /**
   * Replaces the options of each tag of {@code changes}, in order: an empty one removes them.
   * Nothing is written if nothing changes.
   */
  public synchronized StoredTagOptions setTagOptions(List<TagOptionsChange> changes) throws IOException {
    Map<String, TagOptions> tags = new TreeMap<>(tagOptions == null ? Map.of() : tagOptions.tags());
    for (TagOptionsChange change : changes) {
      if (change.options() == null || change.options().isEmpty()) tags.remove(change.tag());
      else tags.put(change.tag(), change.options().normalized());
    }
    if (tags.equals(tagOptions == null ? Map.of() : tagOptions.tags())) return tagOptions;
    writeTagOptions(tags);
    return tagOptions;
  }

  /** At a new seq, the user state after. */
  private void writeTagOptions(Map<String, TagOptions> tags) throws IOException {
    long seq = lastSeq + 1;
    StoredTagOptions next = new StoredTagOptions(seq, Collections.unmodifiableMap(new TreeMap<>(tags)));
    AtomicFiles.write(root.resolve("tag-options.json"), PRETTY_GSON.toJson(next).getBytes(StandardCharsets.UTF_8));
    tagOptions = next;
    lastSeq = seq;
    wakeSeqWaiters();
    try {
      writeUserState();
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, "Could not write user.json of " + root.getFileName() + " (recovered from tag-options.json on next load)", e);
    }
  }

  private void writeUserState() throws IOException {
    AtomicFiles.write(root.resolve("user.json"), GSON.toJson(new UserState(lastSeq, storeId)).getBytes(StandardCharsets.UTF_8));
  }

  private void loadNotes() throws IOException {
    if (!Files.isDirectory(notesDir)) return;
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(notesDir, Files::isDirectory)) {
      for (Path dir : dirs) {
        String noteId = dir.getFileName().toString();
        if (!NOTE_ID_PATTERN.matcher(noteId).matches()) {
          LOGGER.warning("Ignoring " + dir + ": not a note ID");
          continue;
        }
        Path metaFile = dir.resolve("note.json");
        if (!Files.isRegularFile(metaFile)) {
          LOGGER.warning("Ignoring " + dir + ": no note.json (a creation that was interrupted)");
          continue;
        }
        NoteMeta meta = GSON.fromJson(Files.readString(metaFile, StandardCharsets.UTF_8), NoteMeta.class);
        index.put(noteId, meta);
        lastSeq = Math.max(lastSeq, meta.seq());
        if (meta.lastChangeId() != null) changes.put(meta.lastChangeId(), new ChangeRecord(noteId, meta.revision()));
        if (meta.lastMetaChangeId() != null) metaChanges.put(meta.lastMetaChangeId(), noteId);
      }
    }
  }

  private Path noteDir(String noteId) {
    return notesDir.resolve(noteId);
  }

  private Path revisionFile(String noteId, int revision) {
    return noteDir(noteId).resolve("revisions").resolve(String.format("%06d.json.lz4", revision));
  }

  private Path metaRevisionFile(String noteId, int metaRevision) {
    return noteDir(noteId).resolve("meta").resolve(String.format("%06d.json", metaRevision));
  }

  /** Follows the deltas up to a complete revision, then applies them back down. */
  private Revision readRevisionFile(String noteId, int revision) throws IOException {
    List<StoredRevision> deltas = new ArrayList<>();
    StoredRevision stored = readStored(noteId, revision);
    while (stored.content() == null) {
      if (stored.delta() == null || stored.deltaBase() == null || stored.deltaBase() <= stored.revision()) {
        throw new IOException("Note " + noteId + " of " + root.getFileName() + ": revision " + stored.revision() + " is corrupt");
      }
      deltas.add(stored);
      stored = readStored(noteId, stored.deltaBase());
    }
    String text = stored.content();
    for (int i = deltas.size() - 1; i >= 0; i--) text = deltas.get(i).delta().apply(text);
    StoredRevision requested = deltas.isEmpty() ? stored : deltas.get(0);
    if (requested.contentHash() != null && !Hashes.sha256(text).equals(requested.contentHash())) {
      throw new IOException("Note " + noteId + " of " + root.getFileName() + ": revision " + revision + " doesn't match its hash");
    }
    return requested.toRevision(text);
  }

  private StoredRevision readStored(String noteId, int revision) throws IOException {
    try (InputStream in = new LZ4FrameInputStream(Files.newInputStream(revisionFile(noteId, revision)))) {
      return GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), StoredRevision.class);
    }
  }

  private void writeRevisionFile(String noteId, StoredRevision stored) throws IOException {
    AtomicFiles.write(revisionFile(noteId, stored.revision()), lz4(GSON.toJson(stored).getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] lz4(byte[] data) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (OutputStream out = new LZ4FrameOutputStream(bytes)) {
      out.write(data);
    }
    return bytes.toByteArray();
  }
}
