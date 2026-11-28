package net.pieroxy.notes.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ServerAddressTest {
  @Test
  public void aBareHostGetsHttps() {
    assertEquals("https://notes.example.com", ServerAddress.normalize("  notes.example.com "));
  }

  @Test
  public void thePathQueryFragmentAndDefaultPortAreDropped() {
    assertEquals("https://notes.example.com", ServerAddress.normalize("HTTPS://Notes.Example.com:443/some/path/?q=1#x"));
    assertEquals("https://notes.example.com", ServerAddress.normalize("notes.example.com/"));
  }

  @Test
  public void anotherPortIsKept() {
    assertEquals("https://192.168.1.10:8443", ServerAddress.normalize("192.168.1.10:8443"));
  }

  @Test
  public void plainHttpAndOtherSchemesAreRefused() {
    assertThrows(IllegalArgumentException.class, () -> ServerAddress.normalize("http://notes.example.com"));
    assertThrows(IllegalArgumentException.class, () -> ServerAddress.normalize("ftp://notes.example.com"));
  }

  @Test
  public void nonsenseIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> ServerAddress.normalize(""));
    assertThrows(IllegalArgumentException.class, () -> ServerAddress.normalize("   "));
    assertThrows(IllegalArgumentException.class, () -> ServerAddress.normalize("not an address"));
    assertThrows(IllegalArgumentException.class, () -> ServerAddress.normalize("https://user:pass@notes.example.com"));
  }
}
