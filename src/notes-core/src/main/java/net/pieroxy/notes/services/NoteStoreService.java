package net.pieroxy.notes.services;

import net.pieroxy.notes.storage.NoteMeta;
import net.pieroxy.notes.storage.UserNoteStore;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Each user's {@link UserNoteStore}, under {@code <dataFolder>/users/<username>}, opened on first
 * use. Thins out old revisions shortly after startup, then daily (see {@link UserNoteStore#thin}).
 */
public class NoteStoreService implements Service {
  private final static Logger LOGGER = Logger.getLogger(NoteStoreService.class.getName());
  /** Not right at startup: it reads every revision file. */
  private static final Duration FIRST_THINNING = Duration.ofMinutes(1);
  private static final Duration THINNING_PERIOD = Duration.ofDays(1);

  private final Clock clock;
  private final Map<String, UserNoteStore> stores = new HashMap<>();
  private Path usersDir;
  private ScheduledExecutorService thinner;

  public NoteStoreService() {
    this(Clock.systemUTC());
  }

  NoteStoreService(Clock clock) {
    this.clock = clock;
  }

  @Override
  public List<Class<? extends Service>> getDependencies() {
    return List.of(SettingsService.class);
  }

  @Override
  public void init(IServiceProvider serviceProvider) {
    usersDir = serviceProvider.getSettingsService().getDataFolder().toPath().resolve("users");
  }

  @Override
  public void start() {
    thinner = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "revision-thinning");
      thread.setDaemon(true);
      return thread;
    });
    thinner.scheduleAtFixedRate(this::thinAll, FIRST_THINNING.toMillis(), THINNING_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void destroy() {
    if (thinner != null) thinner.shutdownNow();
  }

  /** Every user's notes; one failing doesn't stop the others. */
  void thinAll() {
    if (!Files.isDirectory(usersDir)) return;
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(usersDir, Files::isDirectory)) {
      for (Path dir : dirs) {
        String username = dir.getFileName().toString();
        if (!SettingsService.USERNAME_PATTERN.matcher(username).matches()) continue;
        try {
          int deleted = getStore(username).thin(clock.millis());
          if (deleted > 0) LOGGER.info("Thinned out " + deleted + " old revision(s) of " + username);
        } catch (IOException | RuntimeException e) {
          LOGGER.log(Level.SEVERE, "Could not thin out the revisions of " + username, e);
        }
      }
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, "Could not list " + usersDir, e);
    }
  }

  /**
   * @return the size in UTF-8 bytes of each of the user's notes larger than {@code minBytes}, deleted
   * ones aside; none if the user has no data yet (nothing is created for them).
   */
  public List<Long> sizesAbove(String username, long minBytes) throws IOException {
    if (!hasData(username)) return List.of();
    return getStore(username).list().stream().filter(note -> !note.deleted() && note.size() > minBytes).map(NoteMeta::size).toList();
  }

  /** @return whether there's a data folder for {@code username}, e.g. left by a user removed from credentials.json. */
  public boolean hasData(String username) {
    if (username == null || !SettingsService.USERNAME_PATTERN.matcher(username).matches()) {
      throw new IllegalArgumentException("Invalid username: " + username);
    }
    return Files.exists(usersDir.resolve(username));
  }

  public synchronized UserNoteStore getStore(String username) throws IOException {
    if (username == null || !SettingsService.USERNAME_PATTERN.matcher(username).matches()) {
      throw new IllegalArgumentException("Invalid username: " + username);
    }
    UserNoteStore store = stores.get(username);
    if (store == null) {
      store = UserNoteStore.open(usersDir.resolve(username), clock);
      stores.put(username, store);
    }
    return store;
  }
}
