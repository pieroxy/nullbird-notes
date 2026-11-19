package net.pieroxy.notes.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.pieroxy.notes.utils.AtomicFiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
 * </pre>
 * Every note's note.json is loaded into memory when the store opens: listing and change queries
 * never touch the disk. Methods are synchronized: commits for a user are serialized.
 * <p>
 * A commit writes, each atomically, the revision, content.txt, note.json (the commit point) and
 * user.json. What an interruption leaves behind never needs a repair pass on startup:
 * <ul>
 *   <li>a revision past note.json's is never read, and the next commit overwrites it;</li>
 *   <li>a content.txt not matching note.json's hash is rewritten from the head revision when read;</li>
 *   <li>a stale user.json is superseded by the notes' own seq on load;</li>
 *   <li>a note folder without note.json (an interrupted creation) is ignored.</li>
 * </ul>
 */
public class UserNoteStore {
  private final static Logger LOGGER = Logger.getLogger(UserNoteStore.class.getName());
  /** Lowercase UUID: note IDs name folders. */
  public static final Pattern NOTE_ID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  static final int TITLE_MAX_LENGTH = 100;
  private final static Gson GSON = new Gson();
  private final static Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  /** The steps of a commit, in order. */
  enum CommitStep { REVISION, CONTENT, META, USER_STATE }

  /** Visible for tests: throwing from it simulates a crash right after that step. */
  interface CommitHook {
    void after(CommitStep step) throws IOException;
  }

  private record UserState(long lastSeq, String storeId) {}

  private final Path root;
  private final Path notesDir;
  private final Clock clock;
  private final Map<String, NoteMeta> index = new HashMap<>();
  // Only each note's last change survives a restart (see NoteMeta#lastChangeId).
  private final Map<String, ChangeRecord> changes = new HashMap<>();
  private long lastSeq;
  private String storeId;
  CommitHook commitHook = step -> {};

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

  /** @return null if there's no such note. */
  public synchronized NoteMeta get(String noteId) {
    return index.get(noteId);
  }

  /** @return where the push with this ID landed, null if unknown. */
  public synchronized ChangeRecord findChange(String changeId) {
    return changeId == null ? null : changes.get(changeId);
  }

  /** @return the head revision's content, null if there's no such note. */
  public synchronized String readContent(String noteId) throws IOException {
    NoteMeta meta = index.get(noteId);
    if (meta == null) return null;
    Path contentFile = noteDir(noteId).resolve("content.txt");
    if (Files.isRegularFile(contentFile)) {
      byte[] bytes = Files.readAllBytes(contentFile);
      if (sha256(bytes).equals(meta.contentHash())) return new String(bytes, StandardCharsets.UTF_8);
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
    String submitted = write.content().equals(write.submittedContent()) ? null : write.submittedContent();
    Revision rev = new Revision(revision, seq, now, write.changeId(), write.deviceId(), write.deleted(), write.content(), submitted);
    AtomicFiles.write(revisionFile, lz4(GSON.toJson(rev).getBytes(StandardCharsets.UTF_8)));
    commitHook.after(CommitStep.REVISION);
    AtomicFiles.write(noteDir.resolve("content.txt"), content);
    commitHook.after(CommitStep.CONTENT);
    NoteMeta meta = new NoteMeta(noteId, revision, seq, current == null ? now : current.createdAt(), now, write.deleted(),
        title(write.content()), content.length, sha256(content), write.changeId(), write.conflictOf());
    AtomicFiles.write(noteDir.resolve("note.json"), PRETTY_GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));

    index.put(noteId, meta);
    lastSeq = seq;
    if (write.changeId() != null) changes.put(write.changeId(), new ChangeRecord(noteId, revision));
    commitHook.after(CommitStep.META);

    try {
      writeUserState();
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, "Could not write user.json of " + root.getFileName() + " (recovered from the notes on next load)", e);
    }
    commitHook.after(CommitStep.USER_STATE);
    return meta;
  }

  /** The first non-blank line, trimmed, at most {@link #TITLE_MAX_LENGTH} code points. */
  static String title(String content) {
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
      }
    }
  }

  private Path noteDir(String noteId) {
    return notesDir.resolve(noteId);
  }

  private Path revisionFile(String noteId, int revision) {
    return noteDir(noteId).resolve("revisions").resolve(String.format("%06d.json.lz4", revision));
  }

  private Revision readRevisionFile(String noteId, int revision) throws IOException {
    try (InputStream in = new LZ4FrameInputStream(Files.newInputStream(revisionFile(noteId, revision)))) {
      return GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), Revision.class);
    }
  }

  private static byte[] lz4(byte[] data) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (OutputStream out = new LZ4FrameOutputStream(bytes)) {
      out.write(data);
    }
    return bytes.toByteArray();
  }

  private static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
    }
  }
}
