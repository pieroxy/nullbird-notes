package net.pieroxy.notes.android;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RoutesTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private WebAppCopy copy() throws Exception {
    return new WebAppCopy(tmp.getRoot(), AppManifest.parse(new TestServer("b1").manifest()));
  }

  @Test
  public void theManifestsFilesComeFromTheDevice() throws Exception {
    Routes routes = new Routes("https://notes.example.com");
    WebAppCopy copy = copy();
    assertEquals("index.html", routes.localPath("GET", "https://notes.example.com/", copy));
    assertEquals("index.html", routes.localPath("GET", "https://notes.example.com", copy));
    assertEquals("index.html", routes.localPath("GET", "https://NOTES.example.com:443/index.html?x=1#!/note", copy));
    assertEquals("icons/icon-192.png", routes.localPath("GET", "https://notes.example.com/icons/icon-192.png", copy));
    assertEquals("nullbird-native.js", routes.localPath("GET", "https://notes.example.com/nullbird-native.js", copy));
  }

  @Test
  public void theRestGoesToTheServer() throws Exception {
    Routes routes = new Routes("https://notes.example.com");
    WebAppCopy copy = copy();
    assertNull(routes.localPath("POST", "https://notes.example.com/", copy));
    assertNull(routes.localPath("GET", "https://notes.example.com/api/changes", copy));
    assertNull(routes.localPath("GET", "https://notes.example.com/fonts/abc.woff2", copy));
    assertNull(routes.localPath("GET", "https://notes.example.com/sw.js", copy));
    assertNull(routes.localPath("GET", "https://notes.example.com:8443/", copy));
    assertNull(routes.localPath("GET", "http://notes.example.com/", copy));
    assertNull(routes.localPath("GET", "https://evil.example.com/index.html", copy));
  }

  @Test
  public void onlyTheAppsPageStaysInTheWebview() {
    Routes routes = new Routes("https://notes.example.com:8443");
    assertTrue(routes.isAppPage("https://notes.example.com:8443/"));
    assertTrue(routes.isAppPage("https://notes.example.com:8443/index.html#!/settings"));
    assertFalse(routes.isAppPage("https://notes.example.com:8443/p/AAAAAAAAAAAAAAAAAAAAAA"));
    assertFalse(routes.isAppPage("https://notes.example.com:8443/export"));
    assertFalse(routes.isAppPage("https://notes.example.com/"));
    assertFalse(routes.isAppPage("https://example.org/"));
    assertFalse(routes.isAppPage("mailto:someone@example.org"));
  }
}
