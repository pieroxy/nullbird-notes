package net.pieroxy.notes.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.pieroxy.notes.config.BackupConfiguration;
import net.pieroxy.notes.storage.UserNoteStore;
import net.pieroxy.notes.utils.AtomicFiles;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Backups (see {@link BackupConfiguration}): a zip in the backup folder, every hour or day, made
 * only if something changed since the last one, the oldest ones beyond the number to keep deleted.
 *
 * <p>A backup holds config.json and credentials.json (secrets included: password hashes, second
 * factors, the SMTP password: readable by the server's user only), and {@code data/users} and
 * {@code data/fonts}: the rest of the data folder is rebuilt on startup, logs, or files waiting to be
 * imported. Each user's notes are copied while their store is locked: a note is never caught half
 * written.
 *
 * <p>The folder is never created: a drive that isn't mounted must fail, not fill the server's own
 * disk. It's checked before every backup as when the admin chose it (see {@link #check}). A backup is
 * written as {@code .partial}, then renamed: a crash never leaves one that looks complete. A failure
 * is logged as SEVERE and shown to the admin; it's tried again an hour later at most.
 *
 * <p>What's due is kept in {@code <dataFolder>/backup.json}, so a restart doesn't make one early.
 */
public class BackupService implements Service {
  private final static Logger LOGGER = Logger.getLogger(BackupService.class.getName());
  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
  public static final String PREFIX = "notes-backup-";
  private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);
  static final Pattern BACKUP_NAME = Pattern.compile(Pattern.quote(PREFIX) + "\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}Z\\.zip");
  private static final String PARTIAL = ".partial";
  private static final Pattern PROBE_NAME = Pattern.compile(Pattern.quote(PREFIX) + "check-[0-9a-f]{16}\\.tmp");
  private static final String STATE_FILE = "backup.json";
  /** How often it looks whether one is due; the first time a minute after startup. */
  private static final Duration TICK = Duration.ofMinutes(1);
  /** After a failure, tried again this much later, or the interval if shorter. */
  private static final Duration RETRY = Duration.ofHours(1);

  /**
   * What was done last.
   *
   * @param folder      where the last backup went: another folder needs one of its own.
   * @param lastRunAt   the last success: a backup made, or none needed.
   * @param fingerprint of what the last backup held (see {@link #fingerprint}).
   * @param lastErrorAt the last failure, if it came after the last success.
   */
  record State(String folder, Long lastRunAt, Long lastBackupAt, String lastBackupFile, Long lastBackupSize, String fingerprint,
               Long lastErrorAt, String lastError) {
    static final State NONE = new State(null, null, null, null, null, null, null, null);
  }

  /**
   * For the admin.
   *
   * @param config  null: backups are off.
   * @param running one is being made, or about to be.
   * @param nextAt  when the next one is due (made only if something changed); null if off.
   */
  public record Status(BackupConfiguration config, boolean running, Long lastBackupAt, String lastBackupFile, Long lastBackupSize,
                       Long lastErrorAt, String lastError, Long nextAt) {}

  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private SettingsService settings;
  private NoteStoreService stores;
  private Path dataFolder;
  private ScheduledExecutorService executor;
  private volatile BackupConfiguration config;
  private volatile State state = State.NONE;
  /** A backup asked for, not started yet. */
  private volatile boolean queued;
  private volatile boolean running;

  public BackupService() {
    this(Clock.systemUTC());
  }

  BackupService(Clock clock) {
    this.clock = clock;
  }

  @Override
  public List<Class<? extends Service>> getDependencies() {
    return List.of(SettingsService.class, NoteStoreService.class);
  }

  @Override
  public void init(IServiceProvider serviceProvider) {
    settings = serviceProvider.getSettingsService();
    stores = serviceProvider.getNoteStoreService();
    dataFolder = settings.getDataFolder().toPath().toAbsolutePath().normalize();
  }

  @Override
  public void start() {
    state = loadState();
    config = settings.getConfig().getBackup();
    executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "backup");
      thread.setDaemon(true);
      return thread;
    });
    executor.scheduleWithFixedDelay(this::runIfDue, TICK.toMillis(), TICK.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void destroy() {
    if (executor != null) executor.shutdownNow();
  }

  /** The settings now in effect, as saved by the admin; null: no more backups. */
  public void configure(BackupConfiguration config) {
    this.config = config;
  }

  /** Makes one now, in the background, changed or not: e.g. once activated, to know it works. */
  public void backUpNow() {
    queued = true;
    executor.execute(() -> run(true));
  }

  public Status status() {
    BackupConfiguration current = config;
    State s = state;
    return new Status(current, queued || running, s.lastBackupAt(), s.lastBackupFile(), s.lastBackupSize(), s.lastErrorAt(), s.lastError(),
        current == null ? null : nextAt(current, s));
  }

  void runIfDue() {
    BackupConfiguration current = config;
    if (current != null && clock.millis() >= nextAt(current, state)) run(false);
  }

  /** Right away the first time, or for another folder; after a failure, an hour later at most. */
  static long nextAt(BackupConfiguration config, State s) {
    long interval = config.getEvery().interval().toMillis();
    long retry = Math.min(interval, RETRY.toMillis());
    long next = s.lastRunAt() == null || !config.getFolder().equals(s.folder()) ? 0 : s.lastRunAt() + interval;
    if (s.lastErrorAt() != null) next = Math.max(next, s.lastErrorAt() + retry);
    return next;
  }

  /**
   * One backup, unless {@code force} is false and nothing changed since the last one, which is still
   * there. Runs on the backup thread only: one at a time.
   */
  void run(boolean force) {
    BackupConfiguration current = config;
    running = true;
    queued = false;
    if (current == null) {
      running = false;
      return;
    }
    long now = clock.millis();
    State before = state;
    try {
      Path folder = check(current.getFolder());
      removeLeftovers(folder);
      String fingerprint = fingerprint();
      boolean unchanged = !force && fingerprint.equals(before.fingerprint()) && current.getFolder().equals(before.folder())
          && before.lastBackupFile() != null && Files.exists(folder.resolve(before.lastBackupFile()));
      if (unchanged) {
        saveState(new State(before.folder(), now, before.lastBackupAt(), before.lastBackupFile(), before.lastBackupSize(), fingerprint, null, null));
        LOGGER.fine("No backup needed: nothing changed since " + before.lastBackupFile());
        return;
      }
      String name = PREFIX + NAME_TIME.format(Instant.ofEpochMilli(now)) + ".zip";
      Path target = folder.resolve(name);
      if (Files.exists(target)) throw new IOException(target + " already exists");
      Path partial = folder.resolve(name + PARTIAL);
      try {
        writeZip(partial);
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        AtomicFiles.syncDirectory(folder);
      } catch (IOException | RuntimeException e) {
        Files.deleteIfExists(partial);
        throw e;
      }
      long size = Files.size(target);
      saveState(new State(current.getFolder(), now, now, name, size, fingerprint, null, null));
      LOGGER.info("Backup " + target + " made: " + size + " bytes");
      prune(folder, current.getKeep());
    } catch (IOException | RuntimeException e) {
      String error = e instanceof IllegalArgumentException ? e.getMessage() : describe(e);
      try {
        saveState(new State(before.folder(), before.lastRunAt(), before.lastBackupAt(), before.lastBackupFile(), before.lastBackupSize(),
            before.fingerprint(), now, error));
      } catch (IOException stateError) {
        LOGGER.log(Level.WARNING, "Could not write " + dataFolder.resolve(STATE_FILE), stateError);
      }
      LOGGER.log(Level.SEVERE, "Backup to " + current.getFolder() + " failed: " + error, e instanceof IllegalArgumentException ? null : e);
    } finally {
      running = false;
    }
  }

  /**
   * Whether backups can go to {@code folder}: a full path to a folder that exists, outside the data
   * folder (and not around it), empty or with backups only, where the server can write, read and
   * delete a file.
   *
   * @return it, as a path.
   * @throws IllegalArgumentException saying, for the admin, what's wrong.
   */
  public Path check(String folder) {
    if (folder == null || folder.isBlank()) throw new IllegalArgumentException("Choose a folder.");
    Path path;
    try {
      path = Path.of(folder.trim());
    } catch (InvalidPathException e) {
      throw new IllegalArgumentException("\"" + folder + "\" isn't a valid path.");
    }
    if (!path.isAbsolute()) throw new IllegalArgumentException("The folder must be a full path, like /mnt/backups/notes.");
    if (!Files.exists(path)) {
      throw new IllegalArgumentException("The folder " + path + " doesn't exist. Notes doesn't create it: create it (or mount its drive) first.");
    }
    if (!Files.isDirectory(path)) throw new IllegalArgumentException(path + " isn't a folder.");
    Path real;
    Path data;
    try {
      real = path.toRealPath();
      data = Files.exists(dataFolder) ? dataFolder.toRealPath() : dataFolder;
    } catch (IOException e) {
      throw new IllegalArgumentException("The folder " + path + " can't be read: " + describe(e));
    }
    if (real.startsWith(data)) {
      throw new IllegalArgumentException("The folder is inside the data folder (" + data + "): each backup would hold the ones before it.");
    }
    if (data.startsWith(real)) throw new IllegalArgumentException("The data folder (" + data + ") is inside this folder: choose one outside it.");
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
      for (Path entry : entries) {
        String name = entry.getFileName().toString();
        if (!isOurs(name)) {
          throw new IllegalArgumentException("The folder " + path + " has something else than backups in it, like \"" + name
              + "\": choose an empty folder, or one with Notes backups only.");
        }
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("The folder " + path + " can't be listed: " + describe(e));
    }
    probe(path);
    return path;
  }

  /** Backups, one being written, a check's file, or what a freshly formatted drive has at its root. */
  private static boolean isOurs(String name) {
    return BACKUP_NAME.matcher(name).matches() || name.endsWith(PARTIAL) && BACKUP_NAME.matcher(name.substring(0, name.length() - PARTIAL.length())).matches()
        || PROBE_NAME.matcher(name).matches() || name.equals("lost+found");
  }

  /** Writes a file, reads it back, deletes it: as backups will. */
  private void probe(Path folder) {
    byte[] id = new byte[8];
    random.nextBytes(id);
    Path probe = folder.resolve(PREFIX + "check-" + HexFormat.of().formatHex(id) + ".tmp");
    byte[] content = ("Notes checks it can write here: " + Instant.ofEpochMilli(clock.millis()) + "\n").getBytes(StandardCharsets.UTF_8);
    try {
      Files.write(probe, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    } catch (IOException e) {
      throw new IllegalArgumentException("Notes can't write in " + folder + ": " + describe(e));
    }
    try {
      if (!Arrays.equals(Files.readAllBytes(probe), content)) throw new IllegalArgumentException("A file written in " + folder + " reads back different.");
    } catch (IOException e) {
      throw new IllegalArgumentException("Notes can't read what it writes in " + folder + ": " + describe(e));
    } finally {
      try {
        Files.deleteIfExists(probe);
      } catch (IOException e) {
        // Over any other error: old backups couldn't be deleted either.
        throw new IllegalArgumentException("Notes can't delete files in " + folder + ": " + describe(e));
      }
    }
  }

  /** What a crash left: a backup half written, a check's file. */
  private static void removeLeftovers(Path folder) throws IOException {
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
      for (Path entry : entries) {
        String name = entry.getFileName().toString();
        if (name.endsWith(PARTIAL) && isOurs(name) || PROBE_NAME.matcher(name).matches()) Files.deleteIfExists(entry);
      }
    }
  }

  /** The oldest beyond {@code keep}: the names sort by date. */
  private static void prune(Path folder, int keep) {
    List<Path> backups = new ArrayList<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder, entry -> BACKUP_NAME.matcher(entry.getFileName().toString()).matches())) {
      entries.forEach(backups::add);
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "Could not list the backups in " + folder + " to delete the old ones", e);
      return;
    }
    backups.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
    for (Path old : backups.subList(Math.min(keep, backups.size()), backups.size())) {
      try {
        Files.deleteIfExists(old);
        LOGGER.info("Old backup " + old + " deleted");
      } catch (IOException e) {
        LOGGER.log(Level.WARNING, "Could not delete the old backup " + old, e);
      }
    }
  }

  /** config.json, credentials.json, then data/fonts and each user's data/users/{name}, the user's store locked. */
  private void writeZip(Path file) throws IOException {
    try {
      Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException e) {
      Files.createFile(file);
    }
    try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
         ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out, 64 * 1024))) {
      addFile(zip, settings.getConfigFile().toPath(), "config.json");
      addFile(zip, settings.getCredentialsFile().toPath(), "credentials.json");
      addTree(zip, dataFolder.resolve("fonts"), "data/fonts/");
      for (String username : stores.usernamesWithData()) {
        UserNoteStore store = stores.getStore(username);
        // Its methods are synchronized: no commit while it's copied.
        synchronized (store) {
          addTree(zip, dataFolder.resolve("users").resolve(username), "data/users/" + username + "/");
        }
      }
    }
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  private static void addTree(ZipOutputStream zip, Path dir, String prefix) throws IOException {
    if (!Files.isDirectory(dir)) return;
    List<Path> files;
    try (Stream<Path> walk = Files.walk(dir)) {
      files = walk.filter(Files::isRegularFile).sorted().toList();
    }
    for (Path file : files) {
      // A write that never finished (see AtomicFiles).
      if (file.getFileName().toString().endsWith(AtomicFiles.TMP_SUFFIX)) continue;
      StringBuilder name = new StringBuilder(prefix);
      for (Path part : dir.relativize(file)) {
        if (name.length() > prefix.length()) name.append('/');
        name.append(part);
      }
      addFile(zip, file, name.toString());
    }
  }

  /** Nothing if it doesn't exist (anymore). */
  private static void addFile(ZipOutputStream zip, Path file, String name) throws IOException {
    FileTime modified;
    try {
      modified = Files.getLastModifiedTime(file);
    } catch (NoSuchFileException e) {
      return;
    }
    ZipEntry entry = new ZipEntry(name);
    entry.setLastModifiedTime(modified);
    zip.putNextEntry(entry);
    try {
      Files.copy(file, zip);
    } catch (NoSuchFileException e) {
      // Gone since: an empty entry, as good as none (a font replaced, the old one deleted).
    }
    zip.closeEntry();
  }

  /**
   * What a backup would hold, in short: config.json, credentials.json, the fonts' index, and each
   * user's store and last change (every change to a store moves it on).
   */
  String fingerprint() throws IOException {
    MessageDigest sha;
    try {
      sha = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    for (Path file : List.of(settings.getConfigFile().toPath(), settings.getCredentialsFile().toPath(), dataFolder.resolve("fonts").resolve("fonts.json"))) {
      byte[] content = Files.exists(file) ? Files.readAllBytes(file) : new byte[0];
      sha.update((file.getFileName() + ":" + content.length + "\n").getBytes(StandardCharsets.UTF_8));
      sha.update(content);
    }
    for (String username : stores.usernamesWithData().stream().sorted().toList()) {
      UserNoteStore store = stores.getStore(username);
      sha.update((username + ":" + store.getStoreId() + ":" + store.getLastSeq() + "\n").getBytes(StandardCharsets.UTF_8));
    }
    return HexFormat.of().formatHex(sha.digest());
  }

  private State loadState() {
    Path file = dataFolder.resolve(STATE_FILE);
    if (!Files.exists(file)) return State.NONE;
    try {
      State loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), State.class);
      return loaded == null ? State.NONE : loaded;
    } catch (IOException | JsonParseException e) {
      LOGGER.log(Level.WARNING, "Could not read " + file + ": the next backup is made right away", e);
      return State.NONE;
    }
  }

  private void saveState(State next) throws IOException {
    state = next;
    AtomicFiles.write(dataFolder.resolve(STATE_FILE), GSON.toJson(next).getBytes(StandardCharsets.UTF_8));
  }

  /** An I/O error, for the admin: the path alone (NoSuchFileException's message) says little. */
  static String describe(Exception e) {
    if (e instanceof NoSuchFileException) return "no such file: " + e.getMessage();
    if (e instanceof AccessDeniedException) return "permission denied: " + e.getMessage();
    if (e instanceof FileSystemException fs && fs.getReason() != null) return fs.getReason() + ": " + fs.getFile();
    return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
  }
}
