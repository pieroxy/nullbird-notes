package net.pieroxy.notes.webserver;

import java.util.Map;

/** The web app's files' types, by extension: as the server sends them, and as the app manifest tells the native apps to. */
public final class MimeTypes {
  public static final Map<String, String> BY_EXTENSION = Map.of(
      "js", "application/javascript;charset=utf-8",
      "html", "text/html;charset=utf-8",
      "css", "text/css;charset=utf-8",
      "svg", "image/svg+xml",
      "png", "image/png",
      "ico", "image/x-icon",
      "webmanifest", "application/manifest+json",
      // The licenses (/licenses/).
      "txt", "text/plain;charset=utf-8");

  private MimeTypes() {}

  /** @return the type of a file named {@code name}, application/octet-stream if unknown. */
  public static String of(String name) {
    int dot = name.lastIndexOf('.');
    String type = dot < 0 ? null : BY_EXTENSION.get(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    return type == null ? "application/octet-stream" : type;
  }
}
