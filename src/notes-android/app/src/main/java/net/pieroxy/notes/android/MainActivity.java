package net.pieroxy.notes.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** The web app, in a webview at the server's address, with its files from the device. */
public final class MainActivity extends Activity {
  /** The oldest Chrome the web app runs on (see compat.js in the web app): WebView is Chrome. */
  private static final int MIN_WEBVIEW = 111;
  private WebView webView;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    WebApp webApp = NotesApp.of(this).webApp();
    if (webApp == null) {
      startActivity(new Intent(this, SetupActivity.class));
      finish();
      return;
    }
    if (webViewMajorVersion() < MIN_WEBVIEW) {
      TextView message = new TextView(this);
      int padding = Math.round(24 * getResources().getDisplayMetrics().density);
      message.setPadding(padding, padding, padding, padding);
      message.setText(getString(R.string.webview_too_old, MIN_WEBVIEW));
      setContentView(message);
      return;
    }
    webView = createWebView(webApp);
    setContentView(webView);
    webView.loadUrl(webApp.server + "/");
    webApp.checkForUpdate();
  }

  /** @return the WebView's major version, or MAX_VALUE if it can't be told: then it's tried. */
  private static int webViewMajorVersion() {
    PackageInfo info = WebView.getCurrentWebViewPackage();
    if (info == null || info.versionName == null) return Integer.MAX_VALUE;
    try {
      return Integer.parseInt(info.versionName.split("\\.")[0]);
    } catch (NumberFormatException e) {
      return Integer.MAX_VALUE;
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  private WebView createWebView(WebApp webApp) {
    if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true);
    WebView view = new WebView(this);
    view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    WebSettings settings = view.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

    view.setWebViewClient(new Client(webApp));
    return view;
  }

  @Override
  protected void onResume() {
    super.onResume();
    WebApp webApp = NotesApp.of(this).webApp();
    if (webView != null && webApp != null) webApp.checkForUpdate();
  }

  @Override
  public void onBackPressed() {
    if (webView != null && webView.canGoBack()) webView.goBack();
    else super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    if (webView != null) {
      webView.destroy();
      webView = null;
    }
    super.onDestroy();
  }

  private final class Client extends WebViewClient {
    private final WebApp webApp;
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    Client(WebApp webApp) {
      this.webApp = webApp;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
      String url = request.getUrl().toString();
      String path = webApp.routes.localPath(request.getMethod(), url, webApp.current());
      if (path == null) return null;
      if (path.equals(Bridge.PATH)) return respond("text/javascript", "utf-8", webApp.current(), new ByteArrayInputStream(Bridge.script(webApp.server)));
      WebAppCopy copy = path.equals(AppManifest.INDEX) && request.isForMainFrame()
          ? webApp.copyForPage(loaded.getAndSet(true))
          : webApp.current();
      AppManifest.AppFile entry = copy.entry(path);
      if (entry == null) return null;
      try {
        if (path.equals(AppManifest.INDEX)) {
          // Read as UTF-8: the web app's build writes it so, and says so in its <meta charset>.
          String html = new String(Files.readAllBytes(copy.file(entry).toPath()), StandardCharsets.UTF_8);
          byte[] page = Bridge.inject(html).getBytes(StandardCharsets.UTF_8);
          return respond(entry.mimeType(), "utf-8", copy, new ByteArrayInputStream(page));
        }
        return respond(entry.mimeType(), entry.charset(), copy, new FileInputStream(copy.file(entry)));
      } catch (IOException e) {
        return null;
      }
    }

    /** With the headers the server would send (see SecurityHeadersFilter): the CSP first. */
    private WebResourceResponse respond(String mimeType, String charset, WebAppCopy copy, InputStream body) {
      Map<String, String> headers = new HashMap<>(copy.manifest.headers);
      headers.put("Cache-Control", "no-cache");
      return new WebResourceResponse(mimeType, charset, 200, "OK", headers, body);
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
      if (webApp.routes.isAppPage(request.getUrl().toString())) return false;
      try {
        startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()).addCategory(Intent.CATEGORY_BROWSABLE));
      } catch (ActivityNotFoundException ignored) {
        // Nothing to open it with.
      }
      return true;
    }

    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
      if (view == webView) {
        ((ViewGroup) view.getParent()).removeView(view);
        view.destroy();
        webView = null;
        recreate();
      }
      return true;
    }
  }

  static Intent restart(Activity from) {
    return new Intent(from, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
  }

}
