package net.pieroxy.notes.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.pieroxy.notes.sync.Splice;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
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
 * </pre>
 * The head revision's file holds its text; an older one's, a splice turning the next revision's
 * text back into its own (a reverse delta), except every {@link #FULL_REVISION_INTERVAL}th one
 * and those the splice wouldn't make much smaller. Reading a revision applies the splices from
 * the nearest complete one: recent revisions, the ones sync reads, are the cheapest.
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
 *   <li>a note folder without note.json (an interrupted creation) is ignored.</li>
 * </ul>
 */
public class UserNoteStore {
  private final static Logger LOGGER = Logger.getLogger(UserNoteStore.class.getName());
  /** Lowercase UUID: note IDs name folders. */
  public static final Pattern NOTE_ID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  static final int TITLE_MAX_LENGTH = 100;
  /** Bounds the splices applied to read an old revision. */
  static final int FULL_REVISION_INTERVAL = 200;
  private final static Gson GSON = new Gson();
  private final static Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  /** The steps of a commit, in order. */
  enum CommitStep { REVISION, CONTENT, META, USER_STATE, PREVIOUS_AS_DELTA }

  /** Visible for tests: throwing from it simulates a crash right after that step. */
  interface CommitHook {
    void after(CommitStep step) throws IOException;
  }

  private record UserState(long lastSeq, String storeId) {}

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
  private final List<Runnable> seqWaiters = new ArrayList<>();
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

  /** @return null if there's no such note or revision. */
  public synchronized Revision readRevision(String noteId, int revision) throws IOException {
    NoteMeta meta = index.get(noteId);
    if (meta == null || revision < 1 || revision > meta.revision()) return null;
    return readRevisionFile(noteId, revision);
  }

  /**
   * Writes {@code write} as the note's next revision, creating the note if {@code expectedRevision}
   * is 0. Once note.json is written the change is committed, even if writing user.json then fails.
   *
   * @throws StaleRevisionException if the note's head isn't {@code expectedRevision}; nothing is written then.
   * @throws IOException if the change couldn't be committed.
   */
  public synchronized NoteMeta commit(String noteId, int expectedRevision, NoteWrite write) throws IOException, StaleRevisionException {
    if (noteId == null || !NOTE_ID_PATTERN.matcher(noteId).matches()) {
      throw new IllegalArgumentException("Invalid note ID: " + noteId);
    }
    if (write.content() == null) throw new IllegalArgumentException("No content for note " + noteId);
    NoteMeta current = index.get(noteId);
    int head = current == null ? 0 : current.revision();
    if (head != expectedRevision) throw new StaleRevisionException(noteId, expectedRevision, head);

    long now = clock.millis();
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
        ? new NoteMeta(noteId, revision, seq, now, now, write.deleted(), title(write.content()), content.length, Hashes.sha256(content),
            write.changeId(), write.conflictOf(), write.tags(), write.options(), 0, null)
        : new NoteMeta(noteId, revision, seq, current.createdAt(), now, write.deleted(), title(write.content()), content.length,
            Hashes.sha256(content), write.changeId(), write.conflictOf(), current.tags(), current.options(), current.metaRevision(),
            current.lastMetaChangeId());
    writeMeta(meta);
    if (write.changeId() != null) changes.put(write.changeId(), new ChangeRecord(noteId, revision));
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
        current.title(), current.size(), current.contentHash(), current.lastChangeId(), current.conflictOf(), tags, options,
        metaRevision, write.changeId());
    writeMeta(meta);
    if (write.changeId() != null) metaChanges.put(write.changeId(), noteId);
    afterMetaWritten();
    return meta;
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

  /** The first non-blank line, trimmed, at most {@link #TITLE_MAX_LENGTH} code points. */
  public static String title(String content) {
    for (String line : content.split("\n", -1)) {
      String trimmed = line.strip();
      if (trimmed.isEmpty()) continue;
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
    if (storeId == null) {
      storeId = UUID.randomUUID().toString();
      writeUserState();
      LOGGER.info("New store ID for " + root.getFileName() + ": its clients will resync from scratch");
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
