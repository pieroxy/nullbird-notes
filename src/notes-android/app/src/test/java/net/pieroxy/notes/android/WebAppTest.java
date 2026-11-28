package net.pieroxy.notes.android;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class WebAppTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private TestServer server;
  private WebAppStore store;
  private WebApp webApp;

  @Before
  public void setUp() throws Exception {
    server = new TestServer("b1");
    store = new WebAppStore(tmp.getRoot(), server);
    store.use(TestServer.ADDRESS, store.download(TestServer.ADDRESS, store.fetchManifest(TestServer.ADDRESS)));
    webApp = new WebApp(store.installed(), store, executor);
  }

  @After
  public void tearDown() {
    executor.shutdownNow();
  }

  @Test
  public void theFirstLoadDoesntWaitAndANewBuildIsUsedAtTheNext() throws Exception {
    server.setBuild("b2");
    server.down = true;
    assertEquals("Offline: the copy on the device", "b1", webApp.copyForPage(false).build());
    server.down = false;
    webApp.checkForUpdate().get(5, TimeUnit.SECONDS);
    assertEquals("Downloaded, not used yet", "b1", webApp.current().build());
    assertEquals("b2", webApp.copyForPage(false).build());
    assertEquals("Kept for the next launch", "b2", store.installed().copy.build());
  }

  @Test
  public void aReloadLooksForTheNewBuildFirst() {
    server.setBuild("b2");
    assertEquals("b2", webApp.copyForPage(true).build());
  }

  @Test
  public void aReloadOfflineKeepsTheCopyInUse() {
    server.setBuild("b2");
    server.down = true;
    assertEquals("b1", webApp.copyForPage(true).build());
  }

  @Test
  public void aBuildDownloadedThenRolledBackIsntUsed() throws Exception {
    server.setBuild("b2");
    webApp.checkForUpdate().get(5, TimeUnit.SECONDS);
    server.setBuild("b1");
    webApp.checkForUpdate().get(5, TimeUnit.SECONDS);
    assertEquals("b1", webApp.copyForPage(false).build());
  }
}
