package net.pieroxy.notes.android;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The web app the webview runs, and its updates. The copy in use opens at once, offline too; a newer
 * build is downloaded in the background and kept ready. It's switched to when the page loads again:
 * after a server upgrade, the old page's API calls are refused (409), and the web app offers to
 * reload; on that reload, the new build is checked for and downloaded first if needed.
 */
final class WebApp {
  private static final Logger LOG = Logger.getLogger(WebApp.class.getName());
  /** How long a reload waits for the new build: past that, the page in use loads. */
  private static final long RELOAD_WAIT_SECONDS = 60;

  final String server;
  final Routes routes;
  private final WebAppStore store;
  private final ExecutorService executor;
  private WebAppCopy current;
  /** Downloaded, newer than {@link #current}: used at the next page load. */
  private WebAppCopy ready;
  private Future<?> check;

  WebApp(WebAppStore.Installed installed, WebAppStore store, ExecutorService executor) {
    this.server = installed.server;
    this.routes = new Routes(server);
    this.current = installed.copy;
    this.store = store;
    this.executor = executor;
  }

  synchronized WebAppCopy current() {
    return current;
  }

  /** Looks for a new build in the background, unless that's under way already. */
  synchronized Future<?> checkForUpdate() {
    if (check == null || check.isDone()) check = executor.submit(this::runCheck);
    return check;
  }

  /**
   * The copy to load the page from, switching to a new build when there's one. Called from the
   * webview's request thread, so it may wait.
   *
   * @param reload whether the page was loaded before: then it's looked for first, at the server.
   */
  WebAppCopy copyForPage(boolean reload) {
    if (reload) {
      try {
        checkForUpdate().get(RELOAD_WAIT_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        LOG.log(Level.INFO, "No new build for this load", e);
      }
    }
    synchronized (this) {
      if (ready != null) {
        try {
          store.use(server, ready);
          current = ready;
        } catch (IOException e) {
          LOG.log(Level.WARNING, "Couldn't switch to the new build", e);
        }
        ready = null;
      }
      return current;
    }
  }

  private void runCheck() {
    try {
      AppManifest manifest = store.fetchManifest(server);
      synchronized (this) {
        if (manifest.build.equals(current.build())) {
          ready = null;
          return;
        }
        if (ready != null && manifest.build.equals(ready.build())) return;
      }
      WebAppCopy copy = store.download(server, manifest);
      synchronized (this) {
        ready = copy;
      }
    } catch (IOException | AppManifest.InvalidManifestException e) {
      LOG.log(Level.INFO, "Couldn't check for a new build", e);
    }
  }
}
