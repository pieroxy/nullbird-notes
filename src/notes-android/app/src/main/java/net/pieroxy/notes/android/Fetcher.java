package net.pieroxy.notes.android;

import java.io.IOException;
import java.io.InputStream;

/** Gets a file from the server: HTTPS in the app, a fake in the tests. */
interface Fetcher {
  /**
   * @param url an https:// address.
   * @return the response's body, for a 200.
   * @throws NotFoundException for any other status, IOException if the server can't be reached.
   */
  InputStream open(String url) throws IOException;

  final class NotFoundException extends IOException {
    NotFoundException(String message) {
      super(message);
    }
  }
}
