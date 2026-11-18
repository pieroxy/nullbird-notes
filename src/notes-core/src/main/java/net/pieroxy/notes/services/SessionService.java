package net.pieroxy.notes.services;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sessions, issued by LoginApi and carried by the session cookie. Not persisted: a
 * restart clears all sessions.
 */
public class SessionService implements Service {
  /** Also the session cookie's Max-Age. */
  public static final Duration LIFETIME = Duration.ofDays(30);

  private final Clock clock;
  // Session ID -> session.
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  public SessionService() {
    this(Clock.systemUTC());
  }

  SessionService(Clock clock) {
    this.clock = clock;
  }

  /** @return a new, unguessable session ID (a {@link UUID}), valid for {@link #LIFETIME}. */
  public String create(String username) {
    Instant now = clock.instant();
    sessions.values().removeIf(session -> session.isExpired(now));
    String id = UUID.randomUUID().toString();
    sessions.put(id, new Session(username, now.plus(LIFETIME)));
    return id;
  }

  /** @return the session, or null if unknown or expired. */
  public Session get(String sessionId) {
    if (sessionId == null) return null;
    Session session = sessions.get(sessionId);
    if (session == null) return null;
    if (session.isExpired(clock.instant())) {
      sessions.remove(sessionId, session);
      return null;
    }
    return session;
  }

  public void invalidate(String sessionId) {
    if (sessionId != null) sessions.remove(sessionId);
  }

  public static class Session {
    private final String username;
    private final Instant expiry;

    Session(String username, Instant expiry) {
      this.username = username;
      this.expiry = expiry;
    }

    boolean isExpired(Instant now) {
      return !now.isBefore(expiry);
    }

    public String getUsername() {
      return username;
    }
  }
}
