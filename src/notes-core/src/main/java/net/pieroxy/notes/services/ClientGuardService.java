package net.pieroxy.notes.services;

import net.pieroxy.notes.utils.RateLimiter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Per-client-address protection of the web server, keyed on the address {@code AccessValve} settled
 * on (the real client's behind a trusted proxy; an IPv6 address by its /64, see
 * {@link #addressKey}). Only failed attempts count, so neither a legitimate
 * user nor someone else's attack can lock anyone out:
 * <ul>
 *   <li>failed logins lock logging in, for {@link #FAILED_LOGIN_WINDOW}; and at most
 *   {@link #MAX_CONCURRENT_CHECKS} passwords are checked at once, server-wide;</li>
 *   <li>404s without a session, typically from a scanner probing paths, ban the address from the
 *   whole web server for {@link #BAN_DURATION}.</li>
 * </ul>
 */
public class ClientGuardService implements Service {
  public static final int MAX_FAILED_LOGINS = 5;
  public static final Duration FAILED_LOGIN_WINDOW = Duration.ofMinutes(5);
  public static final int MAX_NOT_FOUND = 4;
  public static final Duration NOT_FOUND_WINDOW = Duration.ofSeconds(10);
  public static final Duration BAN_DURATION = Duration.ofMinutes(15);
  /** Requested at the site root by browsers and crawlers on their own: a missing one must not count. */
  public static final Set<String> IGNORED_NOT_FOUND_PATHS = Set.of(
      "/favicon.ico", "/apple-touch-icon.png", "/apple-touch-icon-precomposed.png", "/robots.txt");
  /** The webapp's hashed bundles: a page loaded before a redeploy still asks for the old hash. */
  public static final Pattern IGNORED_NOT_FOUND_PATTERN = Pattern.compile("/bundle-[^/]*\\.(js|css)");

  /**
   * Password checks running at once, at most: each takes about a tenth of a second of CPU (see
   * PasswordHasher), and a flood of them, from however many addresses, mustn't take the server down.
   */
  public static final int MAX_CONCURRENT_CHECKS = 4;
  /** Only the characters of an IPv6 literal (with an IPv4 tail, or a zone): what InetAddress parses without DNS. */
  private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+(%[0-9A-Za-z._-]+)?");

  private final RateLimiter failedLogins = new RateLimiter((int) FAILED_LOGIN_WINDOW.toMillis(), MAX_FAILED_LOGINS);
  private final Semaphore checks = new Semaphore(MAX_CONCURRENT_CHECKS);
  private final RateLimiter notFound;
  // One hit per ban: check() then refuses the address until that hit is banDuration old.
  private final RateLimiter bans;

  public ClientGuardService() {
    this(NOT_FOUND_WINDOW, BAN_DURATION);
  }

  /** Visible for tests: shorter durations. */
  ClientGuardService(Duration notFoundWindow, Duration banDuration) {
    notFound = new RateLimiter((int) notFoundWindow.toMillis(), MAX_NOT_FOUND);
    bans = new RateLimiter((int) banDuration.toMillis(), 1);
  }

  /**
   * Before checking a password for {@code clientAddress}: takes one of the
   * {@link #MAX_CONCURRENT_CHECKS} slots, and counts the attempt as failed until
   * {@link PasswordCheck#succeeded()} says otherwise. Counted first, in one step with the limit's
   * check: parallel attempts can't all pass the check before any of them counts. Close it once the
   * password is checked, which frees the slot.
   *
   * @throws Busy if {@link #MAX_CONCURRENT_CHECKS} checks are running: nothing is counted then.
   * @throws RateLimiter.RateExceededException when {@code clientAddress} failed {@link #MAX_FAILED_LOGINS}
   * times within {@link #FAILED_LOGIN_WINDOW}.
   */
  public PasswordCheck startPasswordCheck(String clientAddress) {
    if (!checks.tryAcquire()) throw new Busy();
    try {
      failedLogins.tryHit(addressKey(clientAddress));
    } catch (RuntimeException e) {
      checks.release();
      throw e;
    }
    return new PasswordCheck(addressKey(clientAddress));
  }

  /** A password check under way: see {@link #startPasswordCheck}. */
  public class PasswordCheck implements AutoCloseable {
    private final String key;
    private boolean closed;

    private PasswordCheck(String key) {
      this.key = key;
    }

    /** The password was right: the address's failed attempts are forgotten. */
    public void succeeded() {
      failedLogins.reset(key);
    }

    @Override
    public synchronized void close() {
      if (closed) return;
      closed = true;
      checks.release();
    }
  }

  /** {@link #MAX_CONCURRENT_CHECKS} password checks already running: retry in a moment. */
  public static class Busy extends RuntimeException {
    public Busy() {
      super("Too many password checks at once");
    }
  }

  /** @throws RateLimiter.RateExceededException while {@code clientAddress} is banned, with the time left. */
  public void checkBanned(String clientAddress) {
    bans.check(addressKey(clientAddress));
  }

  /**
   * Counts a 404 answered to a request without a valid session. Paths in
   * {@link #IGNORED_NOT_FOUND_PATHS} or matching {@link #IGNORED_NOT_FOUND_PATTERN} don't count.
   *
   * @return true if this 404 got the address banned.
   */
  public boolean notFound(String clientAddress, String path) {
    if (IGNORED_NOT_FOUND_PATHS.contains(path) || IGNORED_NOT_FOUND_PATTERN.matcher(path).matches()) return false;
    String key = addressKey(clientAddress);
    notFound.hit(key);
    try {
      notFound.check(key);
      return false;
    } catch (RateLimiter.RateExceededException e) {
      notFound.reset(key);
      bans.hit(key);
      return true;
    }
  }

  /**
   * What the limits count by: an IPv4 address, or an IPv6 address's /64, the block a single
   * customer typically gets, so that one can't dodge the limits by changing addresses within it.
   * Anything else as is. Never resolved through DNS.
   */
  static String addressKey(String address) {
    if (address == null || address.indexOf(':') < 0 || !IPV6_LITERAL.matcher(address).matches()) return address;
    try {
      int zone = address.indexOf('%');
      InetAddress parsed = InetAddress.getByName(zone < 0 ? address : address.substring(0, zone));
      byte[] bytes = parsed.getAddress();
      // ::ffff:a.b.c.d is an IPv4 client.
      if (bytes.length == 4) return parsed.getHostAddress();
      StringBuilder key = new StringBuilder();
      for (int i = 0; i < 8; i += 2) key.append(Integer.toHexString(((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff))).append(':');
      return key.append(":/64").toString();
    } catch (UnknownHostException e) {
      return address;
    }
  }
}
