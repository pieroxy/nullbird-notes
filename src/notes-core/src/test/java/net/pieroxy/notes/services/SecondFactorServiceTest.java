package net.pieroxy.notes.services;

import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.config.MailOutConfiguration;
import net.pieroxy.notes.mail.Mail;
import net.pieroxy.notes.mail.SmtpClient;
import net.pieroxy.notes.utils.RateLimiter;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class SecondFactorServiceTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private Instant now = Instant.parse("2026-01-01T00:00:00Z");
  private final Clock clock = new Clock() {
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
  };
  /** The code in the last mail "sent". */
  private String lastCode;
  private SecondFactorService service;

  @Before
  public void setUp() throws Exception {
    File credentials = new File(tmp.getRoot(), "credentials.json");
    Files.writeString(credentials.toPath(), "{\"users\": []}", StandardCharsets.UTF_8);
    Files.writeString(new File(tmp.getRoot(), "config.json").toPath(),
        "{\"mailOut\": {\"host\": \"smtp.example.com\", \"from\": \"notes@example.com\"}}", StandardCharsets.UTF_8);
    SettingsService settings = new SettingsService(new Configuration(), credentials, tmp.getRoot());
    settings.start();
    service = new SecondFactorService(clock, new SmtpClient() {
      @Override
      public void send(MailOutConfiguration config, Mail mail) {
        lastCode = mail.subject();
      }
    });
    ServiceProvider provider = new ServiceProvider(settings, new SessionService(), new ClientGuardService(), new NoteStoreService(),
        new NoteService(), new ImportService(), new FontService(), new WebServerService(), service);
    service.init(provider.scopedFor(service));
  }

  private void send(String key) throws Exception {
    service.sendCode(key, "alice", code -> new Mail("alice@example.com", code, ""));
  }

  @Test
  public void aCodeExpires() throws Exception {
    send("k");
    now = now.plus(SecondFactorService.CODE_LIFETIME).minusSeconds(1);
    assertEquals("alice@example.com", service.pendingAddress("k"));

    now = now.plusSeconds(1);

    assertNull(service.pendingAddress("k"));
    assertEquals(SecondFactorService.Check.Outcome.NONE, service.check("k", lastCode).outcome());
  }

  @Test
  public void aNewCodeReplacesTheOldAndKeysDontMix() throws Exception {
    send("k");
    String first = lastCode;
    send("k");
    if (!first.equals(lastCode)) assertEquals(SecondFactorService.Check.Outcome.WRONG, service.check("k", first).outcome());
    assertEquals(SecondFactorService.Check.Outcome.NONE, service.check("other", lastCode).outcome());

    assertEquals(SecondFactorService.Check.Outcome.RIGHT, service.check("k", lastCode).outcome());
  }

  @Test
  public void aMailboxIsntFlooded() throws Exception {
    for (int i = 0; i < SecondFactorService.MAX_SENT; i++) send("k" + i);
    try {
      send("k");
      fail();
    } catch (RateLimiter.RateExceededException e) {
      // Refused.
    }
  }
}
