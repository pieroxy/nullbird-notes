package net.pieroxy.notes.android;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Tells the web app it's in the Android app: {@code window.NullbirdNative} (see Platform.ts in the
 * web app), defined before the page's own script runs.
 * <p>
 * The app serves it as a script at {@link #PATH} on the server's origin, and adds it to the page as
 * it serves index.html: the CSP allows the server's own scripts, not inline ones. This works with any
 * WebView, where document-start scripts need a recent one.
 */
final class Bridge {
  /** On the server's origin, answered by the app: the server has nothing there. */
  static final String PATH = "nullbird-native.js";
  private static final String TAG = "<script src=/" + PATH + "></script>";

  private Bridge() {}

  static byte[] script(String server) {
    String js = "window.NullbirdNative = Object.freeze({ platform: \"android\", serverUrl: " + quote(server) + " });\n";
    return js.getBytes(StandardCharsets.UTF_8);
  }

  /** @return {@code html} with the bridge's script before its first script, so that it runs first. */
  static String inject(String html) {
    String lower = html.toLowerCase(Locale.ROOT);
    int at = lower.indexOf("<script");
    if (at < 0) at = lower.indexOf("</head>");
    if (at < 0) at = 0;
    return html.substring(0, at) + TAG + html.substring(at);
  }

  /** A JavaScript string literal: the server's address, checked already, but escaped anyway. */
  private static String quote(String text) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"' || c == '\\' || c == '/') out.append('\\').append(c);
      else if (c < 0x20 || c == 0x2028 || c == 0x2029) out.append(String.format("\\u%04x", (int) c));
      else out.append(c);
    }
    return out.append('"').toString();
  }
}
