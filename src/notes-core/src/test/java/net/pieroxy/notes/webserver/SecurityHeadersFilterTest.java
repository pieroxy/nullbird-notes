package net.pieroxy.notes.webserver;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SecurityHeadersFilterTest {
  @Test
  public void onlyHashedBundlesAreCachedForGood() {
    assertEquals("public, max-age=31536000, immutable", SecurityHeadersFilter.cacheControl("/bundle-3a4b24.js"));
    assertEquals("public, max-age=31536000, immutable", SecurityHeadersFilter.cacheControl("/bundle-3a4b24.css.map"));
    assertEquals("no-cache", SecurityHeadersFilter.cacheControl("/"));
    assertEquals("no-cache", SecurityHeadersFilter.cacheControl("/index.html"));
    assertEquals("no-cache", SecurityHeadersFilter.cacheControl("/icons/icon-192.png"));
    // A published note: unpublished, it's gone.
    assertEquals("no-store", SecurityHeadersFilter.cacheControl("/p/AAAAAAAAAAAAAAAAAAAAAA"));
    // The browser must see a new service worker at once.
    assertEquals("no-cache", SecurityHeadersFilter.cacheControl("/sw.js"));
    assertEquals("no-cache", SecurityHeadersFilter.cacheControl("/bundle-xyz.js"));
    assertEquals("no-cache", SecurityHeadersFilter.cacheControl("/icons/bundle-3a4b24.js"));
  }

  @Test
  public void apiResponsesAreNeverStored() {
    assertEquals("no-store", SecurityHeadersFilter.cacheControl("/api/Changes"));
  }
}
