package net.pieroxy.notes.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AppManifestTest {
  @Test
  public void aServersManifestIsRead() throws Exception {
    AppManifest manifest = AppManifest.parse(new TestServer("1.3.0+abc").manifest());
    assertEquals("1.3.0+abc", manifest.build);
    assertEquals(3, manifest.files.size());
    AppManifest.AppFile index = manifest.files.get("index.html");
    assertEquals("text/html", index.mimeType());
    assertEquals("utf-8", index.charset());
    assertNull(manifest.files.get("icons/icon-192.png").charset());
    assertEquals("default-src 'none'", manifest.headers.get("Content-Security-Policy"));
  }

  @Test
  public void pathsCantLeaveTheFolder() {
    assertTrue(AppManifest.isSafePath("index.html"));
    assertTrue(AppManifest.isSafePath("icons/icon-192.png"));
    assertTrue(AppManifest.isSafePath("licenses/third_party+notes.txt"));
    assertFalse(AppManifest.isSafePath(""));
    assertFalse(AppManifest.isSafePath("/index.html"));
    assertFalse(AppManifest.isSafePath("../index.html"));
    assertFalse(AppManifest.isSafePath("icons/../../x"));
    assertFalse(AppManifest.isSafePath("icons//x"));
    assertFalse(AppManifest.isSafePath("icons/"));
    assertFalse(AppManifest.isSafePath(".hidden"));
    assertFalse(AppManifest.isSafePath("a\\b"));
  }

  @Test
  public void anythingElseIsRefused() {
    assertThrows(AppManifest.InvalidManifestException.class, () -> AppManifest.parse("<html>not json</html>"));
    assertThrows(AppManifest.InvalidManifestException.class, () -> AppManifest.parse("{\"build\":\"x\"}"));
    String noIndex = "{\"build\":\"x\",\"headers\":{},\"files\":[{\"path\":\"a.js\",\"size\":1,\"sha256\":\"" + "0".repeat(64) + "\",\"type\":\"text/javascript\"}]}";
    assertThrows(AppManifest.InvalidManifestException.class, () -> AppManifest.parse(noIndex));
    String badPath = new TestServer("x").manifest().replace("icons/icon-192.png", "../evil");
    assertThrows(AppManifest.InvalidManifestException.class, () -> AppManifest.parse(badPath));
    String badHeader = new TestServer("x").manifest().replace("default-src 'none'", "a\\r\\nSet-Cookie: x");
    assertThrows(AppManifest.InvalidManifestException.class, () -> AppManifest.parse(badHeader));
  }
}
