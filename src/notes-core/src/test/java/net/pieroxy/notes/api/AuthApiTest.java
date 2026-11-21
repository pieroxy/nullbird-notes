package net.pieroxy.notes.api;

import com.google.gson.JsonObject;
import net.pieroxy.notes.services.ClientGuardService;
import net.pieroxy.notes.utils.BuildInfo;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

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

  @Test
  public void loginAndSessionTellWhoIsAdmin() throws Exception {
    server = new TestServer(tmp.getRoot(), Map.of("alice", "alice-password", "bob", "bob-password"), Set.of("alice"));

    TestServer.Client alice = server.client();
    JsonObject login = alice.post("Login", "{\"login\":\"alice\",\"password\":\"alice-password\"}").result();
    assertTrue(login.get("admin").getAsBoolean());
    assertTrue(alice.get("Session", "{}").result().get("admin").getAsBoolean());

    TestServer.Client bob = server.client();
    assertFalse(bob.post("Login", "{\"login\":\"bob\",\"password\":\"bob-password\"}").result().get("admin").getAsBoolean());
    assertFalse(bob.get("Session", "{}").result().get("admin").getAsBoolean());

    JsonObject refused = server.client().post("Login", "{\"login\":\"alice\",\"password\":\"wrong\"}").result();
    assertFalse(refused.get("admin").getAsBoolean());
    assertFalse(server.client().get("Session", "{}").result().get("admin").getAsBoolean());
  }

  @Test
  public void theSessionSaysWhatCredentialsJsonSaysNow() throws Exception {
    server = new TestServer(tmp.getRoot(), USERS, Set.of("alice"));
    TestServer.Client alice = server.client().login("alice", "alice-password");

    File credentials = new File(tmp.getRoot(), "credentials.json");
    String json = Files.readString(credentials.toPath(), StandardCharsets.UTF_8);
    assertTrue(json, json.contains("\"admin\":true"));
    long before = credentials.lastModified();
    Files.writeString(credentials.toPath(), json.replace("\"admin\":true", "\"admin\":false"), StandardCharsets.UTF_8);
    credentials.setLastModified(before + 2000);

    JsonObject session = alice.get("Session", "{}").result();
    assertTrue(session.get("authenticated").getAsBoolean());
    assertFalse(session.get("admin").getAsBoolean());
  }

  @Test
  public void aBurstOfWrongPasswordsChecksNoMoreThanTheLimit() throws Exception {
    server = new TestServer(tmp.getRoot(), USERS);
    int bursts = 30;
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    java.util.List<java.util.concurrent.Future<TestServer.Response>> responses = new java.util.ArrayList<>();
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(bursts);
    try {
      for (int i = 0; i < bursts; i++) {
        TestServer.Client client = server.client();
        responses.add(pool.submit(() -> {
          start.await();
          return client.post("Login", "{\"login\":\"alice\",\"password\":\"guess\"}");
        }));
      }
      start.countDown();
      int checked = 0;
      int busy = 0;
      int locked = 0;
      for (java.util.concurrent.Future<TestServer.Response> future : responses) {
        TestServer.Response response = future.get();
        if (response.status() == 200) {
          assertFalse(response.json().getAsJsonObject("result").get("ok").getAsBoolean());
          checked++;
        } else {
          assertEquals(response.body(), 429, response.status());
          String error = response.json().get("error").getAsString();
          if (error.equals("Please retry later.")) busy++;
          else if (error.startsWith("Too many failed attempts")) locked++;
          else throw new AssertionError(error);
        }
      }
      assertTrue("checked " + checked, checked >= 1 && checked <= ClientGuardService.MAX_FAILED_LOGINS);
      assertEquals(bursts, checked + busy + locked);
    } finally {
      pool.shutdownNow();
    }
  }
}
