package net.pieroxy.notes.android;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The server's address as the user types it, made into the form the app and the web app use:
 * {@code https://host[:port]}, with no path and no trailing slash (see Platform.ts in the web app).
 */
final class ServerAddress {
  private ServerAddress() {}

  /**
   * @return {@code input} as https://host[:port]: https:// added when no scheme is given, any path,
   *     query or fragment removed, the host in lowercase, port 443 left out.
   * @throws IllegalArgumentException with a message for the user, if it isn't an https address.
   */
  static String normalize(String input) {
    String text = input == null ? "" : input.trim();
    if (text.isEmpty()) throw new IllegalArgumentException("Enter your server's address.");
    if (!text.contains("://")) text = "https://" + text;
    URI uri;
    try {
      uri = new URI(text);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("This isn't a valid address.");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("https")) throw new IllegalArgumentException("Only https:// addresses are allowed.");
    String host = uri.getHost();
    if (host == null || host.isEmpty() || uri.getRawUserInfo() != null) throw new IllegalArgumentException("This isn't a valid address.");
    int port = uri.getPort();
    return "https://" + host.toLowerCase(Locale.ROOT) + (port == -1 || port == 443 ? "" : ":" + port);
  }
}
