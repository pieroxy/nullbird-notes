package net.pieroxy.notes.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.pieroxy.notes.mail.FakeSmtpServer;
import net.pieroxy.notes.services.SecondFactorService;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** StartTotpFactor, ConfirmTotpFactor, RemoveTotpFactor and SetPreferredFactor through HTTP. */
public class TotpFactorApiTest {
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

  private JsonObject bobsEntry() throws Exception {
    String json = Files.readString(new File(tmp.getRoot(), "credentials.json").toPath(), StandardCharsets.UTF_8);
    return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("users").get(0).getAsJsonObject();
  }

  private String start() throws Exception {
    JsonObject setup = bob.post("StartTotpFactor", "{\"password\":\"bob-password\"}").result();
    assertTrue(setup.get("ok").getAsBoolean());
    return setup.get("secret").getAsString();
  }

  private JsonObject confirm(String code) throws Exception {
    return bob.post("ConfirmTotpFactor", "{\"code\":\"" + code + "\"}").result();
  }

  private static String codeAt(String secret, long stepsFromNow) {
    return Totp.code(Base32.decode(secret), Totp.step(Instant.now()) + stepsFromNow);
  }

  /** A code no step within the clock check matches. */
  private static String wrongCode(String secret) {
    Set<String> near = new HashSet<>();
    for (int i = -25; i <= 25; i++) near.add(codeAt(secret, i));
    for (int candidate = 0; ; candidate++) {
      String code = String.format("%06d", candidate);
      if (!near.contains(code)) return code;
    }
  }

  private String setUpTotp() throws Exception {
    String secret = start();
    assertTrue(confirm(codeAt(secret, 0)).get("ok").getAsBoolean());
    return secret;
  }

  private void mailOutTo(FakeSmtpServer smtp) throws Exception {
    Files.writeString(new File(tmp.getRoot(), "config.json").toPath(), "{\"mailOut\": {\"host\": \"127.0.0.1\", \"port\": " + smtp.port()
        + ", \"security\": \"NONE\", \"from\": \"notes@example.com\"}}", StandardCharsets.UTF_8);
  }

  private void setUpMail() throws Exception {
    try (FakeSmtpServer smtp = new FakeSmtpServer(null)) {
      smtp.start();
      mailOutTo(smtp);
      bob.post("SendMailFactorCode", "{\"address\":\"bob@example.com\",\"password\":\"bob-password\"}");
      Matcher matcher = Pattern.compile("Subject: Notes code: (\\d{6})").matcher(smtp.message);
      assertTrue(matcher.find());
      assertTrue(bob.post("ConfirmMailFactor", "{\"code\":\"" + matcher.group(1) + "\"}").result().get("ok").getAsBoolean());
    }
  }

  @Test
  public void aCodeFromTheAppSavesTheSecret() throws Exception {
    assertEquals("The password is wrong.", bob.post("StartTotpFactor", "{\"password\":\"nope\"}").result().get("error").getAsString());
    String secret = start();
    assertFalse(bob.get("SecondFactor", "{}").result().has("totp"));
    assertEquals("This code is wrong.", confirm(wrongCode(secret)).get("error").getAsString());
    assertFalse(bobsEntry().has("secondFactor"));

    assertTrue(confirm(codeAt(secret, 0)).get("ok").getAsBoolean());

    JsonObject factors = bobsEntry().getAsJsonObject("secondFactor");
    assertEquals("totp", factors.get("preferred").getAsString());
    assertEquals(secret, factors.getAsJsonObject("totp").get("secret").getAsString());
    assertEquals(Totp.step(Instant.now()), factors.getAsJsonObject("totp").get("lastUsedStep").getAsLong(), 1);
    assertTrue(bob.get("SecondFactor", "{}").result().has("totp"));
    assertEquals("used up", "This setup expired: start again.", confirm(codeAt(secret, 0)).get("error").getAsString());
  }

