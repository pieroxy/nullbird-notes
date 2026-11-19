package net.pieroxy.notes.api;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SecurityHeadersTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private TestServer server;

  @Before
  public void setUp() throws Exception {
    server = new TestServer(tmp.getRoot(), Map.of("alice", "alice-password"));
  }

  @After
  public void tearDown() {
    if (server != null) server.close();
  }

  private static String header(HttpResponse<String> response, String name) {
    return response.headers().firstValue(name).orElse(null);
  }

  @Test
  public void everyResponseCarriesTheSecurityHeaders() throws Exception {
    for (String path : List.of("/", "/index.html", "/no-such-file", "/api/Session?input=%7B%7D")) {
      HttpResponse<String> response = server.client().raw(path);
      assertTrue(path, header(response, "Content-Security-Policy").startsWith("default-src 'none'; script-src 'self'"));
      assertEquals(path, "nosniff", header(response, "X-Content-Type-Options"));
      assertEquals(path, "no-referrer", header(response, "Referrer-Policy"));
      assertEquals(path, "DENY", header(response, "X-Frame-Options"));
      assertEquals(path, "same-origin", header(response, "Cross-Origin-Opener-Policy"));
    }
  }

  @Test
  public void apiResponsesAreNeverStoredAndThePageIsRevalidated() throws Exception {
    TestServer.Client client = server.client().login("alice", "alice-password");

    assertEquals("no-store", header(client.raw("/api/ServerInfo?input=%7B%7D"), "Cache-Control"));
    assertEquals("no-cache", header(client.raw("/"), "Cache-Control"));
  }

  @Test
  public void errorPagesDontTellTheServersVersion() throws Exception {
    HttpResponse<String> response = server.client().raw("/no-such-file");

    assertEquals(404, response.statusCode());
    assertFalse(response.body(), response.body().contains("Tomcat"));
  }
}
