package net.pieroxy.notes.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import net.pieroxy.notes.api.metadata.InvalidInputException;
import net.pieroxy.notes.api.metadata.NotFoundException;
import net.pieroxy.notes.fonts.FontFamily;
import net.pieroxy.notes.fonts.FontFile;
import net.pieroxy.notes.fonts.FontInspector;
import net.pieroxy.notes.fonts.FontStyle;
import net.pieroxy.notes.fonts.UploadedFont;
import net.pieroxy.notes.utils.AtomicFiles;
import net.pieroxy.notes.utils.Hashes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The fonts the admin installed (see {@link FontFamily}), in {@code <dataFolder>/fonts}: their list
 * in fonts.json, and their files, each named after its content. A file is uploaded first
 * ({@link #upload}), then made part of a family ({@link #save}); one no family has is deleted after
 * {@link #UNUSED_KEPT_FOR}, and on start.
 */
public class FontService implements Service {
  private final static Logger LOGGER = Logger.getLogger(FontService.class.getName());
  public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
  public static final int MAX_FAMILIES = 100;
  public static final int MAX_NAME_LENGTH = 60;
  public static final Duration UNUSED_KEPT_FOR = Duration.ofHours(1);
  public static final Pattern FILE_PATTERN = Pattern.compile("[0-9a-f]{64}\\.(woff2|woff|ttf|otf)");
  public static final Pattern ID_PATTERN = Pattern.compile("[0-9a-f]{16}");
  private static final String INDEX = "fonts.json";
  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Clock clock;
  private Path folder;
  private List<FontFamily> families = List.of();

  public FontService() {
    this(Clock.systemUTC());
  }

  FontService(Clock clock) {
    this.clock = clock;
  }

  @Override
  public List<Class<? extends Service>> getDependencies() {
    return List.of(SettingsService.class);
  }

  @Override
  public void init(IServiceProvider serviceProvider) {
    folder = serviceProvider.getSettingsService().getDataFolder().toPath().resolve("fonts");
  }

  @Override
  public synchronized void start() {
    try {
      Files.createDirectories(folder);
      Path index = folder.resolve(INDEX);
      if (Files.exists(index)) {
        List<FontFamily> read = GSON.fromJson(Files.readString(index, StandardCharsets.UTF_8), new TypeToken<List<FontFamily>>() {}.getType());
        families = read == null ? List.of() : List.copyOf(read);
      }
    } catch (IOException | JsonParseException e) {
      throw new IllegalStateException("Could not read the installed fonts in " + folder + ": " + e.getMessage(), e);
    }
    deleteUnused(Duration.ZERO);
    if (!families.isEmpty()) LOGGER.info(families.size() + " font(s) installed");
  }

  /** By name. */
  public synchronized List<FontFamily> list() {
    return families;
  }

  /**
   * Keeps a font file until a family takes it, for {@link #UNUSED_KEPT_FOR} at most.
   *
   * @throws InvalidInputException if it's too large, or not a font a browser loads.
   */
  public UploadedFont upload(String admin, String clientAddress, InputStream in) throws IOException {
    byte[] data = in.readNBytes((int) MAX_FILE_BYTES + 1);
    if (data.length > MAX_FILE_BYTES) {
      throw new InvalidInputException("The file is larger than " + MAX_FILE_BYTES / (1024 * 1024) + " MB, the most a font file may be.");
    }
    FontInspector.Inspection font;
    try {
      font = FontInspector.inspect(data);
    } catch (InvalidInputException e) {
      LOGGER.info("Admin \"" + admin + "\" from " + clientAddress + " uploaded a font file, refused: " + e.getMessage());
      throw e;
    }
    String name = Hashes.sha256(data) + "." + FontInspector.extension(font.format());
    Path target = folder.resolve(name);
    synchronized (this) {
      if (Files.exists(target)) {
        // Uploaded again: as just uploaded, not to be taken for unused.
        Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.from(clock.instant()));
      } else {
        Path tmp = folder.resolve(name + AtomicFiles.TMP_SUFFIX);
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.from(clock.instant()));
      }
    }
    LOGGER.info("Admin \"" + admin + "\" from " + clientAddress + " uploaded the font file " + name + " (" + data.length + " bytes"
        + (font.familyName() != null ? ", \"" + printable(font.familyName()) + "\"" : "") + ")");
    return new UploadedFont(name, format(font), data.length, font.variable(), font.weightMin(), font.weightMax(), font.weight(),
        font.italic(), font.familyName() == null ? null : printable(font.familyName()), font.monospace(),
        FontStyle.of(font.weight(), font.italic()));
  }

  /**
   * Adds a family ({@code id} null) or replaces one, with these files, uploaded before.
   *
   * @return every family, as now.
   * @throws InvalidInputException if the name or files aren't acceptable.
   * @throws NotFoundException if there's no family {@code id}.
   */
  public synchronized List<FontFamily> save(String admin, String clientAddress, String id, String name, boolean monospace,
                                            Map<FontStyle, String> files) throws IOException {
    String cleanName = name == null ? "" : name.strip();
    if (cleanName.isEmpty()) throw new InvalidInputException("Give the font a name.");
    if (cleanName.length() > MAX_NAME_LENGTH) throw new InvalidInputException("The name is longer than " + MAX_NAME_LENGTH + " characters.");
    if (cleanName.chars().anyMatch(Character::isISOControl)) throw new InvalidInputException("The name has invalid characters.");
    if (files.isEmpty()) throw new InvalidInputException("Add at least one file to the font.");
    if (id != null && find(id) == null) throw new NotFoundException("This font isn't installed anymore.");
    for (FontFamily other : families) {
      if (!other.id().equals(id) && other.name().equalsIgnoreCase(cleanName)) {
        throw new InvalidInputException("There's already a font named \"" + other.name() + "\".");
      }
    }
    if (id == null && families.size() >= MAX_FAMILIES) throw new InvalidInputException("There are " + MAX_FAMILIES + " fonts already, the most there can be.");

    List<FontFile> fontFiles = new ArrayList<>();
    for (Map.Entry<FontStyle, String> entry : new EnumMap<>(files).entrySet()) {
      String file = entry.getValue();
      Path path = file == null || !FILE_PATTERN.matcher(file).matches() ? null : folder.resolve(file);
      if (path == null || !Files.isRegularFile(path)) throw new InvalidInputException("A file of the font is gone: upload it again.");
      // Read again rather than trusted: the name only says what it was when uploaded.
      byte[] data = Files.readAllBytes(path);
      FontInspector.Inspection font = FontInspector.inspect(data);
      fontFiles.add(new FontFile(entry.getKey(), file, format(font), data.length, font.variable(), font.weightMin(), font.weightMax()));
    }

    String familyId = id != null ? id : newId();
    FontFamily family = new FontFamily(familyId, cleanName, monospace, List.copyOf(fontFiles));
    List<FontFamily> updated = new ArrayList<>(families);
    updated.removeIf(f -> f.id().equals(familyId));
    updated.add(family);
    updated.sort(Comparator.comparing(f -> f.name().toLowerCase(Locale.ROOT)));
    write(updated);
    LOGGER.info("Admin \"" + admin + "\" from " + clientAddress + (id == null ? " installed" : " changed") + " the font \"" + cleanName
        + "\" (" + familyId + "): " + fontFiles.stream().map(f -> f.style().name().toLowerCase(Locale.ROOT) + " " + f.file()).toList()
        + (monospace ? ", fixed-width" : ""));
    deleteUnused(UNUSED_KEPT_FOR);
    return families;
  }

  /** @return every family, as now. */
  public synchronized List<FontFamily> delete(String admin, String clientAddress, String id) throws IOException {
    FontFamily family = find(id);
    if (family == null) throw new NotFoundException("This font isn't installed anymore.");
    List<FontFamily> updated = new ArrayList<>(families);
    updated.remove(family);
    write(updated);
    LOGGER.info("Admin \"" + admin + "\" from " + clientAddress + " deleted the font \"" + family.name() + "\" (" + id + ")");
    deleteUnused(UNUSED_KEPT_FOR);
    return families;
  }

  /** @return the font file named {@code name}, installed or just uploaded; null if there's none. */
  public Path file(String name) {
    if (name == null || !FILE_PATTERN.matcher(name).matches()) return null;
    Path path = folder.resolve(name);
    return Files.isRegularFile(path) ? path : null;
  }

  private FontFamily find(String id) {
    if (id == null) return null;
    return families.stream().filter(f -> f.id().equals(id)).findFirst().orElse(null);
  }

  private void write(List<FontFamily> updated) throws IOException {
    AtomicFiles.write(folder.resolve(INDEX), GSON.toJson(updated).getBytes(StandardCharsets.UTF_8));
    families = List.copyOf(updated);
  }

  /** The files no family has, older than {@code age} (any age if zero); and temporary files left by a crash. */
  private synchronized void deleteUnused(Duration age) {
    Set<String> used = new HashSet<>();
    for (FontFamily family : families) for (FontFile file : family.files()) used.add(file.file());
    long before = clock.millis() - age.toMillis();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
      for (Path entry : entries) {
        String name = entry.getFileName().toString();
        boolean unused = FILE_PATTERN.matcher(name).matches() && !used.contains(name);
        boolean leftover = name.endsWith(AtomicFiles.TMP_SUFFIX);
        if ((unused || leftover) && (age.isZero() || Files.getLastModifiedTime(entry).toMillis() <= before)) {
          try {
            Files.delete(entry);
          } catch (NoSuchFileException ignored) {
            // Gone already.
          }
        }
      }
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "Could not delete the unused font files in " + folder + ": " + e.getMessage());
    }
  }

  private static String format(FontInspector.Inspection font) {
    return font.format().name().toLowerCase(Locale.ROOT);
  }

  private static String newId() {
    byte[] bytes = new byte[8];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /** A name read from a file, for a log line or the page: no control characters, not too long. */
  private static String printable(String text) {
    String clean = text.replaceAll("\\p{Cntrl}", "").strip();
    return clean.length() > MAX_NAME_LENGTH ? clean.substring(0, MAX_NAME_LENGTH) : clean;
  }
}
