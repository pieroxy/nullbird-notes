package net.pieroxy.notes.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.pieroxy.notes.config.PasswordGenerator;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The Admin* endpoints through HTTP. */
public class AdminApiTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private TestServer server;
  private TestServer.Client alice;

  @Before
  public void setUp() throws Exception {
    server = new TestServer(tmp.getRoot(), Map.of("alice", "alice-password", "bob", "bob-password"), Set.of("alice"));
    alice = server.client().login("alice", "alice-password");
  }

  @After
  public void tearDown() {
    server.close();
  }

  private static JsonObject user(TestServer.Client client, String username) throws Exception {
    for (JsonElement user : client.get("AdminUsers", "{}").result().getAsJsonArray("users")) {
      if (user.getAsJsonObject().get("username").getAsString().equals(username)) return user.getAsJsonObject();
    }
    return null;
  }

  private JsonObject credentialsEntry(String username) throws Exception {
    JsonObject root = JsonParser.parseString(Files.readString(new File(tmp.getRoot(), "credentials.json").toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
    for (JsonElement user : root.getAsJsonArray("users")) {
      if (user.getAsJsonObject().get("username").getAsString().equals(username)) return user.getAsJsonObject();
    }
    return null;
  }

  private static String addUser(String username, String email, boolean admin) {
    JsonObject input = new JsonObject();
    input.addProperty("newUsername", username);
    if (email != null) input.addProperty("email", email);
    input.addProperty("admin", admin);
    return input.toString();
  }

  @Test
  public void onlyAdminsMayCall() throws Exception {
    TestServer.Client bob = server.client().login("bob", "bob-password");

    assertEquals(403, bob.get("AdminUsers", "{}").status());
    assertEquals(403, bob.post("AdminAddUser", addUser("eve", null, true)).status());
    assertEquals(403, bob.post("AdminSetAdmin", "{\"user\":\"bob\",\"admin\":true}").status());
    assertEquals(403, bob.post("AdminResetPassword", "{\"user\":\"alice\"}").status());
    assertEquals(401, server.client().get("AdminUsers", "{}").status());
    assertFalse(credentialsEntry("bob").get("admin").getAsBoolean());
  }

  @Test
  public void listsTheUsersWithWhenTheyWereLastSeen() throws Exception {
    JsonArray users = alice.get("AdminUsers", "{}").result().getAsJsonArray("users");

    assertEquals(2, users.size());
    JsonObject first = users.get(0).getAsJsonObject();
    assertEquals("alice", first.get("username").getAsString());
    assertTrue(first.get("admin").getAsBoolean());
    assertTrue(Math.abs(first.get("lastSeenAt").getAsLong() - System.currentTimeMillis()) < 60_000);
    assertFalse(first.has("createdAt"));
    JsonObject second = users.get(1).getAsJsonObject();
    assertEquals("bob", second.get("username").getAsString());
    assertFalse(second.get("admin").getAsBoolean());
    assertFalse("never logged in", second.has("lastSeenAt"));
  }

  @Test
  public void addsAUserWhoCanLogInWithThePasswordGiven() throws Exception {
    long before = System.currentTimeMillis();
    JsonObject result = alice.post("AdminAddUser", addUser("carol", " carol@example.com ", false)).result();

    assertTrue(result.toString(), result.get("ok").getAsBoolean());
    String password = result.get("password").getAsString();
    assertEquals(PasswordGenerator.LENGTH, password.length());
    server.client().login("carol", password);
    JsonObject carol = user(alice, "carol");
    assertEquals("carol@example.com", carol.get("email").getAsString());
    assertFalse(carol.get("admin").getAsBoolean());
    assertTrue(carol.get("createdAt").getAsLong() >= before - 1000);
    JsonObject entry = credentialsEntry("carol");
    assertFalse(entry.has("admin"));
    Instant.parse(entry.get("createdAt").getAsString());
  }

  @Test
  public void addsAnAdminWithoutEmail() throws Exception {
    JsonObject result = alice.post("AdminAddUser", addUser("dave", "", true)).result();

    assertTrue(result.toString(), result.get("ok").getAsBoolean());
    JsonObject dave = user(alice, "dave");
    assertTrue(dave.get("admin").getAsBoolean());
    assertFalse(dave.has("email"));
    assertTrue(server.client().login("dave", result.get("password").getAsString()).get("AdminUsers", "{}").status() == 200);
  }

  @Test
  public void refusesBadOrTakenUsernamesAndBadEmails() throws Exception {
    for (String input : new String[] {addUser("bob", null, false), addUser("Bob", null, false), addUser("../x", null, false),
        addUser("", null, false), addUser("eve", "not an email", false), addUser("eve", "eve@nowhere", false)}) {
      JsonObject result = alice.post("AdminAddUser", input).result();
      assertFalse(input, result.get("ok").getAsBoolean());
      assertTrue(input, result.has("error"));
      assertFalse(input, result.has("password"));
    }
    assertNull(user(alice, "eve"));
  }

  @Test
  public void refusesANameWhoseDataIsStillThere() throws Exception {
    Files.createDirectories(tmp.getRoot().toPath().resolve("data/users/frank"));

    JsonObject result = alice.post("AdminAddUser", addUser("frank", null, false)).result();

    assertFalse(result.get("ok").getAsBoolean());
    assertTrue(result.get("error").getAsString(), result.get("error").getAsString().contains("users/frank"));
    assertNull(user(alice, "frank"));
  }

  @Test
  public void resetsAPasswordAndLogsTheUserOutEverywhere() throws Exception {
    TestServer.Client bob = server.client().login("bob", "bob-password");

    JsonObject result = alice.post("AdminResetPassword", "{\"user\":\"bob\"}").result();

    assertTrue(result.toString(), result.get("ok").getAsBoolean());
    assertEquals(401, bob.get("ServerInfo", "{}").status());
    assertFalse(server.client().post("Login", "{\"login\":\"bob\",\"password\":\"bob-password\"}").result().get("ok").getAsBoolean());
    server.client().login("bob", result.get("password").getAsString());
  }

  @Test
  public void switchesTheAdminFlag() throws Exception {
    assertTrue(alice.post("AdminSetAdmin", "{\"user\":\"bob\",\"admin\":true}").result().get("ok").getAsBoolean());
    assertTrue(user(alice, "bob").get("admin").getAsBoolean());
    TestServer.Client bob = server.client().login("bob", "bob-password");
    assertEquals(200, bob.get("AdminUsers", "{}").status());

    assertTrue(alice.post("AdminSetAdmin", "{\"user\":\"bob\",\"admin\":false}").result().get("ok").getAsBoolean());
    assertFalse(credentialsEntry("bob").has("admin"));
    assertEquals("at once, same session", 403, bob.get("AdminUsers", "{}").status());
  }

  @Test
  public void anAdminCantChangeThemselvesHere() throws Exception {
    JsonObject demote = alice.post("AdminSetAdmin", "{\"user\":\"alice\",\"admin\":false}").result();
    JsonObject reset = alice.post("AdminResetPassword", "{\"user\":\"alice\"}").result();

    assertFalse(demote.get("ok").getAsBoolean());
    assertFalse(reset.get("ok").getAsBoolean());
    assertTrue(credentialsEntry("alice").get("admin").getAsBoolean());
    alice.login("alice", "alice-password");
  }

  @Test
  public void unknownUsersAreRefused() throws Exception {
    assertFalse(alice.post("AdminSetAdmin", "{\"user\":\"nobody\",\"admin\":true}").result().get("ok").getAsBoolean());
    assertFalse(alice.post("AdminResetPassword", "{\"user\":\"nobody\"}").result().get("ok").getAsBoolean());
  }
}
