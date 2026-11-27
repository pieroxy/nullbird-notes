package net.pieroxy.notes.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.pieroxy.notes.mail.FakeSmtpServer;
import net.pieroxy.notes.totp.Base32;
import net.pieroxy.notes.totp.Totp;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** AdminResetSecondFactor, and the 2FA AdminUsers shows, through HTTP. */
public class AdminResetSecondFactorTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private TestServer server;
  private TestServer.Client alice;
  private TestServer.Client bob;

  @Before
  public void setUp() throws Exception {
    server = new TestServer(tmp.getRoot(), Map.of("alice", "alice-password", "bob", "bob-password"), Set.of("alice"));
    alice = server.client().login("alice", "alice-password");
    bob = server.client().login("bob", "bob-password");
  }

  @After
  public void tearDown() {
    server.close();
  }

  private void mailOutTo(FakeSmtpServer smtp) throws Exception {
    Files.writeString(new File(tmp.getRoot(), "config.json").toPath(), "{\"mailOut\": {\"host\": \"127.0.0.1\", \"port\": " + smtp.port()
        + ", \"security\": \"NONE\", \"from\": \"notes@example.com\"}}", StandardCharsets.UTF_8);
  }

  /** Bob with codes mailed to bob@example.com and an app. */
  private void bobSetsUpBoth() throws Exception {
    try (FakeSmtpServer smtp = new FakeSmtpServer(null)) {
      smtp.start();
      mailOutTo(smtp);
      bob.post("SendMailFactorCode", "{\"address\":\"bob@example.com\",\"password\":\"bob-password\"}");
      Matcher matcher = Pattern.compile("Subject: Notes code: (\\d{6})").matcher(smtp.message);
      assertTrue(matcher.find());
      assertTrue(bob.post("ConfirmMailFactor", "{\"code\":\"" + matcher.group(1) + "\"}").result().get("ok").getAsBoolean());
    }
    String secret = bob.post("StartTotpFactor", "{\"password\":\"bob-password\"}").result().get("secret").getAsString();
    String code = Totp.code(Base32.decode(secret), Totp.step(Instant.now()));
    assertTrue(bob.post("ConfirmTotpFactor", "{\"code\":\"" + code + "\"}").result().get("ok").getAsBoolean());
  }

  private JsonObject listed(String username) throws Exception {
    for (JsonElement user : alice.get("AdminUsers", "{}").result().getAsJsonArray("users")) {
      if (user.getAsJsonObject().get("username").getAsString().equals(username)) return user.getAsJsonObject();
    }
    throw new AssertionError("no " + username);
  }

  @Test
  public void anAdminTurnsAnotherUsersOffAndTheyreTold() throws Exception {
    bobSetsUpBoth();
    assertTrue(listed("bob").get("mailFactor").getAsBoolean());
    assertTrue(listed("bob").get("totpFactor").getAsBoolean());
    assertFalse(listed("alice").get("totpFactor").getAsBoolean());

    try (FakeSmtpServer smtp = new FakeSmtpServer(null)) {
      smtp.start();
      mailOutTo(smtp);
      assertTrue(alice.post("AdminResetSecondFactor", "{\"user\":\"bob\"}").result().get("ok").getAsBoolean());

      assertTrue(smtp.message.contains("To: bob@example.com\r\n"));
      assertTrue(smtp.message.contains("Subject: Notes: two-step verification reset\r\n"));
    }
    assertFalse(listed("bob").get("mailFactor").getAsBoolean());
    assertFalse(listed("bob").get("totpFactor").getAsBoolean());
    String json = Files.readString(new File(tmp.getRoot(), "credentials.json").toPath(), StandardCharsets.UTF_8);
    for (JsonElement user : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("users")) assertFalse(user.getAsJsonObject().has("secondFactor"));
    assertTrue("their session stays", bob.get("Session", "{}").result().get("authenticated").getAsBoolean());
    assertTrue("the password alone logs in", server.client().post("Login", "{\"login\":\"bob\",\"password\":\"bob-password\"}").result().get("ok").getAsBoolean());
  }

  @Test
  public void notOnesOwnNorByANonAdmin() throws Exception {
    assertEquals("Change your own two-step verification in My Account.",
        alice.post("AdminResetSecondFactor", "{\"user\":\"alice\"}").result().get("error").getAsString());
    assertEquals("There's no user \"carol\".", alice.post("AdminResetSecondFactor", "{\"user\":\"carol\"}").result().get("error").getAsString());
    assertEquals(403, bob.post("AdminResetSecondFactor", "{\"user\":\"alice\"}").status());
  }
}
