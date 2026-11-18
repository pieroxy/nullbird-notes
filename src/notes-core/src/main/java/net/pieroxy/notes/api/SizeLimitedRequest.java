package net.pieroxy.notes.api;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import net.pieroxy.notes.api.metadata.RequestTooLargeException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Throws {@link RequestTooLargeException} as soon as the body is found to exceed {@code maxBytes}:
 * up front from {@code Content-Length}, and while reading otherwise (chunked or lying requests).
 */
class SizeLimitedRequest extends HttpServletRequestWrapper {
  private final long maxBytes;
  private ServletInputStream limited;
  private BufferedReader reader;

  SizeLimitedRequest(HttpServletRequest request, long maxBytes) {
    super(request);
    this.maxBytes = maxBytes;
  }

  @Override
  public ServletInputStream getInputStream() throws IOException {
    if (limited == null) {
      if (getContentLengthLong() > maxBytes) throw new RequestTooLargeException(maxBytes);
      limited = new LimitedInputStream(super.getInputStream(), maxBytes);
    }
    return limited;
  }

  @Override
  public BufferedReader getReader() throws IOException {
    if (reader == null) {
      String encoding = getCharacterEncoding();
      Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
      reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
    return reader;
  }

  static class LimitedInputStream extends ServletInputStream {
    private final ServletInputStream in;
    private final long maxBytes;
    private long read;

    LimitedInputStream(ServletInputStream in, long maxBytes) {
      this.in = in;
      this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
      int b = in.read();
      if (b >= 0) count(1);
      return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int n = in.read(buffer, offset, length);
      if (n > 0) count(n);
      return n;
    }

    private void count(int n) {
      read += n;
      if (read > maxBytes) throw new RequestTooLargeException(maxBytes);
    }

    @Override
    public boolean isFinished() {
      return in.isFinished();
    }

    @Override
    public boolean isReady() {
      return in.isReady();
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      in.setReadListener(readListener);
    }
  }
}
