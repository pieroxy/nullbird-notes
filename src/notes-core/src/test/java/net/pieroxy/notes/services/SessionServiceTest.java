package net.pieroxy.notes.services;

import net.pieroxy.notes.config.Configuration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionServiceTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private final MovableClock clock = new MovableClock();
  private ServiceProvider provider;

  private static class MovableClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  @Before
  public void setUp() {
    SettingsService settings = new SettingsService(new Configuration(), new File(tmp.getRoot(), "credentials.json"), tmp.getRoot());
    provider = new ServiceProvider(settings, new SessionService(), new ClientGuardService(), new NoteStoreService(), new NoteService(),
        new WebServerService());
  }

  /** A service as after a server (re)start: loaded from the data folder. */
  private SessionService started() {
    SessionService sessions = new SessionService(clock);
    sessions.init(provider.scopedFor(sessions));
    return sessions;
  }

  private Path file() {
    return tmp.getRoot().toPath().resolve("sessions.json");
  }

  @Test
  public void aSessionSurvivesARestartWithItsMetadata() {
    SessionService sessions = started();
    String id = sessions.create("alice", "Firefox");
    clock.advance(Duration.ofHours(1));
    sessions.resume(id);
    sessions.destroy();

    SessionService.Session session = started().get(id);
    assertNotNull(session);
    assertEquals("alice", session.getUsername());
    assertEquals("Firefox", session.getUserAgent());
    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), session.getCreatedAt());
    assertEquals(Instant.parse("2026-01-01T01:00:00Z"), session.getLastLoginAt());
    assertEquals(Instant.parse("2026-01-01T01:00:00Z"), session.getLastUsedAt());
  }

  @Test
  public void aLoginIsWrittenAtOnce() {
    String id = started().create("alice", null);

    assertNotNull(started().get(id));
  }

  @Test
  public void aLogoutIsWrittenAtOnce() {
    SessionService sessions = started();
    String id = sessions.create("alice", null);
    sessions.invalidate(id);

    assertNull(started().get(id));
  }

  @Test
  public void theFileHoldsNoSessionId() throws Exception {
    String id = started().create("alice", null);

    assertFalse(Files.readString(file()).contains(id));
  }

  @Test
  public void expiresThirtyDaysAfterTheLastUse() {
    SessionService sessions = started();
    String id = sessions.create("alice", null);
    clock.advance(Duration.ofDays(20));
    assertNotNull(sessions.use(id));
    clock.advance(Duration.ofDays(29));
    assertNotNull(sessions.get(id));
    clock.advance(Duration.ofDays(1));

    assertNull(sessions.get(id));
  }

  @Test
  public void aGetIsNotAUse() {
    SessionService sessions = started();
    String id = sessions.create("alice", null);
    clock.advance(Duration.ofDays(20));
    sessions.get(id);
    clock.advance(Duration.ofDays(10));

    assertNull(sessions.get(id));
  }

  @Test
  public void expiredSessionsAreNotLoaded() {
    SessionService sessions = started();
    String id = sessions.create("alice", null);
    clock.advance(SessionService.LIFETIME);

    assertNull(started().get(id));
  }

  @Test
  public void usesAreWrittenByTheFlush() {
    SessionService sessions = started();
    String id = sessions.create("alice", null);
    clock.advance(Duration.ofDays(20));
    sessions.use(id);
    sessions.flush();
    clock.advance(Duration.ofDays(20));

    assertNotNull(started().get(id));
  }

  @Test
  public void anUnreadableFileLogsEveryoneOut() throws Exception {
    Files.writeString(file(), "{not json");
    SessionService sessions = started();

    assertNull(sessions.get("anything"));
    String id = sessions.create("alice", null);
    assertNotNull(started().get(id));
  }

  @Test
  public void theCookieIsRefreshedAtMostDaily() {
    SessionService sessions = started();
    SessionService.Session session = sessions.get(sessions.create("alice", null));
    assertFalse(sessions.cookieRefreshDue(session));
    clock.advance(SessionService.COOKIE_REFRESH);
    assertTrue(sessions.cookieRefreshDue(session));
    assertFalse(sessions.cookieRefreshDue(session));
  }

  @Test
  public void theCookieIsRefreshedOnTheFirstUseAfterARestart() {
    String id = started().create("alice", null);
    SessionService sessions = started();

    assertTrue(sessions.cookieRefreshDue(sessions.use(id)));
  }

  @Test
  public void lastSeenIsTheLatestUseOfTheUsersLiveSessions() {
    SessionService sessions = started();
    assertNull(sessions.lastSeen("alice"));
    String laptop = sessions.create("alice", "laptop");
    clock.advance(Duration.ofHours(1));
    String phone = sessions.create("alice", "phone");
    Instant phoneLogin = clock.instant();
    clock.advance(Duration.ofHours(1));
    sessions.create("bob", "bob's");

    assertEquals(phoneLogin, sessions.lastSeen("alice"));
    clock.advance(Duration.ofHours(1));
    sessions.use(laptop);
    assertEquals(clock.instant(), sessions.lastSeen("alice"));

    sessions.invalidateAll("alice");
    assertNull(sessions.get(laptop));
    assertNull(sessions.get(phone));
    assertNull("no session left: not known", sessions.lastSeen("alice"));
    assertNotNull(sessions.lastSeen("bob"));
    clock.advance(SessionService.LIFETIME);
    assertNull("expired", sessions.lastSeen("bob"));
  }
}
