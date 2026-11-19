package net.pieroxy.notes.api;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The asynchronous side of BaseApiEndpoint, through a real embedded Tomcat. */
public class AsyncEndpointTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private TestServer server;
  private TestServer.Client client;

  @Before
  public void setUp() throws Exception {
    server = new TestServer(tmp.getRoot(), Map.of());
    client = server.client();
  }

  @After
  public void tearDown() {
    server.close();
  }

  @Test
  public void answersWhenTheFutureCompletes() throws Exception {
    TestServer.Response response = client.get("TestDelay", "{\"delayMillis\":200,\"value\":\"hello\"}");

    assertEquals("hello", response.result().get("value").getAsString());
  }

  @Test
  public void answersRightAwayWhenTheFutureIsAlreadyDone() throws Exception {
    TestServer.Response response = client.get("TestDelay", "{\"delayMillis\":0,\"value\":\"now\"}");

    assertEquals("now", response.result().get("value").getAsString());
  }

  @Test
  public void mapsAFailedFutureLikeASynchronousError() throws Exception {
    TestServer.Response response = client.get("TestDelay", "{\"delayMillis\":100,\"fail\":true}");

    assertEquals(400, response.status());
    assertEquals("{\"ok\":false,\"error\":\"Failed on purpose.\"}", response.body());
  }

  @Test
  public void answersA503WhenTheFutureNeverCompletes() throws Exception {
    TestServer.Response response = client.get("TestHang", "{}");

    assertEquals(503, response.status());
    assertTrue(response.body(), response.body().contains("took too long"));
  }

  @Test
  public void manyWaitingRequestsAllGetTheirAnswer() throws Exception {
    List<CompletableFuture<TestServer.Response>> responses = new ArrayList<>();
    for (int i = 0; i < 300; i++) responses.add(client.getAsync("TestDelay", "{\"delayMillis\":500,\"value\":\"v" + i + "\"}"));
    for (int i = 0; i < responses.size(); i++) {
      assertEquals("v" + i, responses.get(i).get().result().get("value").getAsString());
    }
  }

  @Test
  public void synchronousEndpointsAreUnaffected() throws Exception {
    TestServer.Response response = client.get("Session", "{}");

    assertEquals("{\"ok\":true,\"result\":{\"authenticated\":false}}", response.body());
  }
}
