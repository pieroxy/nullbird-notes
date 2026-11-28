package net.pieroxy.notes.android;

import java.net.URI;
import java.util.Locale;

/**
 * Which requests the app answers from the device, and which pages the webview shows. The webview
 * runs the web app at the server's own address: the manifest's files come from the copy on the
 * device, the rest (the API, the installed fonts) from the server, untouched.
 */
final class Routes {
  private final String host;
  private final int port;

  /** @param server as https://host[:port] (see ServerAddress). */
  Routes(String server) {
    URI uri = URI.create(server);
    host = uri.getHost().toLowerCase(Locale.ROOT);
    port = portOf(uri);
  }

  /**
   * @return the path in the manifest this request is for, {@link AppManifest#INDEX} for the page
   *     itself, {@link Bridge#PATH} for the bridge; null if it isn't a GET on the server for a file of
   *     the copy.
   */
  String localPath(String method, String url, WebAppCopy copy) {
    if (!"GET".equalsIgnoreCase(method)) return null;
    String path = pathOnServer(url);
    if (path == null) return null;
    if (isPage(path)) return AppManifest.INDEX;
    String relative = path.substring(1);
    if (relative.equals(Bridge.PATH)) return Bridge.PATH;
    return copy.entry(relative) != null ? relative : null;
  }

  /** @return whether the webview shows this page: the web app's, on the server, and nothing else. */
  boolean isAppPage(String url) {
    String path = pathOnServer(url);
    return path != null && isPage(path);
  }

  private static boolean isPage(String path) {
    return path.equals("/") || path.equals("/" + AppManifest.INDEX);
  }

  /** @return the decoded path, starting with /, if {@code url} is on the server; else null. */
  private String pathOnServer(String url) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (Exception e) {
      return null;
    }
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) return null;
    if (!uri.getHost().toLowerCase(Locale.ROOT).equals(host) || portOf(uri) != port) return null;
    String path = uri.getPath();
    return path == null || path.isEmpty() ? "/" : path;
  }

  private static int portOf(URI uri) {
    return uri.getPort() == -1 ? 443 : uri.getPort();
  }
}
