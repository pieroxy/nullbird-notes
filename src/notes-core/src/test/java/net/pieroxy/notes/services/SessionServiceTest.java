package net.pieroxy.notes.services;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.config.CredentialsFile;
import net.pieroxy.notes.config.PasswordHash;
import net.pieroxy.notes.config.PasswordHasher;
import net.pieroxy.notes.config.UserCredential;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionServiceTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private static final PasswordHash ALICE = PasswordHasher.hash("alice-password");
  private static final PasswordHash BOB = PasswordHasher.hash("bob-password");

  private final MovableClock clock = new MovableClock();
  private ServiceProvider provider;
  private long credentialsWrittenAt = 1_700_000_000_000L;

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
  public void setUp() throws IOException {
    writeUsers(Map.of("alice", ALICE, "bob", BOB));
    SettingsService settings = new SettingsService(new Configuration(), credentialsFile(), tmp.getRoot());
    settings.start();
    provider = new ServiceProvider(settings, new SessionService(), new ClientGuardService(), new NoteStoreService(), new NoteService(),
        new ImportService(), new FontService(), new WebServerService());
  }

  /** A service as after a server (re)start: loaded from the data folder. */
  private SessionService started() {
    SessionService sessions = new SessionService(clock);
    sessions.init(provider.scopedFor(sessions));
    return sessions;
  }

  private File credentialsFile() {
    return new File(tmp.getRoot(), "credentials.json");
  }

  /** Replaces credentials.json, its modification time moved on so that it's seen as changed. */
  private void writeUsers(Map<String, PasswordHash> passwords) throws IOException {
    List<UserCredential> users = new ArrayList<>();
    passwords.forEach((username, password) -> {
      UserCredential user = new UserCredential();
      user.setUsername(username);
      user.setPassword(password);
      users.add(user);
    });
    CredentialsFile file = new CredentialsFile();
    file.setUsers(users);
    Files.writeString(credentialsFile().toPath(), new Gson().toJson(file));
    credentialsWrittenAt += 1000;
    assertTrue(credentialsFile().setLastModified(credentialsWrittenAt));
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

  @Test
  public void aUserRemovedFromCredentialsIsLoggedOutAtOnce() throws Exception {
    SessionService sessions = started();
    String alice = sessions.create("alice", null);
    String bob = sessions.create("bob", null);

    writeUsers(Map.of("bob", BOB));

    assertNull(sessions.get(alice));
    assertNotNull(sessions.get(bob));
  }

  @Test
  public void anotherPersonUnderTheSameNameDoesNotGetTheSessions() throws Exception {
    SessionService sessions = started();
    String id = sessions.create("bob", null);
    writeUsers(Map.of("alice", ALICE));
    writeUsers(Map.of("alice", ALICE, "bob", PasswordHasher.hash("the new bob's")));

    assertNull(sessions.get(id));
  }

  @Test
  public void aPasswordResetByHandLogsTheUserOut() throws Exception {
    SessionService sessions = started();
    String id = sessions.create("alice", null);

    writeUsers(Map.of("alice", PasswordHasher.hash("alice-password"), "bob", BOB));

    assertNull("the same password, hashed again: a new salt", sessions.get(id));
  }

  @Test
  public void theSessionChangingThePasswordCarriesOnAcrossARestart() throws Exception {
    SessionService sessions = started();
    String changing = sessions.create("alice", null);
    String other = sessions.create("alice", null);
    PasswordHash changed = PasswordHasher.hash("a new password");

    sessions.expectPassword(changing, changed);
    assertNotNull("credentials.json not written yet", sessions.get(changing));
    writeUsers(Map.of("alice", changed, "bob", BOB));

    assertNotNull(sessions.get(changing));
    assertNull(sessions.get(other));
    sessions.destroy();
    assertNotNull(started().get(changing));
  }

  @Test
  public void aSessionFromBeforeFingerprintsTakesItsUsersOnItsNextUse() throws Exception {
    String id = started().create("alice", null);
    JsonObject stored = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
    for (JsonElement session : stored.getAsJsonArray("sessions")) session.getAsJsonObject().remove("credential");
    Files.writeString(file(), stored.toString());

    SessionService sessions = started();
    assertNotNull("not logged out", sessions.get(id));
    writeUsers(Map.of("alice", PasswordHasher.hash("changed"), "bob", BOB));
    assertNull("from then on, bound to the password", sessions.get(id));
  }

  @Test
  public void sessionsOfRemovedUsersLeaveTheFileAtStart() throws Exception {
    SessionService sessions = started();
    sessions.create("alice", null);
    sessions.create("bob", null);
    sessions.destroy();
    writeUsers(Map.of("bob", BOB));

    SessionService restarted = started();
    restarted.start();
    restarted.destroy();

    assertFalse(Files.readString(file()).contains("\"alice\""));
    assertTrue(Files.readString(file()).contains("\"bob\""));
  }
}
