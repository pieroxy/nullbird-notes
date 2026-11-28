package net.pieroxy.notes.android;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WebAppStoreTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private String[] children(File dir) {
    String[] names = dir.list();
    Arrays.sort(names);
    return names;
  }

  @Test
  public void aDownloadedCopyIsUsedOnceInstalled() throws Exception {
    TestServer server = new TestServer("b1");
    WebAppStore store = new WebAppStore(tmp.getRoot(), server);
    assertNull(store.installed());

    WebAppCopy copy = store.download(TestServer.ADDRESS, store.fetchManifest(TestServer.ADDRESS));
    assertNull("Not before it's used", store.installed());
    store.use(TestServer.ADDRESS, copy);

    WebAppStore.Installed installed = new WebAppStore(tmp.getRoot(), server).installed();
    assertNotNull(installed);
    assertEquals(TestServer.ADDRESS, installed.server);
    assertEquals("b1", installed.copy.build());
    for (var file : server.files.entrySet()) {
      assertArrayEquals(file.getValue(), Files.readAllBytes(installed.copy.file(installed.copy.entry(file.getKey())).toPath()));
    }
  }

  @Test
  public void aFileThatDoesntMatchItsHashFailsTheWholeDownload() throws Exception {
    TestServer server = new TestServer("b1");
    WebAppStore store = new WebAppStore(tmp.getRoot(), server);
    AppManifest manifest = store.fetchManifest(TestServer.ADDRESS);
    server.tampered.put("icons/icon-192.png", new byte[]{1, 2, 4});
    assertThrows(WebAppStore.CorruptDownloadException.class, () -> store.download(TestServer.ADDRESS, manifest));
    server.tampered.put("icons/icon-192.png", new byte[]{1, 2, 3, 4});
    assertThrows(WebAppStore.CorruptDownloadException.class, () -> store.download(TestServer.ADDRESS, manifest));
    assertEquals("Nothing left behind", 0, children(tmp.getRoot()).length);
  }

  @Test
  public void usingANewCopyDeletesTheOldOne() throws Exception {
    TestServer server = new TestServer("b1");
    WebAppStore store = new WebAppStore(tmp.getRoot(), server);
    WebAppCopy first = store.download(TestServer.ADDRESS, store.fetchManifest(TestServer.ADDRESS));
    store.use(TestServer.ADDRESS, first);
    server.setBuild("b2");
    WebAppCopy second = store.download(TestServer.ADDRESS, store.fetchManifest(TestServer.ADDRESS));
    assertTrue(first.dir.isDirectory());
    store.use(TestServer.ADDRESS, second);
    assertTrue(second.dir.isDirectory());
    assertTrue(!first.dir.exists());
    assertEquals("b2", store.installed().copy.build());
  }

  @Test
  public void aBuildOnTheDeviceAlreadyIsntDownloadedAgain() throws Exception {
    TestServer server = new TestServer("b1");
    WebAppStore store = new WebAppStore(tmp.getRoot(), server);
    AppManifest manifest = store.fetchManifest(TestServer.ADDRESS);
    store.download(TestServer.ADDRESS, manifest);
    int before = server.fetched.size();
    store.download(TestServer.ADDRESS, manifest);
    assertEquals(before, server.fetched.size());
  }

  @Test
  public void leftoverDownloadsAreDeletedAndClearDeletesEverything() throws Exception {
    assertTrue(new File(tmp.getRoot(), "tmp-leftover/files").mkdirs());
    TestServer server = new TestServer("b1");
    WebAppStore store = new WebAppStore(tmp.getRoot(), server);
    assertEquals(0, children(tmp.getRoot()).length);
    store.use(TestServer.ADDRESS, store.download(TestServer.ADDRESS, store.fetchManifest(TestServer.ADDRESS)));
    store.clear();
    assertEquals(0, children(tmp.getRoot()).length);
    assertNull(store.installed());
  }

  @Test
  public void anythingButAManifestIsntANotesServer() throws Exception {
    TestServer server = new TestServer("b1") {
      @Override
      String manifest() {
        return "<html>Welcome to nginx</html>";
      }
    };
    WebAppStore store = new WebAppStore(tmp.getRoot(), server);
    assertThrows(AppManifest.InvalidManifestException.class, () -> store.fetchManifest(TestServer.ADDRESS));
    assertThrows(Fetcher.NotFoundException.class, () -> store.fetchManifest("https://other.example.com"));
  }
}
