package net.pieroxy.notes.api;

import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.services.ClientGuardService;
import net.pieroxy.notes.services.NoteService;
import net.pieroxy.notes.services.NoteStoreService;
import net.pieroxy.notes.services.ServiceProvider;
import net.pieroxy.notes.services.SessionService;
import net.pieroxy.notes.services.SettingsService;
import net.pieroxy.notes.services.WebServerService;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The asynchronous side of BaseApiEndpoint, through a real embedded Tomcat. */
public class AsyncEndpointTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private ServiceProvider services;
  private int port;
  private final HttpClient http = HttpClient.newHttpClient();

  @Before
  public void setUp() throws IOException {
    try (ServerSocket probe = new ServerSocket(0)) {
      port = probe.getLocalPort();
    }
    File credentials = tmp.newFile("credentials.json");
    Files.writeString(credentials.toPath(), "{\"users\":[]}");
    Configuration config = new Configuration();
    config.setHttpPort(port);
    config.setAddress("127.0.0.1");
    services = new ServiceProvider(new SettingsService(config, credentials, tmp.newFolder("data")), new SessionService(),
        new ClientGuardService(), new NoteStoreService(), new NoteService(), new WebServerService());
    services.init();
  }

  @After
  public void tearDown() {
    services.destroy();
  }

  private HttpResponse<String> get(String endpoint, String input) throws Exception {
    return getAsync(endpoint, input).get();
  }

  private CompletableFuture<HttpResponse<String>> getAsync(String endpoint, String input) {
    URI uri = URI.create("http://127.0.0.1:" + port + "/api/" + endpoint + "?input=" + URLEncoder.encode(input, StandardCharsets.UTF_8));
    return http.sendAsync(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  public void answersWhenTheFutureCompletesOnAContainerThread() throws Exception {
    HttpResponse<String> response = get("TestDelay", "{\"delayMillis\":200,\"value\":\"hello\"}");

    assertEquals(200, response.statusCode());
    assertTrue(response.body(), response.body().contains("\"value\":\"hello\""));
    assertFalse(response.body(), response.body().contains("CompletableFuture"));
  }

  @Test
  public void answersRightAwayWhenTheFutureIsAlreadyDone() throws Exception {
    HttpResponse<String> response = get("TestDelay", "{\"delayMillis\":0,\"value\":\"now\"}");

    assertEquals(200, response.statusCode());
    assertTrue(response.body(), response.body().contains("\"value\":\"now\""));
  }

  @Test
  public void mapsAFailedFutureLikeASynchronousError() throws Exception {
    HttpResponse<String> response = get("TestDelay", "{\"delayMillis\":100,\"fail\":true}");

    assertEquals(400, response.statusCode());
    assertEquals("{\"ok\":false,\"error\":\"Failed on purpose.\"}", response.body());
  }

  @Test
  public void answersA503WhenTheFutureNeverCompletes() throws Exception {
    HttpResponse<String> response = get("TestHang", "{}");

    assertEquals(503, response.statusCode());
    assertTrue(response.body(), response.body().contains("took too long"));
  }

  @Test
  public void manyWaitingRequestsAllGetTheirAnswer() throws Exception {
    List<CompletableFuture<HttpResponse<String>>> responses = new ArrayList<>();
    for (int i = 0; i < 300; i++) responses.add(getAsync("TestDelay", "{\"delayMillis\":500,\"value\":\"v" + i + "\"}"));
    for (int i = 0; i < responses.size(); i++) {
      HttpResponse<String> response = responses.get(i).get();
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"value\":\"v" + i + "\""));
    }
  }

  @Test
  public void synchronousEndpointsAreUnaffected() throws Exception {
    HttpResponse<String> response = get("Session", "{}");

    assertEquals(200, response.statusCode());
    assertEquals("{\"ok\":true,\"result\":{\"authenticated\":false}}", response.body());
  }
}
