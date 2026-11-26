package net.pieroxy.notes.mail;

import net.pieroxy.notes.config.MailOutConfiguration;
import net.pieroxy.notes.config.MailOutConfiguration.Security;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SmtpClientTest {
  @ClassRule
  public static TemporaryFolder tmp = new TemporaryFolder();

  /** A server certificate for 127.0.0.1, and a client trusting it. */
  private static SSLContext serverTls;
  private static SSLSocketFactory trusting;

  @BeforeClass
  public static void certificate() throws Exception {
    File keystore = new File(tmp.getRoot(), "smtp.p12");
    String keytool = new File(System.getProperty("java.home"), "bin/keytool").getPath();
    Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "smtp", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
        "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", keystore.getPath(),
        "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(output, 0, process.waitFor());
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream in = new FileInputStream(keystore)) {
      store.load(in, "changeit".toCharArray());
    }
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, "changeit".toCharArray());
    serverTls = SSLContext.getInstance("TLS");
    serverTls.init(keys.getKeyManagers(), null, null);
    TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(store);
    SSLContext client = SSLContext.getInstance("TLS");
    client.init(null, trust.getTrustManagers(), null);
    trusting = client.getSocketFactory();
  }

  private static MailOutConfiguration config(FakeSmtpServer server, Security security, String username, String password) {
    MailOutConfiguration config = new MailOutConfiguration();
    config.setHost("127.0.0.1");
    config.setPort(server.port());
    config.setSecurity(security);
    config.setUsername(username);
    config.setPassword(password);
    config.setFrom("notes@example.com");
    return config;
  }

  private static final Mail MAIL = new Mail("alice@example.com", "Your code", "Line one\n.Line two\n");

  private static String bodyOf(String message) {
    String encoded = message.substring(message.indexOf("\r\n\r\n") + 4).replace("\r\n", "");
    return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
  }

  @Test
  public void sendsWithAuthPlainOverStartTls() throws Exception {
    try (FakeSmtpServer server = new FakeSmtpServer(serverTls)) {
      server.offerStartTls = true;
      server.start();

      new SmtpClient(trusting).send(config(server, Security.STARTTLS, "notes", "s3cret"), MAIL);

      assertNull(server.failure);
      assertTrue(server.commands.get(0).startsWith("EHLO [127.0.0.1]"));
      assertEquals("STARTTLS", server.commands.get(1));
      assertTrue("hello again, encrypted", server.commands.get(2).startsWith("EHLO "));
      String credentials = new String(Base64.getDecoder().decode(server.commands.get(3).substring("AUTH PLAIN ".length())), StandardCharsets.UTF_8);
      assertEquals("\0notes\0s3cret", credentials);
      assertEquals("MAIL FROM:<notes@example.com>", server.commands.get(4));
      assertEquals("RCPT TO:<alice@example.com>", server.commands.get(5));
      assertTrue(server.message.contains("Subject: Your code\r\n"));
      assertTrue(server.message.contains("From: notes@example.com\r\n"));
      assertEquals("Line one\r\n.Line two\r\n", bodyOf(server.message));
    }
  }

  @Test
  public void sendsOverImplicitTlsWithAuthLogin() throws Exception {
    try (FakeSmtpServer server = new FakeSmtpServer(serverTls)) {
      server.implicitTls = true;
      server.authMechanisms = "LOGIN";
      server.start();

      new SmtpClient(trusting).send(config(server, Security.TLS, "notes", "s3cret"), MAIL);

      assertNull(server.failure);
      assertEquals("AUTH LOGIN", server.commands.get(1));
      assertEquals("notes", new String(Base64.getDecoder().decode(server.commands.get(2)), StandardCharsets.UTF_8));
      assertEquals("s3cret", new String(Base64.getDecoder().decode(server.commands.get(3)), StandardCharsets.UTF_8));
      assertTrue(server.message != null);
    }
  }

  @Test
  public void sendsInClearWithoutAuthentication() throws Exception {
    try (FakeSmtpServer server = new FakeSmtpServer(serverTls)) {
      server.start();

      new SmtpClient(trusting).send(config(server, Security.NONE, "", null), MAIL);

      assertFalse(server.commands.stream().anyMatch(c -> c.startsWith("AUTH")));
      assertTrue(server.message != null);
    }
  }

  @Test
  public void anUntrustedCertificateIsRefused() throws Exception {
    try (FakeSmtpServer server = new FakeSmtpServer(serverTls)) {
      server.implicitTls = true;
      server.start();

      try {
        new SmtpClient().send(config(server, Security.TLS, "notes", "s3cret"), MAIL);
        fail();
      } catch (SmtpException e) {
        assertTrue(e.getMessage(), e.getMessage().startsWith("The TLS connection to 127.0.0.1 failed"));
      }
    }
  }

  @Test
  public void startTlsMissingIsAnErrorNotAFallbackToClear() throws Exception {
    try (FakeSmtpServer server = new FakeSmtpServer(serverTls)) {
      server.start();

      try {
        new SmtpClient(trusting).send(config(server, Security.STARTTLS, "notes", "s3cret"), MAIL);
        fail();
      } catch (SmtpException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("doesn't offer STARTTLS"));
      }
      assertFalse(server.commands.stream().anyMatch(c -> c.startsWith("AUTH")));
    }
  }

  @Test
  public void aRefusalSaysWhichStepAndWhyButNeverThePassword() throws Exception {
    try (FakeSmtpServer server = new FakeSmtpServer(serverTls)) {
      server.replies.put("AUTH", "535 5.7.8 Authentication failed");
      server.start();

      try {
        new SmtpClient(trusting).send(config(server, Security.NONE, "notes", "s3cret"), MAIL);
        fail();
      } catch (SmtpException e) {
        assertEquals("AUTH PLAIN *** was refused: 535 5.7.8 Authentication failed", e.getMessage());
      }
    }
    try (FakeSmtpServer server = new FakeSmtpServer(serverTls)) {
      server.replies.put("RCPT", "550 5.1.1 No such user");
      server.start();

      try {
        new SmtpClient(trusting).send(config(server, Security.NONE, "", null), MAIL);
        fail();
      } catch (SmtpException e) {
        assertEquals("RCPT TO:<alice@example.com> was refused: 550 5.1.1 No such user", e.getMessage());
      }
    }
  }

  @Test
  public void aSubjectOutsideAsciiIsEncodedAndHeadersCantBeInjected() {
    String message = new Mail("alice@example.com", "Votre code — été", "x").format("notes@example.com", ZonedDateTime.of(2026, 11, 26, 12, 0, 0, 0, ZoneOffset.UTC));
    assertTrue(message, message.contains("Subject: =?UTF-8?B?" + Base64.getEncoder().encodeToString("Votre code — été".getBytes(StandardCharsets.UTF_8)) + "?=\r\n"));
    assertTrue(message.contains("Date: Thu, 26 Nov 2026 12:00:00 GMT\r\n"));
    for (String bad : new String[] {"a\r\nBcc: eve@example.com", "a\nb"}) {
      try {
        new Mail("alice@example.com", bad, "x");
        fail(bad);
      } catch (IllegalArgumentException e) {
        // Refused.
      }
    }
    try {
      new Mail("alice@example.com>\r\nRCPT TO:<eve@example.com", "s", "x");
      fail();
    } catch (IllegalArgumentException e) {
      // Refused.
    }
  }
}
