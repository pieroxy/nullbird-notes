package net.pieroxy.notes.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.pieroxy.notes.mail.FakeSmtpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** AdminMailOut and AdminSaveMailOut through HTTP. */
public class AdminMailOutApiTest {
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

  private JsonObject savedMailOut() throws Exception {
    String json = Files.readString(new File(tmp.getRoot(), "config.json").toPath(), StandardCharsets.UTF_8);
    return JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("mailOut");
  }

  private static String save(String host, int port, String security, String username, String password, String from) {
    return "{\"host\":\"" + host + "\",\"port\":" + port + ",\"security\":\"" + security + "\",\"smtpUsername\":\"" + username + "\""
        + (password == null ? "" : ",\"password\":\"" + password + "\"") + ",\"from\":\"" + from + "\"}";
  }

  private String refusal(String body) throws Exception {
    JsonObject result = alice.post("AdminSaveMailOut", body).result();
    assertFalse(result.get("ok").getAsBoolean());
    return result.get("error").getAsString();
  }

  @Test
  public void onlyAdminsMayCall() throws Exception {
    TestServer.Client bob = server.client().login("bob", "bob-password");

    assertEquals(403, bob.get("AdminMailOut", "{}").status());
    assertEquals(403, bob.post("AdminSaveMailOut", save("smtp.example.com", 587, "STARTTLS", "", null, "notes@example.com")).status());
    assertFalse(new File(tmp.getRoot(), "config.json").exists());
  }

  @Test
  public void nothingSavedMeansNoMailWithDefaults() throws Exception {
    JsonObject mail = alice.get("AdminMailOut", "{}").result();

    assertEquals("", mail.get("host").getAsString());
    assertEquals("STARTTLS", mail.get("security").getAsString());
    assertEquals(587, mail.get("port").getAsInt());
    assertFalse(mail.get("passwordSet").getAsBoolean());
  }

  @Test
  public void savedBesideTheRestOfTheConfigThePasswordNeverSentBack() throws Exception {
    Files.writeString(new File(tmp.getRoot(), "config.json").toPath(), "{\"httpPort\": 9000}", StandardCharsets.UTF_8);

    JsonObject result = alice.post("AdminSaveMailOut", save("smtp.example.com", 465, "TLS", "notes", "s3cret", "notes@example.com")).result();

    assertTrue(result.get("ok").getAsBoolean());
    JsonObject saved = savedMailOut();
    assertEquals("smtp.example.com", saved.get("host").getAsString());
    assertEquals("TLS", saved.get("security").getAsString());
    assertEquals("s3cret", saved.get("password").getAsString());
    String json = Files.readString(new File(tmp.getRoot(), "config.json").toPath(), StandardCharsets.UTF_8);
    assertEquals(9000, JsonParser.parseString(json).getAsJsonObject().get("httpPort").getAsInt());
    JsonObject mail = alice.get("AdminMailOut", "{}").result();
    assertTrue(mail.get("passwordSet").getAsBoolean());
    assertFalse(mail.has("password"));
    assertEquals(465, mail.get("port").getAsInt());
  }

  @Test
  public void thePasswordStaysUnlessReplacedAndGoesWithTheUsername() throws Exception {
    alice.post("AdminSaveMailOut", save("smtp.example.com", 587, "STARTTLS", "notes", "first", "notes@example.com"));

    alice.post("AdminSaveMailOut", save("smtp2.example.com", 587, "STARTTLS", "notes", "", "notes@example.com"));
    assertEquals("first", savedMailOut().get("password").getAsString());
    alice.post("AdminSaveMailOut", save("smtp2.example.com", 587, "STARTTLS", "notes", "second", "notes@example.com"));
    assertEquals("second", savedMailOut().get("password").getAsString());
    alice.post("AdminSaveMailOut", save("smtp2.example.com", 587, "STARTTLS", "", "ignored", "notes@example.com"));
    assertFalse(savedMailOut().has("password"));
  }

  @Test
  public void badValuesAreRefusedAndNothingWritten() throws Exception {
    assertTrue(refusal(save("smtp.example.com:587", 587, "STARTTLS", "", null, "notes@example.com")).contains("without a port"));
    assertTrue(refusal(save("smtp.example.com", 70000, "STARTTLS", "", null, "notes@example.com")).contains("port"));
    assertTrue(refusal(save("smtp.example.com", 587, "STARTTLS", "", null, "Notes <notes@example.com>")).contains("email address"));
    assertTrue(refusal(save("smtp.example.com", 587, "STARTTLS", "notes", null, "notes@example.com")).contains("needs a password"));
    assertTrue(refusal(save("smtp.example.com", 587, "STARTTLS", "notes", "a\\r\\nRSET", "notes@example.com")).contains("line breaks"));
    assertFalse(new File(tmp.getRoot(), "config.json").exists());
  }

  @Test
  public void aTestMailGoesThroughTheSavedSettingsOrSaysWhyNot() throws Exception {
    JsonObject none = alice.post("AdminTestMailOut", "{\"to\":\"alice@example.com\"}").result();
    assertFalse(none.get("ok").getAsBoolean());
    assertEquals("No SMTP server is saved.", none.get("error").getAsString());

    try (FakeSmtpServer smtp = new FakeSmtpServer(null)) {
      smtp.replies.put("RCPT", "550 5.1.1 No such user");
      smtp.start();
      alice.post("AdminSaveMailOut", save("127.0.0.1", smtp.port(), "NONE", "", null, "notes@example.com"));

      JsonObject refused = alice.post("AdminTestMailOut", "{\"to\":\"nobody@example.com\"}").result();

      assertFalse(refused.get("ok").getAsBoolean());
      assertEquals("RCPT TO:<nobody@example.com> was refused: 550 5.1.1 No such user", refused.get("error").getAsString());
    }
    try (FakeSmtpServer smtp = new FakeSmtpServer(null)) {
      smtp.start();
      alice.post("AdminSaveMailOut", save("127.0.0.1", smtp.port(), "NONE", "", null, "notes@example.com"));

      JsonObject sent = alice.post("AdminTestMailOut", "{\"to\":\"alice@example.com\"}").result();

      assertTrue(sent.get("ok").getAsBoolean());
      assertTrue(smtp.message.contains("To: alice@example.com\r\n"));
    }
  }

  @Test
  public void noHostTurnsMailOffWhateverTheRest() throws Exception {
    JsonObject result = alice.post("AdminSaveMailOut", save("", 587, "STARTTLS", "", null, "")).result();

    assertTrue(result.get("ok").getAsBoolean());
    assertFalse("nothing changed from the defaults: nothing written", new File(tmp.getRoot(), "config.json").exists());
  }
}
