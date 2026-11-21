package net.pieroxy.notes.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RateLimiterTest {

  private static void assertRefused(RateLimiter limiter, String key) {
    try {
      limiter.check(key);
      fail(key + " should be over the limit");
    } catch (RateLimiter.RateExceededException expected) {
      // expected
    }
  }

  @Test
  public void checkRefusesOnceTheKeyHasMaxHitsCaseInsensitively() {
    RateLimiter limiter = new RateLimiter(60_000, 2);
    limiter.hit("admin");
    limiter.check("admin");
    limiter.hit(" Admin ");
    try {
      limiter.check("ADMIN");
      fail("should be over the limit");
    } catch (RateLimiter.RateExceededException expected) {
      assertTrue("retry once the oldest hit expires", expected.getRetryAfterMillis() > 59_000 && expected.getRetryAfterMillis() <= 60_000);
    }
    limiter.check("other");
  }

  @Test
  public void checkAddsNoHit() {
    RateLimiter limiter = new RateLimiter(60_000, 1);
    for (int i = 0; i < 10; i++) limiter.check("admin");
    assertEquals(0, limiter.getNbTimeStamps());
  }

  @Test
  public void resetClearsTheKey() {
    RateLimiter limiter = new RateLimiter(60_000, 1);
    limiter.hit("admin");
    assertRefused(limiter, "admin");

    limiter.reset("ADMIN");

    limiter.check("admin");
  }

  @Test
  public void allowsAgainOnceTheWindowHasPassed() throws InterruptedException {
    RateLimiter limiter = new RateLimiter(50, 1);
    limiter.hit("admin");
    assertRefused(limiter, "admin");
    Thread.sleep(80);
    limiter.check("admin");
  }

  @Test
  public void dropsKeysWithNoHitLeftInTheWindow() throws InterruptedException {
    RateLimiter limiter = new RateLimiter(50, 5);
    limiter.hit("a");
    limiter.hit("b");
    assertEquals(2, limiter.getNbKeys());
    Thread.sleep(80);
    limiter.hit("c");
    assertEquals(1, limiter.getNbKeys());
    assertEquals(1, limiter.getNbTimeStamps());
  }

  @Test
  public void tryHitLetsNoMoreThanTheHitsLeftThroughConcurrently() throws Exception {
    RateLimiter limiter = new RateLimiter(60_000, 5);
    java.util.concurrent.atomic.AtomicInteger passed = new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    java.util.List<Thread> threads = new java.util.ArrayList<>();
    for (int i = 0; i < 50; i++) {
      Thread thread = new Thread(() -> {
        try {
          start.await();
          limiter.tryHit("client");
          passed.incrementAndGet();
        } catch (RateLimiter.RateExceededException | InterruptedException expected) {
          // over the limit
        }
      });
      thread.start();
      threads.add(thread);
    }
    start.countDown();
    for (Thread thread : threads) thread.join();
    assertEquals(5, passed.get());
  }
}
