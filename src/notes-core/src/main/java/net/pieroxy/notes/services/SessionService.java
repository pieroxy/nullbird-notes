package net.pieroxy.notes.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.pieroxy.notes.utils.AtomicFiles;
import net.pieroxy.notes.utils.Hashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sessions, issued by LoginApi and carried by the session cookie. A session expires {@link #LIFETIME}
 * after its last use.
 * <p>
 * Persisted to {@code <dataFolder>/sessions.json}, keyed by the SHA-256 of the session ID: the file
 * alone can't be used to take over a session. Logins and logouts are written at once; uses at most
 * every {@link #FLUSH_PERIOD} and on shutdown. An unreadable file is logged and ignored, which logs
 * everyone out.
 */
public class SessionService implements Service {
  private final static Logger LOGGER = Logger.getLogger(SessionService.class.getName());
  /** Also the session cookie's Max-Age. */
  public static final Duration LIFETIME = Duration.ofDays(30);
  static final Duration FLUSH_PERIOD = Duration.ofMinutes(5);
  /** How often the cookie's Max-Age is pushed back, so a session in use never loses its cookie. */
  static final Duration COOKIE_REFRESH = Duration.ofDays(1);
  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

  private final Clock clock;
  // SHA-256 of the session ID -> session.
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final Object writeLock = new Object();
  private volatile boolean dirty;
  private Path file;
  private ScheduledExecutorService flusher;

  public SessionService() {
    this(Clock.systemUTC());
  }

  SessionService(Clock clock) {
    this.clock = clock;
  }

  @Override
  public List<Class<? extends Service>> getDependencies() {
    return List.of(SettingsService.class);
  }

  @Override
  public void init(IServiceProvider serviceProvider) {
    file = serviceProvider.getSettingsService().getDataFolder().toPath().resolve("sessions.json");
    load();
  }

  @Override
  public void start() {
    flusher = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "session-flush");
      thread.setDaemon(true);
      return thread;
    });
    long period = FLUSH_PERIOD.toMillis();
    flusher.scheduleAtFixedRate(this::flush, period, period, TimeUnit.MILLISECONDS);
  }

  @Override
  public void destroy() {
    if (flusher != null) flusher.shutdownNow();
    flush();
  }

  /** @return a new, unguessable session ID (a {@link UUID}). */
  public String create(String username, String userAgent) {
    Instant now = clock.instant();
    String id = UUID.randomUUID().toString();
    Session session = new Session(username, userAgent, now, now, now);
    // LoginApi sets the cookie.
    session.cookieRefreshedAt = now;
    sessions.put(Hashes.sha256(id), session);
    save();
    return id;
  }

  /** @return the session, or null if unknown or expired. Doesn't count as a use. */
  public Session get(String sessionId) {
    if (sessionId == null) return null;
    String key = Hashes.sha256(sessionId);
    Session session = sessions.get(key);
    if (session == null) return null;
    if (session.isExpired(clock.instant())) {
      if (sessions.remove(key, session)) dirty = true;
      return null;
    }
    return session;
  }

  /** {@link #get}, and records a use of the session, which pushes its expiry back. */
  public Session use(String sessionId) {
    Session session = get(sessionId);
    if (session != null) {
      session.lastUsedAt = clock.instant();
      dirty = true;
    }
    return session;
  }

  /** {@link #use}, for the webapp opening on an existing session: also its last login. */
  public Session resume(String sessionId) {
    Session session = use(sessionId);
    if (session != null) session.lastLoginAt = session.lastUsedAt;
    return session;
  }

  /** @return whether the session cookie's Max-Age is due to be refreshed; if so, it's considered done. */
  public boolean cookieRefreshDue(Session session) {
    Instant now = clock.instant();
    Instant refreshed = session.cookieRefreshedAt;
    if (refreshed != null && now.isBefore(refreshed.plus(COOKIE_REFRESH))) return false;
    session.cookieRefreshedAt = now;
    return true;
  }

  public void invalidate(String sessionId) {
    if (sessionId != null && sessions.remove(Hashes.sha256(sessionId)) != null) save();
  }

  /** Ends every session of {@code username} but {@code keptSessionId}: logged out everywhere else. */
  public void invalidateOthers(String username, String keptSessionId) {
    String kept = keptSessionId == null ? null : Hashes.sha256(keptSessionId);
    if (sessions.entrySet().removeIf(entry -> entry.getValue().getUsername().equals(username) && !entry.getKey().equals(kept))) save();
  }

  /** Writes the sessions if anything changed since the last write. */
  void flush() {
    if (dirty) save();
  }

  private void save() {
    if (file == null) return;
    synchronized (writeLock) {
      dirty = false;
      Instant now = clock.instant();
      sessions.values().removeIf(session -> session.isExpired(now));
      List<StoredSession> stored = new ArrayList<>();
      sessions.forEach((key, session) -> stored.add(new StoredSession(key, session)));
      try {
        AtomicFiles.write(file, GSON.toJson(new SessionsFile(stored)).getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        dirty = true;
        LOGGER.log(Level.SEVERE, "Could not write " + file + ": " + e.getMessage(), e);
      }
    }
  }

  private void load() {
    if (!Files.exists(file)) return;
    try {
      SessionsFile parsed = GSON.fromJson(Files.readString(file), SessionsFile.class);
      Instant now = clock.instant();
      if (parsed != null && parsed.sessions != null) {
        for (StoredSession stored : parsed.sessions) {
          Session session = stored.toSession();
          if (stored.idHash != null && session != null && !session.isExpired(now)) sessions.put(stored.idHash, session);
        }
      }
      LOGGER.info(sessions.size() + " session(s) loaded from " + file);
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, file + " could not be read, everyone will have to log in again: " + e.getMessage(), e);
    }
  }

  public static class Session {
    private final String username;
    private final String userAgent;
    private final Instant createdAt;
    private volatile Instant lastLoginAt;
    private volatile Instant lastUsedAt;
    /** Not persisted: after a restart, the first use refreshes the cookie. */
    private volatile Instant cookieRefreshedAt;

    Session(String username, String userAgent, Instant createdAt, Instant lastLoginAt, Instant lastUsedAt) {
      this.username = username;
      this.userAgent = userAgent;
      this.createdAt = createdAt;
      this.lastLoginAt = lastLoginAt;
      this.lastUsedAt = lastUsedAt;
    }

    boolean isExpired(Instant now) {
      return !now.isBefore(lastUsedAt.plus(LIFETIME));
    }

    public String getUsername() {
      return username;
    }

    /** Null if the login request carried none. */
    public String getUserAgent() {
      return userAgent;
    }

    public Instant getCreatedAt() {
      return createdAt;
    }

    /** The login, or the last time the webapp opened on this session since. */
    public Instant getLastLoginAt() {
      return lastLoginAt;
    }

    public Instant getLastUsedAt() {
      return lastUsedAt;
    }
  }

  private static class SessionsFile {
    List<StoredSession> sessions;

    SessionsFile(List<StoredSession> sessions) {
      this.sessions = sessions;
    }
  }

  /** Times in epoch milliseconds. */
  private static class StoredSession {
    String idHash;
    String username;
    String userAgent;
    Long createdAt;
    Long lastLoginAt;
    Long lastUsedAt;

    StoredSession(String idHash, Session session) {
      this.idHash = idHash;
      this.username = session.username;
      this.userAgent = session.userAgent;
      this.createdAt = session.createdAt.toEpochMilli();
      this.lastLoginAt = session.lastLoginAt.toEpochMilli();
      this.lastUsedAt = session.lastUsedAt.toEpochMilli();
    }

    /** @return null if incomplete. */
    Session toSession() {
      if (username == null || createdAt == null || lastLoginAt == null || lastUsedAt == null) return null;
      return new Session(username, userAgent, Instant.ofEpochMilli(createdAt), Instant.ofEpochMilli(lastLoginAt),
          Instant.ofEpochMilli(lastUsedAt));
    }
  }
}
