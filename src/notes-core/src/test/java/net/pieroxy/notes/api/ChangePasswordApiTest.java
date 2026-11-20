package net.pieroxy.notes.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.pieroxy.notes.services.ClientGuardService;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChangePasswordApiTest {
  private static final Map<String, String> USERS = Map.of("alice", "alice-password", "bob", "bob-password");
  private static final String NEW_PASSWORD = "a new password, long enough";

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private TestServer server;
  private Path credentials;

  @Before
  public void setUp() throws Exception {
    server = new TestServer(tmp.getRoot(), USERS);
    credentials = tmp.getRoot().toPath().resolve("credentials.json");
  }

  @After
  public void tearDown() {
    server.close();
  }

  private static String input(String current, String next) {
    JsonObject input = new JsonObject();
    input.addProperty("currentPassword", current);
    input.addProperty("newPassword", next);
    return input.toString();
  }

  private boolean canLogIn(String username, String password) throws Exception {
    JsonObject input = new JsonObject();
    input.addProperty("login", username);
    input.addProperty("password", password);
    return server.client().post("Login", input.toString()).result().get("ok").getAsBoolean();
  }

  private JsonObject user(String username) throws Exception {
    JsonArray users = JsonParser.parseString(Files.readString(credentials)).getAsJsonObject().getAsJsonArray("users");
    for (var entry : users) {
      if (entry.getAsJsonObject().get("username").getAsString().equals(username)) return entry.getAsJsonObject();
    }
    throw new AssertionError("No user " + username);
  }

  @Test
  public void changesThePassword() throws Exception {
    TestServer.Client laptop = server.client().login("alice", "alice-password");

    JsonObject result = laptop.post("ChangePassword", input("alice-password", NEW_PASSWORD)).result();

    assertTrue(result.get("ok").getAsBoolean());
    assertNull(result.get("error"));
    assertFalse(canLogIn("alice", "alice-password"));
    assertTrue(canLogIn("alice", NEW_PASSWORD));
    assertTrue(canLogIn("bob", "bob-password"));
  }

  @Test
  public void logsOutTheOtherSessionsOnly() throws Exception {
    TestServer.Client laptop = server.client().login("alice", "alice-password");
    TestServer.Client phone = server.client().login("alice", "alice-password");
    TestServer.Client bob = server.client().login("bob", "bob-password");

    assertTrue(laptop.post("ChangePassword", input("alice-password", NEW_PASSWORD)).result().get("ok").getAsBoolean());

    assertEquals(200, laptop.get("ServerInfo", "{}").status());
    assertEquals(401, phone.get("ServerInfo", "{}").status());
    assertEquals(200, bob.get("ServerInfo", "{}").status());
  }

  @Test
  public void aWrongCurrentPasswordIsRefused() throws Exception {
    TestServer.Client laptop = server.client().login("alice", "alice-password");

    JsonObject result = laptop.post("ChangePassword", input("not it", NEW_PASSWORD)).result();

    assertFalse(result.get("ok").getAsBoolean());
    assertTrue(result.get("error").getAsString(), result.get("error").getAsString().contains("current password is wrong"));
    assertTrue(canLogIn("alice", "alice-password"));
    assertEquals(200, laptop.get("ServerInfo", "{}").status());
  }

  @Test
  public void wrongCurrentPasswordsCountAsFailedLogins() throws Exception {
    TestServer.Client laptop = server.client().login("alice", "alice-password");
    for (int i = 0; i < ClientGuardService.MAX_FAILED_LOGINS; i++) {
      assertFalse(laptop.post("ChangePassword", input("not it", NEW_PASSWORD)).result().get("ok").getAsBoolean());
    }

    assertEquals(429, laptop.post("ChangePassword", input("alice-password", NEW_PASSWORD)).status());
    // Logging in from that address is refused too: the same failures count.
    assertEquals(429, server.client().post("Login", "{\"login\":\"alice\",\"password\":\"alice-password\"}").status());
  }

  @Test
  public void aShortPasswordIsRefused() throws Exception {
    TestServer.Client laptop = server.client().login("alice", "alice-password");

    JsonObject result = laptop.post("ChangePassword", input("alice-password", "11 chars...")).result();

    assertFalse(result.get("ok").getAsBoolean());
    assertTrue(result.get("error").getAsString().contains("at least 12 characters"));
    assertTrue(canLogIn("alice", "alice-password"));
  }

  @Test
  public void twelveCharactersAreEnoughCountingCharactersNotBytes() throws Exception {
    TestServer.Client laptop = server.client().login("alice", "alice-password");
    String twelve = "éééééééééééé";

    assertTrue(laptop.post("ChangePassword", input("alice-password", twelve)).result().get("ok").getAsBoolean());
    assertTrue(canLogIn("alice", twelve));
  }

  @Test
  public void theSamePasswordIsRefused() throws Exception {
    TestServer.Client laptop = server.client().login("alice", "alice-password");

    JsonObject result = laptop.post("ChangePassword", input("alice-password", "alice-password")).result();

    assertFalse(result.get("ok").getAsBoolean());
    assertTrue(result.get("error").getAsString().contains("same"));
  }

  @Test
  public void onlyThePasswordChangesInCredentialsJson() throws Exception {
    // Fields this version doesn't know of (later ones', say) stay, and so do the other users.
    JsonObject root = JsonParser.parseString(Files.readString(credentials)).getAsJsonObject();
    for (var entry : root.getAsJsonArray("users")) {
      JsonObject user = entry.getAsJsonObject();
      if (user.get("username").getAsString().equals("alice")) user.addProperty("email", "alice@example.com");
    }
    root.addProperty("comment", "kept");
    Files.writeString(credentials, root.toString());
    JsonObject bobBefore = user("bob");
    JsonObject alicePasswordBefore = user("alice").getAsJsonObject("password");
    TestServer.Client laptop = server.client().login("alice", "alice-password");

    assertTrue(laptop.post("ChangePassword", input("alice-password", NEW_PASSWORD)).result().get("ok").getAsBoolean());

    JsonObject alice = user("alice");
    assertEquals("alice@example.com", alice.get("email").getAsString());
    assertFalse(alice.getAsJsonObject("password").equals(alicePasswordBefore));
    assertEquals(bobBefore, user("bob"));
    assertEquals("kept", JsonParser.parseString(Files.readString(credentials)).getAsJsonObject().get("comment").getAsString());
    assertTrue(new String(Files.readAllBytes(credentials), StandardCharsets.UTF_8).contains("\n  \"users\""));
  }

  @Test
  public void credentialsJsonKeepsItsPermissions() throws Exception {
    Assume.assumeTrue(credentials.getFileSystem().supportedFileAttributeViews().contains("posix"));
    Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rw-------"));
    TestServer.Client laptop = server.client().login("alice", "alice-password");

    assertTrue(laptop.post("ChangePassword", input("alice-password", NEW_PASSWORD)).result().get("ok").getAsBoolean());

    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(credentials)));
  }

  @Test
  public void requiresALogin() throws Exception {
    assertEquals(401, server.client().post("ChangePassword", input("alice-password", NEW_PASSWORD)).status());
    assertTrue(canLogIn("alice", "alice-password"));
  }
}
