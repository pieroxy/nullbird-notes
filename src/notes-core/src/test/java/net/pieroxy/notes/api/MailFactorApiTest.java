package net.pieroxy.notes.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.pieroxy.notes.mail.FakeSmtpServer;
import net.pieroxy.notes.services.SecondFactorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** SecondFactor, SendMailFactorCode, ConfirmMailFactor and RemoveMailFactor through HTTP. */
public class MailFactorApiTest {
  private static final Pattern CODE = Pattern.compile("Subject: Notes code: (\\d{6})\r\n");

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private TestServer server;
  private TestServer.Client bob;

  @Before
  public void setUp() throws Exception {
    server = new TestServer(tmp.getRoot(), Map.of("bob", "bob-password"));
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

  private JsonObject bobsEntry() throws Exception {
    String json = Files.readString(new File(tmp.getRoot(), "credentials.json").toPath(), StandardCharsets.UTF_8);
    return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("users").get(0).getAsJsonObject();
  }

  private JsonObject sendCode(String address, String password) throws Exception {
    return bob.post("SendMailFactorCode", "{\"address\":\"" + address + "\",\"password\":\"" + password + "\"}").result();
  }

  private JsonObject confirm(String code) throws Exception {
    return bob.post("ConfirmMailFactor", "{\"code\":\"" + code + "\"}").result();
  }

  /** Sends a code to {@code address} through a fake server: the code. */
  private String codeSentTo(String address) throws Exception {
    try (FakeSmtpServer smtp = new FakeSmtpServer(null)) {
      smtp.start();
      mailOutTo(smtp);
      assertTrue(sendCode(address, "bob-password").get("ok").getAsBoolean());
      Matcher matcher = CODE.matcher(smtp.message);
      assertTrue(smtp.message, matcher.find());
      assertTrue(smtp.message.contains("To: " + address + "\r\n"));
      return matcher.group(1);
    }
  }

  @Test
  public void withoutMailOutNothingCanBeSetUp() throws Exception {
    JsonObject status = bob.get("SecondFactor", "{}").result();
    assertFalse(status.get("mailAvailable").getAsBoolean());
    assertFalse(status.has("mail"));

    JsonObject result = sendCode("bob@example.com", "bob-password");

    assertFalse(result.get("ok").getAsBoolean());
    assertTrue(result.get("error").getAsString(), result.get("error").getAsString().contains("administrator hasn't set it up"));
  }

  @Test
  public void theCodeMailedProvesTheAddress() throws Exception {
    String code = codeSentTo("bob@example.com");
    JsonObject pending = bob.get("SecondFactor", "{}").result();
    assertTrue(pending.get("mailAvailable").getAsBoolean());
    assertEquals("bob@example.com", pending.get("pendingAddress").getAsString());
    assertFalse(bobsEntry().has("secondFactor"));

    String wrong = code.equals("000000") ? "111111" : "000000";
    assertEquals("This code is wrong.", confirm(wrong).get("error").getAsString());
    assertTrue(confirm(code.substring(0, 3) + " " + code.substring(3)).get("ok").getAsBoolean());

    JsonObject factors = bobsEntry().getAsJsonObject("secondFactor");
    assertEquals("mail", factors.get("preferred").getAsString());
    assertEquals("bob@example.com", factors.getAsJsonObject("mail").get("address").getAsString());
    JsonObject status = bob.get("SecondFactor", "{}").result();
    assertEquals("bob@example.com", status.getAsJsonObject("mail").get("address").getAsString());
    assertFalse("used up", status.has("pendingAddress"));
    assertFalse(confirm(code).get("ok").getAsBoolean());
  }

  @Test
  public void aWrongPasswordOrAddressSendsNothing() throws Exception {
    try (FakeSmtpServer smtp = new FakeSmtpServer(null)) {
      smtp.start();
      mailOutTo(smtp);

      assertEquals("The password is wrong.", sendCode("bob@example.com", "nope").get("error").getAsString());
      assertEquals("This isn't an email address.", sendCode("bob@example.com>\\r\\nRCPT TO:<eve@example.com", "bob-password").get("error").getAsString());

      assertTrue(smtp.commands.isEmpty());
    }
  }

  @Test
  public void tooManyWrongCodesVoidTheCode() throws Exception {
    String code = codeSentTo("bob@example.com");
    String wrong = code.equals("000000") ? "111111" : "000000";
    for (int i = 1; i < SecondFactorService.MAX_WRONG_CODES; i++) assertEquals("This code is wrong.", confirm(wrong).get("error").getAsString());

    assertTrue(confirm(wrong).get("error").getAsString().startsWith("No valid code"));
    assertTrue(confirm(code).get("error").getAsString().startsWith("No valid code"));
    assertFalse(bobsEntry().has("secondFactor"));
  }

  @Test
  public void aNewAddressReplacesTheOldOnceConfirmedWhichIsTold() throws Exception {
    assertTrue(confirm(codeSentTo("bob@example.com")).get("ok").getAsBoolean());

    String code = codeSentTo("bob@example.org");
    assertEquals("bob@example.com", bobsEntry().getAsJsonObject("secondFactor").getAsJsonObject("mail").get("address").getAsString());
    try (FakeSmtpServer smtp = new FakeSmtpServer(null)) {
      smtp.start();
      mailOutTo(smtp);
      assertTrue(confirm(code).get("ok").getAsBoolean());

      assertTrue(smtp.message.contains("To: bob@example.com\r\n"));
      assertTrue(smtp.message.contains("Subject: Notes: two-step verification address changed\r\n"));
    }
    assertEquals("bob@example.org", bobsEntry().getAsJsonObject("secondFactor").getAsJsonObject("mail").get("address").getAsString());
  }

  @Test
  public void removingTakesThePasswordAndLeavesATotpPreferred() throws Exception {
    assertTrue(confirm(codeSentTo("bob@example.com")).get("ok").getAsBoolean());

    assertEquals("The password is wrong.", bob.post("RemoveMailFactor", "{\"password\":\"nope\"}").result().get("error").getAsString());
    assertTrue(bobsEntry().has("secondFactor"));
    try (FakeSmtpServer smtp = new FakeSmtpServer(null)) {
      smtp.start();
      mailOutTo(smtp);
      assertTrue(bob.post("RemoveMailFactor", "{\"password\":\"bob-password\"}").result().get("ok").getAsBoolean());

      assertTrue(smtp.message.contains("To: bob@example.com\r\n"));
      assertTrue(smtp.message.contains("Subject: Notes: two-step verification by mail turned off\r\n"));
    }
    assertFalse(bobsEntry().has("secondFactor"));
    assertNull(bob.get("SecondFactor", "{}").result().get("mail"));

    assertTrue(confirm(codeSentTo("bob@example.com")).get("ok").getAsBoolean());
    File credentials = new File(tmp.getRoot(), "credentials.json");
    Files.writeString(credentials.toPath(), Files.readString(credentials.toPath(), StandardCharsets.UTF_8)
        .replace("\"secondFactor\": {", "\"secondFactor\": {\"totp\": {\"secret\": \"ABC\"},"), StandardCharsets.UTF_8);
    // No SMTP server listens anymore: the notice isn't sent, the removal holds.
    assertTrue(bob.post("RemoveMailFactor", "{\"password\":\"bob-password\"}").result().get("ok").getAsBoolean());

    JsonObject factors = bobsEntry().getAsJsonObject("secondFactor");
    assertEquals("totp", factors.get("preferred").getAsString());
    assertEquals("ABC", factors.getAsJsonObject("totp").get("secret").getAsString());
    assertFalse(factors.has("mail"));
  }

  private static final String LOGIN = "{\"login\":\"bob\",\"password\":\"bob-password\"}";

  private static JsonObject loginCode(TestServer.Client client, String challenge, String code) throws Exception {
    return client.post("LoginCode", "{\"challenge\":\"" + challenge + "\",\"code\":\"" + code + "\"}").result();
  }

  private static boolean authenticated(TestServer.Client client) throws Exception {
    return client.get("Session", "{}").result().get("authenticated").getAsBoolean();
  }

  @Test
  public void withAMailFactorTheSessionComesOnlyWithTheCode() throws Exception {
    assertTrue(confirm(codeSentTo("bob@example.com")).get("ok").getAsBoolean());
    TestServer.Client phone = server.client();
    JsonObject login;
    String code;
    try (FakeSmtpServer smtp = new FakeSmtpServer(null)) {
      smtp.start();
      mailOutTo(smtp);
      login = phone.post("Login", LOGIN).result();
      Matcher matcher = CODE.matcher(smtp.message);
      assertTrue(matcher.find());
      code = matcher.group(1);
      assertTrue(smtp.message.contains("To: bob@example.com\r\n"));
    }
    assertFalse(login.get("ok").getAsBoolean());
    assertEquals("b***@example.com", login.get("sentTo").getAsString());
    String challenge = login.get("challenge").getAsString();
    assertFalse(authenticated(phone));

    JsonObject wrong = loginCode(phone, challenge, code.equals("000000") ? "111111" : "000000");
    assertEquals("This code is wrong.", wrong.get("error").getAsString());
    assertEquals("the same challenge goes on", challenge, wrong.get("challenge").getAsString());
    assertFalse(authenticated(phone));
    JsonObject right = loginCode(phone, challenge, code);

    assertTrue(right.get("ok").getAsBoolean());
    assertEquals("bob", right.get("username").getAsString());
    assertTrue(authenticated(phone));
    JsonObject again = loginCode(server.client(), challenge, code);
    assertFalse("used up", again.get("ok").getAsBoolean());
    assertFalse(again.has("challenge"));
  }

  @Test
  public void aLoginCodeWithoutAValidChallengeStartsOver() throws Exception {
    JsonObject result = loginCode(server.client(), "made-up", "123456");

    assertFalse(result.get("ok").getAsBoolean());
    assertFalse(result.has("challenge"));
    assertEquals("This login expired: log in again.", result.get("error").getAsString());
  }

  @Test
  public void tooManyWrongLoginCodesEndTheChallenge() throws Exception {
    assertTrue(confirm(codeSentTo("bob@example.com")).get("ok").getAsBoolean());
    TestServer.Client phone = server.client();
    String challenge;
    String code;
    try (FakeSmtpServer smtp = new FakeSmtpServer(null)) {
      smtp.start();
      mailOutTo(smtp);
      challenge = phone.post("Login", LOGIN).result().get("challenge").getAsString();
      Matcher matcher = CODE.matcher(smtp.message);
      assertTrue(matcher.find());
      code = matcher.group(1);
    }
    String wrong = code.equals("000000") ? "111111" : "000000";
    for (int i = 1; i < SecondFactorService.MAX_WRONG_CODES; i++) assertTrue(loginCode(phone, challenge, wrong).has("challenge"));

    assertFalse(loginCode(phone, challenge, wrong).has("challenge"));
    assertFalse(loginCode(phone, challenge, code).get("ok").getAsBoolean());
    assertFalse(authenticated(phone));
  }

  @Test
  public void withoutMailOutAMailFactorLoginIsRefusedAndSaysWhy() throws Exception {
    assertTrue(confirm(codeSentTo("bob@example.com")).get("ok").getAsBoolean());
    Files.delete(new File(tmp.getRoot(), "config.json").toPath());

    JsonObject login = server.client().post("Login", LOGIN).result();

    assertFalse(login.get("ok").getAsBoolean());
    assertFalse(login.has("challenge"));
    assertTrue(login.get("error").getAsString(), login.get("error").getAsString().contains("can't send mail"));
  }

  @Test
  public void anotherCodeReplacesTheFirstWithinTheSameLogin() throws Exception {
    assertTrue(confirm(codeSentTo("bob@example.com")).get("ok").getAsBoolean());
    TestServer.Client phone = server.client();
    String challenge;
    String first;
    try (FakeSmtpServer smtp = new FakeSmtpServer(null)) {
      smtp.start();
      mailOutTo(smtp);
      JsonObject login = phone.post("Login", LOGIN).result();
      assertEquals("mail", login.get("method").getAsString());
      assertFalse("no app", login.has("alternative"));
      challenge = login.get("challenge").getAsString();
      Matcher matcher = CODE.matcher(smtp.message);
      assertTrue(matcher.find());
      first = matcher.group(1);
    }
    String second;
    try (FakeSmtpServer smtp = new FakeSmtpServer(null)) {
      smtp.start();
      mailOutTo(smtp);
      JsonObject again = phone.post("LoginMethod", "{\"challenge\":\"" + challenge + "\",\"method\":\"mail\"}").result();
      assertEquals(challenge, again.get("challenge").getAsString());
      Matcher matcher = CODE.matcher(smtp.message);
      assertTrue(matcher.find());
      second = matcher.group(1);
    }

    if (!first.equals(second)) assertEquals("This code is wrong.", loginCode(phone, challenge, first).get("error").getAsString());
    assertTrue(loginCode(phone, challenge, second).get("ok").getAsBoolean());
  }
}
