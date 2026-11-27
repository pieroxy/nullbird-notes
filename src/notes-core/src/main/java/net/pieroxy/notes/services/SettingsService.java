package net.pieroxy.notes.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.config.CredentialsFile;
import net.pieroxy.notes.config.PasswordHash;
import net.pieroxy.notes.config.SecondFactor;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.utils.AtomicFiles;
import net.pieroxy.notes.webserver.TrustedProxies;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * config.json and credentials.json. credentials.json is re-read whenever it changes on disk, so
 * users can be added or removed without a restart. A change that fails to load is logged and
 * ignored: the users loaded last stay in effect. The server writes it too: a user's own password,
 * and what admins change.
 */
public class SettingsService implements Service {
  private final static Logger LOGGER = Logger.getLogger(SettingsService.class.getName());
  private static final Gson PRETTY_GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
  /** Usernames name the users' data folders: no path separators, no case-only differences. */
  public static final Pattern USERNAME_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

  private final Configuration config;
  private final File configFile;
  private final File credentialsFile;
  private final File dataFolder;
  private final TrustedProxies trustedProxies;
  private final long maxNoteSize;

  private Map<String, UserCredential> users = Map.of();
  private long credentialsLastModified;
  private long credentialsLength;

  /** {@link #SettingsService(Configuration, File, File, File)} with config.json beside credentials.json. */
  public SettingsService(Configuration config, File credentialsFile, File dataFolder) {
    this(config, new File(credentialsFile.getAbsoluteFile().getParentFile(), "config.json"), credentialsFile, dataFolder);
  }

