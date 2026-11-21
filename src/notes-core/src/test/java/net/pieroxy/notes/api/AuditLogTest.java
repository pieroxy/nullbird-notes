package net.pieroxy.notes.api;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertTrue;

/** Logins, password changes and admins' changes to users: each logged at INFO, with who and from where. */
public class AuditLogTest {
  /** Held: a logger nothing references can be collected, and its handler with it. */
  private static final Logger API_LOGGER = Logger.getLogger("net.pieroxy.notes.api");

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private final List<LogRecord> records = new CopyOnWriteArrayList<>();
  private final Handler capture = new Handler() {
    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
  };
  private TestServer server;

  @Before
  public void setUp() throws Exception {
    API_LOGGER.addHandler(capture);
    server = new TestServer(tmp.getRoot(), Map.of("alice", "alice-password", "bob", "bob-password"), Set.of("alice"));
  }

  @After
  public void tearDown() {
    server.close();
    API_LOGGER.removeHandler(capture);
  }

  private void assertLogged(String... parts) {
    for (LogRecord record : records) {
      if (record.getLevel() != Level.INFO) continue;
      boolean all = true;
      for (String part : parts) all &= record.getMessage().contains(part);
      if (all) return;
    }
    throw new AssertionError("No INFO record with " + List.of(parts) + " in " + records.stream().map(r -> r.getLevel() + " " + r.getMessage()).toList());
  }

  @Test
  public void loginsAndPasswordChanges() throws Exception {
    TestServer.Client bob = server.client().login("bob", "bob-password");
    assertLogged("User \"bob\" logged in from 127.0.0.1");

    bob.post("ChangePassword", "{\"currentPassword\":\"bob-password\",\"newPassword\":\"short\"}").result();
    assertLogged("\"bob\" from 127.0.0.1", "refused", "too short");
    bob.post("ChangePassword", "{\"currentPassword\":\"bob-password\",\"newPassword\":\"bob-password\"}").result();
    assertLogged("\"bob\" from 127.0.0.1", "refused", "the current one");
    bob.post("ChangePassword", "{\"currentPassword\":\"bob-password\",\"newPassword\":\"a much longer password\"}").result();
    assertLogged("User \"bob\" changed their password from 127.0.0.1");
  }

  @Test
  public void adminsChangesToUsersDoneOrRefused() throws Exception {
    TestServer.Client alice = server.client().login("alice", "alice-password");

    alice.post("AdminAddUser", "{\"newUsername\":\"carol\",\"email\":\"carol@example.com\",\"admin\":true}").result();
    assertLogged("Admin \"alice\" from 127.0.0.1 added user \"carol\", an admin, email carol@example.com");
    alice.post("AdminAddUser", "{\"newUsername\":\"bob\",\"admin\":false}").result();
    assertLogged("Admin \"alice\" from 127.0.0.1 was refused adding user \"bob\"", "already a user");

    alice.post("AdminSetAdmin", "{\"user\":\"bob\",\"admin\":true}").result();
    assertLogged("Admin \"alice\" from 127.0.0.1 made \"bob\" an admin");
    alice.post("AdminSetAdmin", "{\"user\":\"alice\",\"admin\":false}").result();
    assertLogged("Admin \"alice\" from 127.0.0.1 was refused making \"alice\" no longer an admin");

    alice.post("AdminResetPassword", "{\"user\":\"bob\"}").result();
    assertLogged("Admin \"alice\" from 127.0.0.1 reset the password of \"bob\"");
    alice.post("AdminResetPassword", "{\"user\":\"no\\nbody\"}").result();
    assertLogged("was refused resetting the password of \"no\\x0abody\"", "There's no user \"no\\x0abody\"");
    assertTrue("never a raw line break", records.stream().noneMatch(r -> r.getMessage().contains("no\nbody")));
  }
}