  @Test
  public void aCodeFromAClockOffSaysSo() throws Exception {
    String secret = start();

    // A step may pass between here and the server: about, not exact.
    String ahead = confirm(codeAt(secret, 10)).get("error").getAsString();
    String behind = confirm(codeAt(secret, -10)).get("error").getAsString();

    assertTrue(ahead, ahead.matches("This code is from a clock about [56] min ahead of this server's: .*"));
    assertTrue(behind, behind.matches("This code is from a clock about [45] min behind this server's: .*"));
    assertFalse(bobsEntry().has("secondFactor"));
  }

  @Test
  public void tooManyWrongCodesWait() throws Exception {
    String secret = start();
    for (int i = 0; i < SecondFactorService.MAX_WRONG_TOTP; i++) assertEquals("This code is wrong.", confirm(wrongCode(secret)).get("error").getAsString());

    String refused = confirm(codeAt(secret, 0)).get("error").getAsString();

    assertTrue(refused, refused.startsWith("Too many wrong codes"));
    assertFalse(bobsEntry().has("secondFactor"));
  }

  @Test
  public void anotherAppReplacesTheFirstAndTheMailAddressIsTold() throws Exception {
    setUpMail();
    String first = setUpTotp();
    assertEquals("mail", bobsEntry().getAsJsonObject("secondFactor").get("preferred").getAsString());
    String second = start();
    assertEquals("the first still works meanwhile", first, bobsEntry().getAsJsonObject("secondFactor").getAsJsonObject("totp").get("secret").getAsString());

    try (FakeSmtpServer smtp = new FakeSmtpServer(null)) {
      smtp.start();
      mailOutTo(smtp);
      assertTrue(confirm(codeAt(second, 0)).get("ok").getAsBoolean());

      assertTrue(smtp.message.contains("To: bob@example.com\r\n"));
      assertTrue(smtp.message.contains("Subject: Notes: authenticator app changed\r\n"));
    }
    assertEquals(second, bobsEntry().getAsJsonObject("secondFactor").getAsJsonObject("totp").get("secret").getAsString());
  }

  @Test
  public void removingTakesThePasswordLeavesMailPreferredAndTellsIt() throws Exception {
    setUpMail();
    setUpTotp();
    assertTrue(bob.post("SetPreferredFactor", "{\"method\":\"totp\"}").result().get("ok").getAsBoolean());
    assertEquals("totp", bob.get("SecondFactor", "{}").result().get("preferred").getAsString());

    assertEquals("The password is wrong.", bob.post("RemoveTotpFactor", "{\"password\":\"nope\"}").result().get("error").getAsString());
    try (FakeSmtpServer smtp = new FakeSmtpServer(null)) {
      smtp.start();
      mailOutTo(smtp);
      assertTrue(bob.post("RemoveTotpFactor", "{\"password\":\"bob-password\"}").result().get("ok").getAsBoolean());

      assertTrue(smtp.message.contains("Subject: Notes: authenticator app turned off\r\n"));
    }
    JsonObject factors = bobsEntry().getAsJsonObject("secondFactor");
    assertFalse(factors.has("totp"));
    assertEquals("mail", factors.get("preferred").getAsString());
    assertFalse("only with both", bob.get("SecondFactor", "{}").result().has("preferred"));
  }

  @Test
  public void onlyAFactorSetUpCanBePreferred() throws Exception {
    setUpTotp();

    assertEquals("You don't have this method set up.", bob.post("SetPreferredFactor", "{\"method\":\"mail\"}").result().get("error").getAsString());
    assertEquals("You don't have this method set up.", bob.post("SetPreferredFactor", "{\"method\":\"sms\"}").result().get("error").getAsString());
    assertEquals("totp", bobsEntry().getAsJsonObject("secondFactor").get("preferred").getAsString());
  }

  private static final String LOGIN = "{\"login\":\"bob\",\"password\":\"bob-password\"}";

  private static JsonObject loginCode(TestServer.Client client, String challenge, String code) throws Exception {
    return client.post("LoginCode", "{\"challenge\":\"" + challenge + "\",\"code\":\"" + code + "\"}").result();
  }