  /**
   * @param config what config.json said at startup: in effect until the next one.
   * @throws IllegalArgumentException if config.json's trustedProxies or maxNoteSizeMb is invalid.
   */
  public SettingsService(Configuration config, File configFile, File credentialsFile, File dataFolder) {
    this.config = config;
    this.configFile = configFile;
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

  /** As config.json was at startup: in effect until the next one, whatever it says now. */
  public Configuration getConfig() {
    return config;
  }

  public File getConfigFile() {
    return configFile;
  }

  /**
   * config.json as it is now on disk: what the next startup will use. All defaults if it doesn't exist.
   *
   * @throws IOException if it can't be read or parsed.
   */
  public synchronized Configuration readSavedConfig() throws IOException {
    if (!configFile.exists()) return new Configuration();
    try (Reader reader = Files.newBufferedReader(configFile.toPath(), StandardCharsets.UTF_8)) {
      Configuration saved = new Gson().fromJson(reader, Configuration.class);
      return saved == null ? new Configuration() : saved;
    } catch (JsonParseException e) {
      throw new IOException("invalid JSON in " + configFile + ": " + e.getMessage(), e);
    }
  }

  /**
   * Changes config.json, as read from disk now, created if it doesn't exist. Only what {@code change}
   * touches changes: other fields, known to this version or not, stay. Takes effect at the next startup.
   *
   * @throws IOException if it can't be read, parsed or written.
   */
  public synchronized void updateSavedConfig(Consumer<JsonObject> change) throws IOException {
    JsonObject root = new JsonObject();
    if (configFile.exists()) {
      JsonElement parsed;
      try {
        parsed = JsonParser.parseString(Files.readString(configFile.toPath(), StandardCharsets.UTF_8));
      } catch (JsonParseException e) {
        throw new IOException("invalid JSON in " + configFile + ": " + e.getMessage(), e);
      }
      if (!parsed.isJsonObject()) throw new IOException(configFile + " isn't a JSON object");
      root = parsed.getAsJsonObject();
    }
    change.accept(root);
    AtomicFiles.write(configFile.toPath(), (PRETTY_GSON.toJson(root) + "\n").getBytes(StandardCharsets.UTF_8));
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
   * The largest API request body with a session (see ApiServlet): room for a note at {@link #getMaxNoteSize()} even with JSON
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

  /** @return every user, by username. */
  public synchronized List<UserCredential> listUsers() {
    reloadCredentialsIfChanged();
    return users.values().stream().sorted(Comparator.comparing(UserCredential::getUsername)).toList();
  }

  /**
   * Replaces the user's password hash in credentials.json.
   *
   * @throws IllegalArgumentException if there's no such user.
   * @throws IOException if credentials.json can't be read, parsed or written.
   */
  public synchronized void setPassword(String username, PasswordHash password) throws IOException {
    updateUser(username, user -> user.add("password", PRETTY_GSON.toJsonTree(password)));
  }

  /**
   * Sets or clears the user's admin flag in credentials.json. Cleared, the flag is removed: absent
   * means not admin.
   *
   * @throws IllegalArgumentException if there's no such user.
   * @throws IOException if credentials.json can't be read, parsed or written.
   */
  public synchronized void setAdmin(String username, boolean admin) throws IOException {
    updateUser(username, user -> {
      if (admin) user.addProperty("admin", true);
      else user.remove("admin");
    });
  }

  /**
   * Sets the user's mail second factor in credentials.json, replacing any previous address. Preferred
   * if nothing else is.
   *
   * @throws IllegalArgumentException if there's no such user.
   * @throws IOException if credentials.json can't be read, parsed or written.
   */
  public synchronized void setMailFactor(String username, String address, Instant confirmedAt) throws IOException {
    updateUser(username, user -> {
      if (!(user.get("secondFactor") instanceof JsonObject)) user.add("secondFactor", new JsonObject());
      JsonObject factors = user.getAsJsonObject("secondFactor");
      JsonObject mail = new JsonObject();
      mail.addProperty("address", address);
      mail.addProperty("confirmedAt", confirmedAt.toString());
      factors.add(SecondFactor.MAIL, mail);
      if (!factors.has("preferred")) factors.addProperty("preferred", SecondFactor.MAIL);
    });
  }

  /**
   * Removes the user's mail second factor from credentials.json. The TOTP, if any, becomes preferred;
   * without one, "secondFactor" goes.
   *
   * @throws IllegalArgumentException if there's no such user.
   * @throws IOException if credentials.json can't be read, parsed or written.
   */
  public synchronized void removeMailFactor(String username) throws IOException {
    updateUser(username, user -> {
      if (!(user.get("secondFactor") instanceof JsonObject factors)) return;
      factors.remove(SecondFactor.MAIL);
      if (factors.has(SecondFactor.TOTP)) factors.addProperty("preferred", SecondFactor.TOTP);
      else user.remove("secondFactor");
    });
  }

  /**
   * Sets the user's TOTP second factor in credentials.json, replacing any previous secret. Preferred
   * if nothing else is.
   *
   * @param secret base32.
   * @param lastUsedStep the step of the code that confirmed it.
   * @throws IllegalArgumentException if there's no such user.
   * @throws IOException if credentials.json can't be read, parsed or written.
   */
  public synchronized void setTotpFactor(String username, String secret, Instant confirmedAt, long lastUsedStep) throws IOException {
    updateUser(username, user -> {
      if (!(user.get("secondFactor") instanceof JsonObject)) user.add("secondFactor", new JsonObject());
      JsonObject factors = user.getAsJsonObject("secondFactor");
      JsonObject totp = new JsonObject();
      totp.addProperty("secret", secret);
      totp.addProperty("confirmedAt", confirmedAt.toString());
      totp.addProperty("lastUsedStep", lastUsedStep);
      factors.add(SecondFactor.TOTP, totp);
      if (!factors.has("preferred")) factors.addProperty("preferred", SecondFactor.TOTP);
    });
  }

  /**
   * Removes the user's TOTP second factor from credentials.json. The mail one, if any, becomes
   * preferred; without one, "secondFactor" goes.
   *
   * @throws IllegalArgumentException if there's no such user.
   * @throws IOException if credentials.json can't be read, parsed or written.
   */
  public synchronized void removeTotpFactor(String username) throws IOException {
    updateUser(username, user -> {
      if (!(user.get("secondFactor") instanceof JsonObject factors)) return;
      factors.remove(SecondFactor.TOTP);
      if (factors.has(SecondFactor.MAIL)) factors.addProperty("preferred", SecondFactor.MAIL);
      else user.remove("secondFactor");
    });
  }

  /**
   * Records a TOTP code of {@code step} as used, unless one of it or later already is: of two logins
   * with the same code at once, one only gets through.
   *
   * @return false if the step was used already, or the user has no TOTP factor anymore.
   * @throws IOException if credentials.json can't be read, parsed or written.
   */
  public synchronized boolean useTotpStep(String username, long step) throws IOException {
    UserCredential user = getUser(username);
    if (user == null || user.totpFactor() == null || step <= user.totpFactor().getLastUsedStep()) return false;
    updateUser(username, entry -> entry.getAsJsonObject("secondFactor").getAsJsonObject(SecondFactor.TOTP).addProperty("lastUsedStep", step));
    return true;
  }

  /**
   * Sets which second factor is asked first.
   *
   * @param method {@link SecondFactor#MAIL} or {@link SecondFactor#TOTP}.
   * @throws IllegalArgumentException if there's no such user, or they don't have that factor.
   * @throws IOException if credentials.json can't be read, parsed or written.
   */
  public synchronized void setPreferredFactor(String username, String method) throws IOException {
    updateUser(username, user -> {
      if (!(user.get("secondFactor") instanceof JsonObject factors) || !factors.has(method)) {
        throw new IllegalArgumentException("user \"" + username + "\" has no " + method + " second factor");
      }
      factors.addProperty("preferred", method);
    });
  }

  /**
   * Adds a user at the end of credentials.json.
   *
   * @param email null for none.
   * @throws IllegalArgumentException if the username is invalid or taken.
   * @throws IOException if credentials.json can't be read, parsed or written.
   */
  public synchronized void addUser(String username, String email, boolean admin, PasswordHash password, Instant createdAt) throws IOException {
    if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
      throw new IllegalArgumentException("invalid username \"" + username + "\": must match " + USERNAME_PATTERN.pattern());
    }
    JsonObject root = readCredentialsTree();
    if (!(root.get("users") instanceof JsonArray)) root.add("users", new JsonArray());
    JsonArray entries = root.getAsJsonArray("users");
    if (findUser(entries, username) != null) throw new IllegalArgumentException("user \"" + username + "\" already exists");
    JsonObject user = new JsonObject();
    user.addProperty("username", username);
    if (email != null) user.addProperty("email", email);
    if (admin) user.addProperty("admin", true);
    user.addProperty("createdAt", createdAt.toString());
    user.add("password", PRETTY_GSON.toJsonTree(password));
    entries.add(user);
    writeCredentialsTree(root);
  }

  /**
   * Changes the user's entry in credentials.json, as read from disk now. Only what {@code change}
   * touches changes: the other users, and the user's other fields (known to this version or not), stay.
   */
  private void updateUser(String username, Consumer<JsonObject> change) throws IOException {
    JsonObject root = readCredentialsTree();
    JsonObject user = root.get("users") instanceof JsonArray entries ? findUser(entries, username) : null;
    if (user == null) throw new IllegalArgumentException("no user \"" + username + "\" in " + credentialsFile);
    change.accept(user);
    writeCredentialsTree(root);
  }

  private JsonObject readCredentialsTree() throws IOException {
    JsonElement root;
    try {
      root = JsonParser.parseString(Files.readString(credentialsFile.toPath(), StandardCharsets.UTF_8));
    } catch (JsonParseException e) {
      throw new IOException("invalid JSON: " + e.getMessage(), e);
    }
    if (!root.isJsonObject()) throw new IOException("not a JSON object");
    return root.getAsJsonObject();
  }

  /** Written atomically, then loaded: what's written is what's in effect, or the write fails. */
  private void writeCredentialsTree(JsonObject root) throws IOException {
    AtomicFiles.write(credentialsFile.toPath(), (PRETTY_GSON.toJson(root) + "\n").getBytes(StandardCharsets.UTF_8));
    loadCredentials();
  }

  private static JsonObject findUser(JsonArray entries, String username) {
    for (JsonElement entry : entries) {
      if (!entry.isJsonObject()) continue;
      JsonElement name = entry.getAsJsonObject().get("username");
      if (name != null && name.isJsonPrimitive() && username.equals(name.getAsString())) return entry.getAsJsonObject();
    }
    return null;
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
        try {
          user.createdAtInstant();
        } catch (DateTimeParseException e) {
          throw new IllegalArgumentException("user \"" + username + "\" has an invalid createdAt, not ISO 8601: \"" + user.getCreatedAt() + "\"");
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
