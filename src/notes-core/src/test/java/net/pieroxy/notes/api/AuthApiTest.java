package net.pieroxy.notes.api;

import net.pieroxy.notes.utils.BuildInfo;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
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

  @Test
  public void aWebAppOfAnotherBuildIsToldToReload() throws Exception {
    server = new TestServer(tmp.getRoot(), USERS);
    TestServer.Client laptop = server.client().login("alice", "alice-password");

    for (String build : Arrays.asList("0.0.1+0000000", null)) {
      TestServer.Response response = laptop.asBuild(build).get("ServerInfo", "{}");
      assertEquals(409, response.status());
      assertTrue(response.body(), response.json().get("error").getAsString().contains("reload"));
    }
    assertEquals(409, server.client().asBuild(null).post("Login", "{\"login\":\"alice\",\"password\":\"alice-password\"}").status());
    assertEquals(200, laptop.asBuild(BuildInfo.ID).get("ServerInfo", "{}").status());
  }
}
