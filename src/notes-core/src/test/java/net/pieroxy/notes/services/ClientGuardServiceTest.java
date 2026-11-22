package net.pieroxy.notes.services;

import net.pieroxy.notes.utils.RateLimiter;
import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ClientGuardServiceTest {
  private static final String SCANNER = "203.0.113.7";
  private static final String VISITOR = "198.51.100.4";

  @Test
  public void bansAfterMaxNotFoundWithinTheWindow() {
    ClientGuardService guard = new ClientGuardService();
    for (int i = 1; i < ClientGuardService.MAX_NOT_FOUND; i++) {
      assertFalse(guard.notFound(SCANNER, "/wp-login.php"));
    }
    guard.checkBanned(SCANNER);

    assertTrue("the last 404 must trigger the ban", guard.notFound(SCANNER, "/.env"));
    assertThrows(RateLimiter.RateExceededException.class, () -> guard.checkBanned(SCANNER));
  }

  @Test
  public void ignoredPathsNeverCount() {
    ClientGuardService guard = new ClientGuardService();
    for (int i = 0; i < 3 * ClientGuardService.MAX_NOT_FOUND; i++) {
      for (String path : ClientGuardService.IGNORED_NOT_FOUND_PATHS) {
        assertFalse(guard.notFound(VISITOR, path));
      }
      assertFalse(guard.notFound(VISITOR, "/bundle-4d2a47.js"));
      assertFalse(guard.notFound(VISITOR, "/bundle-4d2a47.css"));
    }
    guard.checkBanned(VISITOR);
  }

  @Test
  public void bundlePatternOnlyMatchesRootBundles() {
    assertFalse(ClientGuardService.IGNORED_NOT_FOUND_PATTERN.matcher("/x/bundle-4d2a47.js").matches());
    assertFalse(ClientGuardService.IGNORED_NOT_FOUND_PATTERN.matcher("/bundle-4d2a47.php").matches());
  }

  @Test
  public void aBanOnlyConcernsItsOwnAddress() {
    ClientGuardService guard = new ClientGuardService();
    for (int i = 0; i < ClientGuardService.MAX_NOT_FOUND; i++) guard.notFound(SCANNER, "/admin");

    assertThrows(RateLimiter.RateExceededException.class, () -> guard.checkBanned(SCANNER));
    guard.checkBanned(VISITOR);
  }

  @Test
  public void notFoundsSpreadOverMoreThanTheWindowDontBan() throws InterruptedException {
    ClientGuardService guard = new ClientGuardService(Duration.ofMillis(200), Duration.ofMinutes(15));
    for (int i = 1; i < ClientGuardService.MAX_NOT_FOUND; i++) guard.notFound(VISITOR, "/typo");
    Thread.sleep(300);

    assertFalse(guard.notFound(VISITOR, "/typo"));
    guard.checkBanned(VISITOR);
  }

  @Test
  public void theBanExpires() throws InterruptedException {
    ClientGuardService guard = new ClientGuardService(Duration.ofSeconds(10), Duration.ofMillis(200));
    for (int i = 0; i < ClientGuardService.MAX_NOT_FOUND; i++) guard.notFound(SCANNER, "/admin");
    assertThrows(RateLimiter.RateExceededException.class, () -> guard.checkBanned(SCANNER));

    Thread.sleep(300);
    guard.checkBanned(SCANNER);
    assertFalse("the count starts over after a ban", guard.notFound(SCANNER, "/admin"));
  }

  @Test
  public void aFifthConcurrentPasswordCheckIsBusy() {
    ClientGuardService guard = new ClientGuardService();
    java.util.List<ClientGuardService.PasswordCheck> running = new java.util.ArrayList<>();
    for (int i = 0; i < ClientGuardService.MAX_CONCURRENT_CHECKS; i++) running.add(guard.startPasswordCheck("10.0.0." + i));

    assertThrows(ClientGuardService.Busy.class, () -> guard.startPasswordCheck(VISITOR));
    running.get(0).close();
    running.get(0).close(); // twice frees one slot, not two
    running.add(guard.startPasswordCheck(VISITOR));
    assertThrows(ClientGuardService.Busy.class, () -> guard.startPasswordCheck(SCANNER));
  }

  @Test
  public void busyCountsNoAttempt() {
    ClientGuardService guard = new ClientGuardService();
    java.util.List<ClientGuardService.PasswordCheck> running = new java.util.ArrayList<>();
    for (int i = 0; i < ClientGuardService.MAX_CONCURRENT_CHECKS; i++) running.add(guard.startPasswordCheck("10.0.0." + i));
    for (int i = 0; i < 3 * ClientGuardService.MAX_FAILED_LOGINS; i++) {
      assertThrows(ClientGuardService.Busy.class, () -> guard.startPasswordCheck(VISITOR));
    }
    running.forEach(ClientGuardService.PasswordCheck::close);

    for (int i = 0; i < ClientGuardService.MAX_FAILED_LOGINS; i++) guard.startPasswordCheck(VISITOR).close();
  }

  @Test
  public void anAttemptCountsAsFailedUntilItSucceeds() {
    ClientGuardService guard = new ClientGuardService();
    // Started, not finished: they already count, so parallel ones can't all get through.
    java.util.List<ClientGuardService.PasswordCheck> unfinished = new java.util.ArrayList<>();
    for (int i = 0; i < ClientGuardService.MAX_CONCURRENT_CHECKS; i++) unfinished.add(guard.startPasswordCheck(VISITOR));
    unfinished.forEach(ClientGuardService.PasswordCheck::close);
    guard.startPasswordCheck(VISITOR).close();

    assertThrows(RateLimiter.RateExceededException.class, () -> guard.startPasswordCheck(VISITOR));
    // Refused: no slot kept.
    for (int i = 0; i < ClientGuardService.MAX_CONCURRENT_CHECKS; i++) guard.startPasswordCheck("10.0.0." + i).close();
  }

  @Test
  public void aSuccessForgetsTheFailures() {
    ClientGuardService guard = new ClientGuardService();
    for (int i = 0; i < ClientGuardService.MAX_FAILED_LOGINS - 1; i++) guard.startPasswordCheck(VISITOR).close();
    try (ClientGuardService.PasswordCheck check = guard.startPasswordCheck(VISITOR)) {
      check.succeeded();
    }
    for (int i = 0; i < ClientGuardService.MAX_FAILED_LOGINS; i++) guard.startPasswordCheck(VISITOR).close();
  }

  @Test
  public void ipv6AddressesCountByTheirSlash64() {
    ClientGuardService guard = new ClientGuardService();
    for (int i = 0; i < ClientGuardService.MAX_FAILED_LOGINS; i++) guard.startPasswordCheck("2001:db8:1:2::" + Integer.toHexString(i + 1)).close();

    assertThrows(RateLimiter.RateExceededException.class, () -> guard.startPasswordCheck("2001:db8:1:2:ffff:ffff:ffff:ffff"));
    guard.startPasswordCheck("2001:db8:1:3::1").close();
  }

  @Test
  public void aScannerHoppingWithinItsSlash64IsBanned() {
    ClientGuardService guard = new ClientGuardService();
    for (int i = 0; i < ClientGuardService.MAX_NOT_FOUND; i++) guard.notFound("2001:db8:aa:bb::" + (i + 1), "/.env");

    assertThrows(RateLimiter.RateExceededException.class, () -> guard.checkBanned("2001:db8:aa:bb:1234::9"));
    guard.checkBanned("2001:db8:aa:bc::1");
  }

  @Test
  public void addressKeys() {
    assertEquals("203.0.113.7", ClientGuardService.addressKey("203.0.113.7"));
    assertEquals("2001:db8:1:2::/64", ClientGuardService.addressKey("2001:db8:1:2:3:4:5:6"));
    assertEquals("2001:db8:0:0::/64", ClientGuardService.addressKey("2001:db8::1"));
    assertEquals("2001:db8:0:0::/64", ClientGuardService.addressKey("2001:DB8::1%eth0"));
    assertEquals("203.0.113.7", ClientGuardService.addressKey("::ffff:203.0.113.7"));
    // Not an address: as is, never looked up.
    assertEquals("example.com", ClientGuardService.addressKey("example.com"));
    assertEquals("evil.example:80", ClientGuardService.addressKey("evil.example:80"));
    assertEquals(null, ClientGuardService.addressKey(null));
  }

  @Test
  public void remembersAddressesThatForwardOnceTheyDo() {
    ClientGuardService guard = new ClientGuardService();
    guard.countRequest(VISITOR, null);
    assertTrue("no header yet: not tracked", guard.forwarders().isEmpty());

    guard.countRequest("172.18.0.1", "203.0.113.7");
    guard.countRequest("172.18.0.1", "198.51.100.1, 203.0.113.8");
    guard.countRequest("172.18.0.1", "203.0.113.7");
    guard.countRequest("172.18.0.1", null);

    ClientGuardService.ForwarderStats proxy = guard.forwarders().get(0);
    assertEquals("172.18.0.1", proxy.address());
    assertEquals(4, proxy.requests());
    assertEquals(3, proxy.forwarded());
    assertEquals("the rightmost hop of each", 2, proxy.clients());
  }

  @Test
  public void forwardedClientsAreCountedUpToTheCap() {
    ClientGuardService guard = new ClientGuardService();
    for (int i = 0; i < ClientGuardService.MAX_FORWARDED_CLIENTS + 50; i++) guard.countRequest("172.18.0.1", "10.0." + i / 256 + "." + i % 256);
    assertEquals(ClientGuardService.MAX_FORWARDED_CLIENTS, guard.forwarders().get(0).clients());
  }

  @Test
  public void aProxyInUseStaysWhateverAddressesComeAndGo() {
    ClientGuardService guard = new ClientGuardService();
    for (int i = 0; i < 20; i++) guard.countRequest("172.18.0.1", "203.0.113.7");
    for (int i = 0; i < 5 * ClientGuardService.MAX_FORWARDERS; i++) guard.countRequest("198.51.100." + i, "1.2.3.4");

    assertEquals(ClientGuardService.MAX_FORWARDERS, guard.forwarders().size());
    assertEquals("the most forwarding first", "172.18.0.1", guard.forwarders().get(0).address());
  }
}
