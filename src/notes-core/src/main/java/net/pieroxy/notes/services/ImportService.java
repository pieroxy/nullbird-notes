package net.pieroxy.notes.services;

import net.pieroxy.notes.sync.TagPaths;
import net.pieroxy.notes.api.metadata.InvalidInputException;
import net.pieroxy.notes.api.metadata.TooManyRequestsException;
import net.pieroxy.notes.export.ImportSummary;
import net.pieroxy.notes.export.NotesFile;
import net.pieroxy.notes.storage.NoteMeta;
import net.pieroxy.notes.storage.NoteWrite;
import net.pieroxy.notes.storage.StaleRevisionException;
import net.pieroxy.notes.storage.UserNoteStore;
import net.pieroxy.notes.utils.Hashes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Imports an export, ours or Simplenote's (see {@link NotesFile}), into a user's notes, in two
 * steps: {@link #check} keeps the uploaded file and says what importing it would do; {@link #run}
 * does it, or {@link #cancel} drops it. Nothing is written to the notes before {@link #run}.
 * <p>
 * Only ever adds notes. A note of the file is skipped when the user already has its ID, whatever
 * its content, or a note with exactly its content, whatever its ID: importing the same file twice
 * imports nothing the second time. Otherwise it's imported with its ID when that's a valid one
 * (one of ours), a new one otherwise (Simplenote's), and its dates, tags and options.
 * <p>
 * Uploaded files wait in {@code <dataFolder>/imports}, one per user at most, for
 * {@link #PENDING_FOR}; the folder is emptied on start. Running works the plan out again: the notes
 * may have changed since the check.
 */
public class ImportService implements Service {
  private final static Logger LOGGER = Logger.getLogger(ImportService.class.getName());
  public static final long MAX_UPLOAD_BYTES = 64L * 1024 * 1024;
  /** Unzipped: as much as a zip of {@link #MAX_UPLOAD_BYTES} may hold, and parsed one note at a time. */
  public static final long MAX_JSON_BYTES = 64L * 1024 * 1024;
  /** Files checked or imported at once, server-wide: each takes memory and disk time. */
  public static final int MAX_CONCURRENT_IMPORTS = 2;
  public static final int MAX_NOTES = 100_000;
  public static final Duration PENDING_FOR = Duration.ofMinutes(15);
  private static final String DEVICE_ID = "import";
  private static final int MAX_FILE_NAME_LENGTH = 200;

  private record Pending(String token, Path file, String fileName, long expiresAt) {}

  private enum Outcome { IMPORT, ALREADY_THERE, TOO_LARGE }

  /**
   * @param id    the ID imported as, or the existing note's.
   * @param text  normalized as a pushed note's.
   */
  private record Decision(NotesFile.Note note, Outcome outcome, String id, String text, long size, List<String> tags,
                          Map<String, String> options, int droppedTags, int droppedOptions) {}

  private final Clock clock;
  private final Map<String, Pending> pending = new HashMap<>();
  /** Users with a file being checked or imported: one at a time each. */
  private final Set<String> busyUsers = ConcurrentHashMap.newKeySet();
  /** Package-visible for tests: taking its permits stands for imports in progress. */
  final Semaphore parsing = new Semaphore(MAX_CONCURRENT_IMPORTS);
  private SettingsService settings;
  private NoteStoreService noteStores;
  private Path folder;

  public ImportService() {
    this(Clock.systemUTC());
  }

  ImportService(Clock clock) {
    this.clock = clock;
  }

  @Override
  public List<Class<? extends Service>> getDependencies() {
    return List.of(SettingsService.class, NoteStoreService.class);
  }

  @Override
  public void init(IServiceProvider serviceProvider) {
    settings = serviceProvider.getSettingsService();
    noteStores = serviceProvider.getNoteStoreService();
    folder = settings.getDataFolder().toPath().resolve("imports");
  }

  @Override
  public void start() {
    // Left by a restart: their tokens are gone with it.
    deleteAll();
  }

  @Override
  public synchronized void destroy() {
    pending.clear();
    deleteAll();
  }

  /**
   * Keeps {@code upload} as {@code username}'s pending import, in place of any previous one, and
   * says what running it would do.
   *
   * @throws InvalidInputException if it's too large, or not an export: nothing is kept then.
   * @throws TooManyRequestsException while another of the user's files is checked or imported, or
   * {@link #MAX_CONCURRENT_IMPORTS} are: nothing is kept then.
   */
  public ImportSummary check(String username, String clientAddress, String fileName, InputStream upload) throws IOException {
    String name = cleanFileName(fileName);
    startUserWork(username);
    Path file = null;
    try {
      Files.createDirectories(folder);
      file = folder.resolve(UUID.randomUUID() + ".upload");
      // Written to disk before taking a slot: a slow upload mustn't hold one.
      copyCapped(upload, file);
      Planner planner;
      startParsing();
      try {
        planner = new Planner(noteStores.getStore(username));
        NotesFile.read(file, MAX_JSON_BYTES, MAX_NOTES, planner::decide);
      } finally {
        parsing.release();
      }
      String token = UUID.randomUUID().toString();
      Pending previous;
      synchronized (this) {
        expire();
        previous = pending.put(username, new Pending(token, file, name, clock.millis() + PENDING_FOR.toMillis()));
      }
      if (previous != null) delete(previous.file());
      LOGGER.info("User \"" + username + "\" from " + clientAddress + " uploaded \"" + name + "\" to import: " + planner.notes + " notes");
      return planner.summary(token, name);
    } catch (IOException | RuntimeException e) {
      if (file != null) delete(file);
      if (e instanceof InvalidInputException) {
        LOGGER.info("User \"" + username + "\" from " + clientAddress + " uploaded \"" + name + "\" to import, refused: " + e.getMessage());
      }
      throw e;
    } finally {
      busyUsers.remove(username);
    }
  }

  /**
   * Imports {@code username}'s pending import, as {@link #check} worked out but against their notes
   * as they are now, logging what happens to each note. Each note is written as it's read: the
   * check read the whole file already.
   *
   * @throws InvalidInputException if there's no such pending import (expired, or replaced).
   * @throws TooManyRequestsException as {@link #check}: the import still waits then.
   */
  public ImportSummary run(String username, String clientAddress, String token) throws IOException {
    startUserWork(username);
    try {
      startParsing();
      try {
        Pending upload = take(username, token);
        try {
          String who = "Import for \"" + username + "\": ";
          LOGGER.info(who + "from " + clientAddress + ", \"" + upload.fileName() + "\"");
          UserNoteStore store = noteStores.getStore(username);
          Planner planner;
          synchronized (store) {
            planner = new Planner(store);
            NotesFile.read(upload.file(), MAX_JSON_BYTES, MAX_NOTES, note -> apply(store, who, planner.decide(note)));
          }
          ImportSummary summary = planner.summary(null, upload.fileName());
          LOGGER.info(who + "done, " + summary.notesInFile() + " notes, " + summary.imported() + " imported (" + summary.trashed()
              + " to the trash), " + summary.alreadyThere() + " already there, " + summary.tooLarge().size() + " too large");
          return summary;
        } finally {
          delete(upload.file());
        }
      } finally {
        parsing.release();
      }
    } finally {
      busyUsers.remove(username);
    }
  }

  /** @throws TooManyRequestsException if another of {@code username}'s files is being checked or imported. */
  private void startUserWork(String username) {
    if (!busyUsers.add(username)) {
      throw new TooManyRequestsException("Your previous file is still being checked or imported: wait for it to finish.", 5);
    }
  }

  /** @throws TooManyRequestsException if {@link #MAX_CONCURRENT_IMPORTS} files are being checked or imported. */
  private void startParsing() {
    if (!parsing.tryAcquire()) {
      throw new TooManyRequestsException("The server is busy with other imports: retry in a moment.", 10);
    }
  }

  /** Drops {@code username}'s pending import, if it's still {@code token}. */
  public void cancel(String username, String token) {
    Pending upload;
    synchronized (this) {
      upload = pending.get(username);
      if (upload == null || !upload.token().equals(token)) return;
      pending.remove(username);
    }
    delete(upload.file());
    LOGGER.info("User \"" + username + "\" cancelled the import of \"" + upload.fileName() + "\"");
  }

  private synchronized Pending take(String username, String token) {
    expire();
    Pending upload = pending.get(username);
    if (upload == null || !upload.token().equals(token)) {
      throw new InvalidInputException("This import is no longer waiting (after " + PENDING_FOR.toMinutes() + " minutes, or replaced by another "
          + "file): choose the file again.");
    }
    pending.remove(username);
    return upload;
  }

  /** Drops imports pending for longer than {@link #PENDING_FOR}. Checked on each call: there's no hurry. */
  private void expire() {
    long now = clock.millis();
    pending.values().removeIf(upload -> {
      if (upload.expiresAt() > now) return false;
      delete(upload.file());
      return true;
    });
  }

  /**
   * What happens to each note, decided one at a time, in the file's order, and the summary of it
   * all. Only the user's IDs and content hashes are kept, not the notes.
   */
  private final class Planner {
    private final Set<String> ids = new HashSet<>();
    private final Map<String, String> byHash = new HashMap<>();
    private final long maxSize = settings.getMaxNoteSize();
    private int notes;
    private int imported;
    private int trashed;
    private int alreadyThere;
    /** Over the largest note size each: no more of them than the file's size allows. */
    private final List<ImportSummary.TooLargeNote> tooLarge = new ArrayList<>();

    /** The user's notes as they are now: a moment's lock, not one for the whole file. */
    Planner(UserNoteStore store) {
      synchronized (store) {
        for (NoteMeta note : store.list()) {
          ids.add(note.id());
          byHash.putIfAbsent(note.contentHash(), note.id());
        }
      }
    }

    Decision decide(NotesFile.Note note) {
      notes++;
      String text = NoteService.normalize(note.content());
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      String hash = Hashes.sha256(bytes);
      String fileId = note.id() == null ? null : note.id().toLowerCase(Locale.ROOT);
      String existing = fileId != null && ids.contains(fileId) ? fileId : byHash.get(hash);
      if (existing != null) {
        alreadyThere++;
        return new Decision(note, Outcome.ALREADY_THERE, existing, null, bytes.length, null, null, 0, 0);
      }
      if (bytes.length > maxSize) {
        tooLarge.add(new ImportSummary.TooLargeNote(UserNoteStore.title(text), bytes.length));
        return new Decision(note, Outcome.TOO_LARGE, null, null, bytes.length, null, null, 0, 0);
      }
      String id = fileId != null && UserNoteStore.NOTE_ID_PATTERN.matcher(fileId).matches() ? fileId : UUID.randomUUID().toString();
      List<String> tags = new ArrayList<>(new LinkedHashSet<>(note.tags().stream().filter(java.util.Objects::nonNull).map(TagPaths::normalize).filter(NoteService::isValidTag).toList()));
      if (tags.size() > NoteService.MAX_TAGS) tags = tags.subList(0, NoteService.MAX_TAGS);
      Map<String, String> options = new LinkedHashMap<>();
      for (Map.Entry<String, String> option : note.options().entrySet()) {
        if (options.size() < NoteService.MAX_OPTIONS && NoteService.isValidOption(option.getKey(), option.getValue())) {
          options.put(option.getKey(), option.getValue());
        }
      }
      ids.add(id);
      byHash.putIfAbsent(hash, id);
      imported++;
      if (note.deleted()) trashed++;
      return new Decision(note, Outcome.IMPORT, id, text, bytes.length, tags, options, note.tags().size() - tags.size(),
          note.options().size() - options.size());
    }

    ImportSummary summary(String token, String fileName) {
      return new ImportSummary(token, fileName, notes, imported, trashed, alreadyThere, List.copyOf(tooLarge), maxSize);
    }
  }

  private void apply(UserNoteStore store, String who, Decision decision) throws IOException {
    String fileId = printableId(decision.note().id());
    switch (decision.outcome()) {
      case ALREADY_THERE -> LOGGER.info(who + fileId + " skipped, already there as " + decision.id());
      case TOO_LARGE -> LOGGER.warning(who + fileId + " skipped, too large: " + decision.size() + " bytes, the limit is "
          + settings.getMaxNoteSize());
      case IMPORT -> {
        long now = clock.millis();
        NotesFile.Note note = decision.note();
        long createdAt = note.createdAt() != null ? note.createdAt() : note.modifiedAt() != null ? note.modifiedAt() : now;
        long modifiedAt = note.modifiedAt() != null ? note.modifiedAt() : createdAt;
        try {
          store.importNote(decision.id(), new NoteWrite(decision.text(), note.deleted(), null, DEVICE_ID, null, decision.tags(),
              decision.options()), createdAt, modifiedAt);
        } catch (StaleRevisionException e) {
          throw new IllegalStateException("Note created while its store was locked", e);
        }
        StringBuilder line = new StringBuilder(who).append("imported ").append(fileId);
        if (!decision.id().equals(note.id())) line.append(" as ").append(decision.id());
        if (note.deleted()) line.append(", to the trash");
        if (decision.droppedTags() > 0) line.append(", ").append(decision.droppedTags()).append(" invalid or duplicate tag(s) left out");
        if (decision.droppedOptions() > 0) line.append(", ").append(decision.droppedOptions()).append(" invalid option(s) left out");
        LOGGER.info(line.toString());
      }
    }
  }

  /** The file's ID as a log can show it: anything can be in there. */
  private static String printableId(String id) {
    if (id == null) return "(no ID)";
    StringBuilder sb = new StringBuilder();
    id.codePoints().limit(80).forEach(c -> {
      if (c < 0x20 || c == 0x7f) sb.append(String.format("\\x%02x", c));
      else sb.appendCodePoint(c);
    });
    return sb.toString();
  }

  /** Only shown back and logged: no path, no control characters. */
  static String cleanFileName(String fileName) {
    if (fileName == null || fileName.isBlank()) return "(no name)";
    String name = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
    StringBuilder sb = new StringBuilder();
    name.codePoints().filter(c -> c >= 0x20 && c != 0x7f && c != '"').limit(MAX_FILE_NAME_LENGTH).forEach(sb::appendCodePoint);
    return sb.isEmpty() ? "(no name)" : sb.toString();
  }

  private static void copyCapped(InputStream in, Path file) throws IOException {
    try (OutputStream out = Files.newOutputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      long total = 0;
      for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
        total += n;
        if (total > MAX_UPLOAD_BYTES) {
          throw new InvalidInputException("The file is larger than " + MAX_UPLOAD_BYTES / (1024 * 1024) + " MB, the most one import takes.");
        }
        out.write(buffer, 0, n);
      }
    }
  }

  private static void delete(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "Could not delete " + file, e);
    }
  }

  private void deleteAll() {
    if (folder == null || !Files.isDirectory(folder)) return;
    try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.upload")) {
      for (Path file : files) delete(file);
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "Could not empty " + folder, e);
    }
  }
}
