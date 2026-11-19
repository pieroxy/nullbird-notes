package net.pieroxy.notes.api;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WebServerStartTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void aPortAlreadyInUseFailsTheStartup() throws Exception {
    try (ServerSocket taken = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
      new TestServer(tmp.getRoot(), Map.of(), taken.getLocalPort()).close();
      fail("Started on a port already in use");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("Address already in use"));
    }
  }
}
