package net.pieroxy.notes.android;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Over HTTPS only: redirects are followed only to https addresses, and certificates aren't negotiable. */
final class HttpsFetcher implements Fetcher {
  private final int connectTimeoutMs;
  private final int readTimeoutMs;

  HttpsFetcher(int connectTimeoutMs, int readTimeoutMs) {
    this.connectTimeoutMs = connectTimeoutMs;
    this.readTimeoutMs = readTimeoutMs;
  }

  @Override
  public InputStream open(String url) throws IOException {
    if (!url.startsWith("https://")) throw new IOException("Not https: " + url);
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(connectTimeoutMs);
    connection.setReadTimeout(readTimeoutMs);
    connection.setUseCaches(false);
    connection.setRequestProperty("Accept-Encoding", "identity");
    int status;
    try {
      status = connection.getResponseCode();
    } catch (IOException e) {
      connection.disconnect();
      throw e;
    }
    if (!"https".equals(connection.getURL().getProtocol()) || status != HttpURLConnection.HTTP_OK) {
      connection.disconnect();
      throw new NotFoundException("HTTP " + status + " for " + url);
    }
    return new FilterInputStream(connection.getInputStream()) {
      @Override
      public void close() throws IOException {
        try {
          super.close();
        } finally {
          connection.disconnect();
        }
      }
    };
  }
}
