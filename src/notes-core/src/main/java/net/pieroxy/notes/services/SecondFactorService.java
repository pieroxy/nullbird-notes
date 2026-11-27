package net.pieroxy.notes.services;

import net.pieroxy.notes.config.ConfigValidation;
import net.pieroxy.notes.config.MailOutConfiguration;
import net.pieroxy.notes.mail.Mail;
import net.pieroxy.notes.mail.SmtpClient;
import net.pieroxy.notes.totp.Totp;
import net.pieroxy.notes.utils.RateLimiter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Codes mailed for a second factor, and logins waiting for one, held in memory: a restart voids them.
 * Each code is for one purpose, its key (e.g. "enroll:alice"), valid for {@link #CODE_LIFETIME} and
 * {@link #MAX_WRONG_CODES} wrong tries; a new one under the same key replaces it.
 */
public class SecondFactorService implements Service {
  public static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
  public static final int MAX_WRONG_CODES = 5;
  /** Codes mailed per user within {@link #SENT_WINDOW}, at most: a mailbox mustn't be flooded. */
  public static final int MAX_SENT = 5;
  public static final Duration SENT_WINDOW = Duration.ofMinutes(15);
  /** How long a TOTP secret waits for its first code: time to install an app. */
  public static final Duration TOTP_SETUP_LIFETIME = Duration.ofMinutes(30);
  /** Wrong TOTP codes per user within {@link #WRONG_TOTP_WINDOW}, at most: the next code is always valid, unlike a mailed one. */
  public static final int MAX_WRONG_TOTP = 10;
  public static final Duration WRONG_TOTP_WINDOW = Duration.ofMinutes(15);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Clock clock;
  private final SmtpClient smtp;
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
  // By username.
  private final Map<String, TotpSetup> totpSetups = new ConcurrentHashMap<>();
  private final RateLimiter wrongTotp = new RateLimiter((int) WRONG_TOTP_WINDOW.toMillis(), MAX_WRONG_TOTP);
  private final RateLimiter sent = new RateLimiter((int) SENT_WINDOW.toMillis(), MAX_SENT);
  private SettingsService settings;

  public SecondFactorService() {
    this(Clock.systemUTC(), new SmtpClient());
  }

  SecondFactorService(Clock clock, SmtpClient smtp) {
    this.clock = clock;
    this.smtp = smtp;
  }

  @Override
  public List<Class<? extends Service>> getDependencies() {
    return List.of(SettingsService.class);
  }

  @Override
  public void init(IServiceProvider serviceProvider) {
    settings = serviceProvider.getSettingsService();
  }

  /**
   * @return the saved outgoing mail settings, null if no SMTP server is saved.
   * @throws IOException if config.json can't be read.
   */
  public MailOutConfiguration mailOut() throws IOException {
    MailOutConfiguration config = settings.readSavedConfig().getMailOut();
    return config == null || config.getHost() == null || config.getHost().isBlank() ? null : config;
  }

  /**
   * Mails a new code, replacing any pending under {@code key}.
   *
   * @param user the rate limit's key.
   * @param mail the mail, given the code.
   * @throws RateLimiter.RateExceededException if {@code user} was sent {@link #MAX_SENT} codes within {@link #SENT_WINDOW}.
   * @throws IOException if it wasn't sent, its message for the user: no SMTP server saved, or what the server said.
   */
  public void sendCode(String key, String user, Function<String, Mail> mail) throws IOException {
    MailOutConfiguration config = mailOut();
    if (config == null) throw new IOException("This server can't send mail: its administrator hasn't set it up.");
    try {
      ConfigValidation.validate(config);
    } catch (IllegalArgumentException e) {
      throw new IOException("This server's mail settings are invalid: " + e.getMessage());
    }
    sent.tryHit(user);
    String code = String.format("%06d", RANDOM.nextInt(1_000_000));
    Mail message = mail.apply(code);
    pending.values().removeIf(p -> p.expired(clock.instant()));
    smtp.send(config, message);
    pending.put(key, new Pending(code, message.to(), clock.instant().plus(CODE_LIFETIME)));
  }

  /**
   * Mails {@code mail}, not rate limited: for notices of changes the user made with their password.
   *
   * @throws IOException if it wasn't sent: no SMTP server saved, invalid settings, or what the server said.
   */
  public void sendNotice(Mail mail) throws IOException {
    MailOutConfiguration config = mailOut();
    if (config == null) throw new IOException("no SMTP server is saved");
    try {
      ConfigValidation.validate(config);
    } catch (IllegalArgumentException e) {
      throw new IOException("invalid mail settings: " + e.getMessage());
    }
    smtp.send(config, mail);
  }

  /**
   * A login past its password, waiting for its second factor: valid for {@link #CODE_LIFETIME}.
   *
   * @param method {@code SecondFactor.MAIL} or {@code SecondFactor.TOTP}: the one asked.
   * @return its ID, secret: whoever has it may finish this login.
   */
  public String startChallenge(String username, String method) {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant now = clock.instant();
    challenges.values().removeIf(c -> !now.isBefore(c.expiresAt));
    challenges.put(id, new Challenge(username, method, now.plus(CODE_LIFETIME)));
    return id;
  }

  /** @return the user logging in under challenge {@code id}, null if none is valid. */
  public String challengeUser(String id) {
    Challenge challenge = validChallenge(id);
    return challenge == null ? null : challenge.username;
  }

  /** @return the method challenge {@code id} asks, null if none is valid. */
  public String challengeMethod(String id) {
    Challenge challenge = validChallenge(id);
    return challenge == null ? null : challenge.method;
  }

  /** Challenge {@code id} asks {@code method} from now on, its expiry unchanged; a mailed code is voided. */
  public void switchChallenge(String id, String method) {
    challenges.computeIfPresent(id, (k, c) -> new Challenge(c.username, method, c.expiresAt));
    pending.remove(challengeKey(id));
  }

  private Challenge validChallenge(String id) {
    Challenge challenge = id == null ? null : challenges.get(id);
    return challenge == null || !clock.instant().isBefore(challenge.expiresAt) ? null : challenge;
  }

  /** Ends challenge {@code id}, and voids its code. */
  public void endChallenge(String id) {
    if (id == null) return;
    challenges.remove(id);
    pending.remove(challengeKey(id));
  }

  /** The key of the code proving {@code username}'s new mail factor address. */
  public static String enrollKey(String username) {
    return "enroll:" + username;
  }

  /** The key of challenge {@code id}'s code. */
  public static String challengeKey(String id) {
    return "login:" + id;
  }

  private record Challenge(String username, String method, Instant expiresAt) {
  }

  /** @param secret base32, waiting for its first code. */
  private record TotpSetup(String secret, Instant expiresAt) {
  }

  /** A new TOTP secret for {@code username}, waiting for its first code: replaces any previous one. */
  public String startTotpSetup(String username) {
    String secret = Totp.newSecret();
    Instant now = clock.instant();
    totpSetups.values().removeIf(c -> !now.isBefore(c.expiresAt));
    totpSetups.put(username, new TotpSetup(secret, now.plus(TOTP_SETUP_LIFETIME)));
    return secret;
  }

  /** @return the TOTP secret waiting for {@code username}'s first code, null if none is valid. */
  public String pendingTotpSecret(String username) {
    TotpSetup setup = totpSetups.get(username);
    return setup == null || !clock.instant().isBefore(setup.expiresAt) ? null : setup.secret;
  }

  public void endTotpSetup(String username) {
    totpSetups.remove(username);
  }

  /**
   * {@link Totp#verify} now, a wrong code counted against {@code username}.
   *
   * @return the step matched, null if wrong.
   * @throws RateLimiter.RateExceededException after {@link #MAX_WRONG_TOTP} wrong codes within {@link #WRONG_TOTP_WINDOW}: not checked then.
   */
  public Long verifyTotp(String username, String secret, String code, long lastUsedStep) {
    wrongTotp.check(username);
    Long step = Totp.verify(secret, code, clock.instant(), lastUsedStep);
    if (step == null) wrongTotp.hit(username);
    return step;
  }

  /** {@link Totp#offsetSteps}, now. */
  public Long totpOffsetSteps(String secret, String code, int maxSteps) {
    return Totp.offsetSteps(secret, code, clock.instant(), maxSteps);
  }

  /** @return where the code pending under {@code key} went, null if none is valid. */
  public String pendingAddress(String key) {
    Pending p = pending.get(key);
    return p == null || p.expired(clock.instant()) ? null : p.address;
  }

  public void discard(String key) {
    pending.remove(key);
  }

  /**
   * Checks {@code code} against the one pending under {@code key}, which goes once right, or wrong
   * {@link #MAX_WRONG_CODES} times. Spaces are ignored.
   */
  public Check check(String key, String code) {
    String typed = code == null ? "" : code.replaceAll("\\s", "");
    Check[] result = {Check.NONE};
    pending.computeIfPresent(key, (k, p) -> {
      if (p.expired(clock.instant())) return null;
      if (MessageDigest.isEqual(p.code.getBytes(StandardCharsets.UTF_8), typed.getBytes(StandardCharsets.UTF_8))) {
        result[0] = Check.right(p.address);
        return null;
      }
      p.wrong++;
      result[0] = p.wrong >= MAX_WRONG_CODES ? Check.NONE : Check.WRONG;
      return p.wrong >= MAX_WRONG_CODES ? null : p;
    });
    return result[0];
  }

  /**
   * What a code turned out to be.
   *
   * @param address where it went, if right.
   */
  public record Check(Outcome outcome, String address) {
    public enum Outcome {
      RIGHT,
      WRONG,
      /** None valid: none sent, expired, or the last wrong try. */
      NONE
    }

    static final Check WRONG = new Check(Outcome.WRONG, null);
    static final Check NONE = new Check(Outcome.NONE, null);

    static Check right(String address) {
      return new Check(Outcome.RIGHT, address);
    }
  }

  private static class Pending {
    final String code;
    final String address;
    final Instant expiresAt;
    int wrong;

    Pending(String code, String address, Instant expiresAt) {
      this.code = code;
      this.address = address;
      this.expiresAt = expiresAt;
    }

    boolean expired(Instant now) {
      return !now.isBefore(expiresAt);
    }
  }
}
