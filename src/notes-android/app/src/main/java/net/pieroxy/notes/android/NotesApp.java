package net.pieroxy.notes.android;

import android.app.Application;
import android.webkit.CookieManager;
import android.webkit.WebStorage;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Holds the store and the web app in use, for every screen. */
public final class NotesApp extends Application {
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private WebAppStore store;
  private WebApp webApp;

  @Override
  public void onCreate() {
    super.onCreate();
    store = new WebAppStore(new File(getFilesDir(), "webapp"), new HttpsFetcher(15_000, 30_000));
  }

  static NotesApp of(android.content.Context context) {
    return (NotesApp) context.getApplicationContext();
  }

  WebAppStore store() {
    return store;
  }

  /** @return the web app in use, or null: no server set up yet. */
  synchronized WebApp webApp() {
    if (webApp == null) {
      WebAppStore.Installed installed = store.installed();
      if (installed != null) webApp = new WebApp(installed, store, executor);
    }
    return webApp;
  }

  /** Installs {@code copy} for {@code server}, in place of what was there. */
  synchronized void install(String server, WebAppCopy copy) throws IOException {
    store.use(server, copy);
    webApp = null;
  }

  /**
   * Forgets the server: its web app, and the webview's data (notes on the device, the session).
   * Each server has its own origin, so its data wouldn't show for another one: this is for privacy.
   */
  synchronized void forgetServer() {
    WebStorage.getInstance().deleteAllData();
    CookieManager cookies = CookieManager.getInstance();
    cookies.removeAllCookies(null);
    cookies.flush();
    store.clear();
    webApp = null;
  }
}
