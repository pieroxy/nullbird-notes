package net.pieroxy.notes.services;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.config.CredentialsFile;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.webserver.TrustedProxies;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * config.json and credentials.json. credentials.json is re-read whenever it changes on disk, so
 * users can be added or removed without a restart. A change that fails to load is logged and
 * ignored: the users loaded last stay in effect.
 */
public class SettingsService implements Service {
  private final static Logger LOGGER = Logger.getLogger(SettingsService.class.getName());
  /** Usernames name the users' data folders: no path separators, no case-only differences. */
  public static final Pattern USERNAME_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

  private final Configuration config;
  private final File credentialsFile;
  private final File dataFolder;
  private final TrustedProxies trustedProxies;
  private final long maxNoteSize;

  private Map<String, UserCredential> users = Map.of();
  private long credentialsLastModified;
  private long credentialsLength;

  /** @throws IllegalArgumentException if config.json's trustedProxies or maxNoteSizeMb is invalid. */
  public SettingsService(Configuration config, File credentialsFile, File dataFolder) {
    this.config = config;
    this.credentialsFile = credentialsFile;
    this.dataFolder = dataFolder;
    this.trustedProxies = TrustedProxies.parse(config.getTrustedProxies());
    if (config.getMaxNoteSizeMb() < 1) {
      throw new IllegalArgumentException("maxNoteSizeMb must be at least 1, not " + config.getMaxNoteSizeMb() + ".");
    }
    this.maxNoteSize = config.getMaxNoteSizeMb() * 1024L * 1024L;
  }

  /** @throws IllegalStateException if credentials.json can't be loaded. */
  @Override
  public synchronized void start() {
    try {
      loadCredentials();
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException("Could not load " + credentialsFile + ": " + e.getMessage(), e);
    }
    LOGGER.info(users.size() + " user(s) loaded from " + credentialsFile);
  }

  public Configuration getConfig() {
    return config;
  }

  public File getDataFolder() {
    return dataFolder;
  }

  public TrustedProxies getTrustedProxies() {
    return trustedProxies;
  }

  /** The largest note content, in UTF-8 bytes. */
  public long getMaxNoteSize() {
    return maxNoteSize;
  }

  /**
   * The largest API request body: room for a note at {@link #getMaxNoteSize()} even with JSON
   * escaping doubling it (newlines, quotes), plus the rest of the request.
   */
  public long getMaxRequestBytes() {
    return 2 * maxNoteSize + 64 * 1024;
  }

  public boolean isSessionCookieSecure() {
    return config.isSecureCookie();
  }

  /** @return the user's credential, null if there is no such user. */
  public synchronized UserCredential getUser(String username) {
    reloadCredentialsIfChanged();
    return username == null ? null : users.get(username);
  }

  private void reloadCredentialsIfChanged() {
    if (credentialsFile.lastModified() == credentialsLastModified && credentialsFile.length() == credentialsLength) return;
    try {
      loadCredentials();
      LOGGER.info(credentialsFile + " changed: " + users.size() + " user(s) loaded");
    } catch (IOException | RuntimeException e) {
      // Not retried until the file changes again.
      credentialsLastModified = credentialsFile.lastModified();
      credentialsLength = credentialsFile.length();
      LOGGER.log(Level.SEVERE, credentialsFile + " changed but could not be loaded, keeping the previous users: " + e.getMessage());
    }
  }

  private void loadCredentials() throws IOException {
    long lastModified = credentialsFile.lastModified();
    long length = credentialsFile.length();
    CredentialsFile parsed;
    try (Reader reader = Files.newBufferedReader(credentialsFile.toPath(), StandardCharsets.UTF_8)) {
      parsed = new Gson().fromJson(reader, CredentialsFile.class);
    } catch (JsonParseException e) {
      throw new IllegalArgumentException("invalid JSON: " + e.getMessage(), e);
    }
    Map<String, UserCredential> loaded = new HashMap<>();
    if (parsed != null && parsed.getUsers() != null) {
      for (UserCredential user : parsed.getUsers()) {
        String username = user.getUsername();
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
          throw new IllegalArgumentException("invalid username \"" + username + "\": must match " + USERNAME_PATTERN.pattern());
        }
        if (user.getPassword() == null || user.getPassword().getHash() == null || user.getPassword().getSalt() == null) {
          throw new IllegalArgumentException("user \"" + username + "\" has no password hash and salt");
        }
        if (loaded.put(username, user) != null) {
          throw new IllegalArgumentException("user \"" + username + "\" is declared twice");
        }
      }
    }
    users = Map.copyOf(loaded);
    credentialsLastModified = lastModified;
    credentialsLength = length;
  }
}
