package net.pieroxy.notes.storage;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.pieroxy.notes.utils.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * The version of everything the server stores in the data folder, in {@code <dataFolder>/format.json}.
 * Checked on startup, before anything reads or writes the data: an older format is migrated, a
 * newer one (written by a later version of the server) refuses to start.
 */
public final class DataFormat {
  private final static Logger LOGGER = Logger.getLogger(DataFormat.class.getName());
  public static final int CURRENT_VERSION = 1;
  static final String FILE_NAME = "format.json";
  private static final Gson GSON = new Gson();

  /** Turns the data folder from format {@code from} into {@code from + 1}. */
  interface Migration {
    int from();

    void migrate(Path dataFolder) throws IOException;
  }

  /** In order: one per version below {@link #CURRENT_VERSION}. */
  private static final List<Migration> MIGRATIONS = List.of();

  private record Format(Integer version) {}

  private DataFormat() {}

  /**
   * Brings the data folder to {@link #CURRENT_VERSION}. Without format.json, a folder already
   * holding data is in format 1 (data written before format.json existed); an empty one is new.
   *
   * @throws IllegalStateException if the format is newer than this server's, or format.json can't be read.
   */
  public static void upgrade(Path dataFolder) throws IOException {
    upgrade(dataFolder, CURRENT_VERSION, MIGRATIONS);
  }

  static void upgrade(Path dataFolder, int currentVersion, List<Migration> migrations) throws IOException {
    Path file = dataFolder.resolve(FILE_NAME);
    int version;
    if (Files.exists(file)) {
      version = read(file);
    } else {
      version = holdsData(dataFolder) ? 1 : currentVersion;
      write(file, version);
    }
    if (version > currentVersion) {
      throw new IllegalStateException(dataFolder + " is in data format " + version + ", written by a later version of notes: this one"
          + " only reads format " + currentVersion + " and older. Run a later version.");
    }
    while (version < currentVersion) {
      int from = version;
      Migration migration = migrations.stream().filter(m -> m.from() == from).findFirst()
          .orElseThrow(() -> new IllegalStateException("No migration from data format " + from));
      LOGGER.info("Migrating " + dataFolder + " from data format " + from + " to " + (from + 1));
      migration.migrate(dataFolder);
      version = from + 1;
      // After each step: an interrupted migration resumes from the step it was in.
      write(file, version);
    }
  }

  private static int read(Path file) throws IOException {
    try {
      Format format = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Format.class);
      if (format != null && format.version() != null && format.version() >= 1) return format.version();
    } catch (JsonParseException e) {
      // Reported below.
    }
    throw new IllegalStateException(file + " is unreadable: it should hold {\"version\": N}.");
  }

  private static void write(Path file, int version) throws IOException {
    AtomicFiles.write(file, GSON.toJson(new Format(version)).getBytes(StandardCharsets.UTF_8));
  }

  /** Logs, the extracted webapp and Tomcat's work folder are recreated as needed: they aren't data. */
  private static boolean holdsData(Path dataFolder) {
    return Files.exists(dataFolder.resolve("users")) || Files.exists(dataFolder.resolve("sessions.json"));
  }
}
