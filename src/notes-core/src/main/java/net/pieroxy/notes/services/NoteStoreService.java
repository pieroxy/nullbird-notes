package net.pieroxy.notes.services;

import net.pieroxy.notes.storage.UserNoteStore;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Each user's {@link UserNoteStore}, under {@code <dataFolder>/users/<username>}, opened on first use. */
public class NoteStoreService implements Service {
  private final Clock clock;
  private final Map<String, UserNoteStore> stores = new HashMap<>();
  private Path usersDir;

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
