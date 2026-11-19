package net.pieroxy.notes.api;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AuthApiTest {
  private static final Map<String, String> USERS = Map.of("alice", "alice-password");

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private TestServer server;

  @After
  public void tearDown() {
    if (server != null) server.close();
  }

  private TestServer restart() throws Exception {
    server.close();
    server = new TestServer(tmp.getRoot(), USERS);
    return server;
  }

  @Test
  public void aSessionSurvivesAServerRestart() throws Exception {
    server = new TestServer(tmp.getRoot(), USERS);
    TestServer.Client laptop = server.client().login("alice", "alice-password");

    TestServer.Client sameLaptop = restart().client(laptop);
    assertTrue(sameLaptop.get("Session", "{}").result().get("authenticated").getAsBoolean());
    assertEquals(200, sameLaptop.get("ServerInfo", "{}").status());
  }

  @Test
  public void aLogoutSurvivesAServerRestart() throws Exception {
    server = new TestServer(tmp.getRoot(), USERS);
    TestServer.Client laptop = server.client().login("alice", "alice-password");
    String cookieBefore = laptop.sessionCookie();
    TestServer.Client copy = server.client();
    copy.setSessionCookie(cookieBefore);
    assertTrue(copy.get("Session", "{}").result().get("authenticated").getAsBoolean());
    laptop.post("Logout", "{}").result();

    TestServer.Client sameLaptop = restart().client(laptop);
    sameLaptop.setSessionCookie(cookieBefore);
    assertFalse(sameLaptop.get("Session", "{}").result().get("authenticated").getAsBoolean());
    assertEquals(401, sameLaptop.get("ServerInfo", "{}").status());
  }
}
