package net.pieroxy.notes.api;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import net.pieroxy.notes.api.metadata.RequestTooLargeException;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class LimitedInputStreamTest {
  private static ServletInputStream servletStream(byte[] content) {
    ByteArrayInputStream in = new ByteArrayInputStream(content);
    return new ServletInputStream() {
      @Override
      public int read() {
        return in.read();
      }

      @Override
      public int read(byte[] buffer, int offset, int length) {
        return in.read(buffer, offset, length);
      }

      @Override
      public boolean isFinished() {
        return in.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener readListener) {
        throw new UnsupportedOperationException();
      }
    };
  }

  private static SizeLimitedRequest.LimitedInputStream limited(int size, long maxBytes) {
    return new SizeLimitedRequest.LimitedInputStream(servletStream(new byte[size]), maxBytes);
  }

  @Test
  public void readsABodyUpToTheLimit() throws IOException {
    assertArrayEquals(new byte[100], limited(100, 100).readAllBytes());
  }

  @Test
  public void throwsOnceTheLimitIsExceededInBulk() {
    assertThrows(RequestTooLargeException.class, () -> limited(101, 100).readAllBytes());
  }

  @Test
  public void throwsOnceTheLimitIsExceededByteByByte() throws IOException {
    SizeLimitedRequest.LimitedInputStream in = limited(3, 2);
    assertEquals(0, in.read());
    assertEquals(0, in.read());
    assertThrows(RequestTooLargeException.class, in::read);
  }

  @Test
  public void anEmptyBodyIsFine() throws IOException {
    assertEquals(-1, limited(0, 0).read());
  }
}