  private static boolean authenticated(TestServer.Client client) throws Exception {
    return client.get("Session", "{}").result().get("authenticated").getAsBoolean();
  }

  @Test
  public void withAnAppTheSessionComesWithItsCodeOnce() throws Exception {
    // Setting up used the current step's code: the next one logs in.
    String secret = setUpTotp();
    TestServer.Client phone = server.client();
    JsonObject login = phone.post("Login", LOGIN).result();
    assertEquals("totp", login.get("method").getAsString());
    assertFalse(login.has("alternative"));
    assertFalse(login.has("sentTo"));
    String challenge = login.get("challenge").getAsString();

    JsonObject wrong = loginCode(phone, challenge, wrongCode(secret));
    assertEquals("This code is wrong.", wrong.get("error").getAsString());
    assertEquals(challenge, wrong.get("challenge").getAsString());
    assertFalse(authenticated(phone));
    String code = codeAt(secret, 1);
    assertTrue(loginCode(phone, challenge, code).get("ok").getAsBoolean());
    assertTrue(authenticated(phone));

    TestServer.Client thief = server.client();
    String again = thief.post("Login", LOGIN).result().get("challenge").getAsString();
    assertEquals("a code works once", "This code is wrong.", loginCode(thief, again, code).get("error").getAsString());
    assertEquals("nor one before it", "This code is wrong.", loginCode(thief, again, codeAt(secret, 0)).get("error").getAsString());
    assertFalse(authenticated(thief));
  }

  @Test
  public void withBothThePreferredIsAskedAndTheOtherOffered() throws Exception {
    setUpMail();
    String secret = setUpTotp();
    assertTrue(bob.post("SetPreferredFactor", "{\"method\":\"totp\"}").result().get("ok").getAsBoolean());
    TestServer.Client phone = server.client();
    try (FakeSmtpServer smtp = new FakeSmtpServer(null)) {
      smtp.start();
      mailOutTo(smtp);
      JsonObject login = phone.post("Login", LOGIN).result();
      assertEquals("totp", login.get("method").getAsString());
      assertEquals("mail", login.get("alternative").getAsString());
      String challenge = login.get("challenge").getAsString();

      JsonObject byMail = phone.post("LoginMethod", "{\"challenge\":\"" + challenge + "\",\"method\":\"mail\"}").result();

      assertEquals(challenge, byMail.get("challenge").getAsString());
      assertEquals("mail", byMail.get("method").getAsString());
      assertEquals("b***@example.com", byMail.get("sentTo").getAsString());
      assertEquals("totp", byMail.get("alternative").getAsString());
      Matcher matcher = Pattern.compile("Subject: Notes code: (\\d{6})").matcher(smtp.message);
      assertTrue(matcher.find());
      assertFalse("the app's code isn't asked anymore", loginCode(phone, challenge, codeAt(secret, 1)).get("ok").getAsBoolean());
      assertTrue(loginCode(phone, challenge, matcher.group(1)).get("ok").getAsBoolean());
    }
    assertTrue(authenticated(phone));
  }

  @Test
  public void mailPreferredButNotSendableAsksTheApp() throws Exception {
    setUpMail();
    String secret = setUpTotp();
    assertEquals("mail", bobsEntry().getAsJsonObject("secondFactor").get("preferred").getAsString());
    Files.delete(new File(tmp.getRoot(), "config.json").toPath());
    TestServer.Client phone = server.client();

    JsonObject login = phone.post("Login", LOGIN).result();

    assertEquals("totp", login.get("method").getAsString());
    assertFalse(login.has("alternative"));
    String challenge = login.get("challenge").getAsString();
    assertEquals("Codes can't be mailed to you.", phone.post("LoginMethod", "{\"challenge\":\"" + challenge + "\",\"method\":\"mail\"}").result().get("error").getAsString());
    assertTrue(loginCode(phone, challenge, codeAt(secret, 1)).get("ok").getAsBoolean());
  }
}
