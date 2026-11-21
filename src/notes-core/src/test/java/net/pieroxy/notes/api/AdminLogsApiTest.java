package net.pieroxy.notes.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** AdminLogs through HTTP, on log files written here. */
public class AdminLogsApiTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private TestServer server;
  private TestServer.Client alice;
  private Path logs;

  @Before
  public void setUp() throws Exception {
    server = new TestServer(tmp.getRoot(), Map.of("alice", "alice-password", "bob", "bob-password"), Set.of("alice"));
    alice = server.client().login("alice", "alice-password");
    logs = Files.createDirectories(tmp.getRoot().toPath().resolve("data/logs"));
  }

  @After
  public void tearDown() {
    server.close();
  }

  private static String line(int second, String level, String message) {
    return String.format("2026-11-21 10:%02d:%02d Ab12 %7s %30s %s%n", second / 60, second % 60, level, "Some/where", message);
  }

  private JsonObject logs(String input) throws Exception {
    return alice.get("AdminLogs", input).result();
  }

  private static List<String> messages(JsonObject result) {
    List<String> messages = new ArrayList<>();
    for (var entry : result.getAsJsonArray("entries")) messages.add(entry.getAsJsonObject().get("message").getAsString());
    return messages;
  }

  @Test
  public void onlyAdminsMayRead() throws Exception {
    assertEquals(403, server.client().login("bob", "bob-password").get("AdminLogs", "{\"file\":0}").status());
  }

  @Test
  public void newestFirstFilteredBySeverityAndSearched() throws Exception {
    Files.writeString(logs.resolve("log.txt"), line(1, "INFO", "User \"alice\" logged in") + line(2, "WARNING", "Failed login for \"bob\"")
        + line(3, "SEVERE", "Endpoint failed") + "java.lang.IllegalStateException: Boom\n\tat Some.where(Some.java:1)\n"
        + line(4, "INFO", "User \"bob\" logged in"), StandardCharsets.UTF_8);

    JsonObject all = logs("{\"file\":0}");
    assertEquals(List.of("User \"bob\" logged in", "Endpoint failed", "Failed login for \"bob\"", "User \"alice\" logged in"), messages(all));
    assertTrue(all.getAsJsonArray("entries").get(1).getAsJsonObject().get("details").getAsString().contains("IllegalStateException"));
    assertFalse(all.get("more").getAsBoolean());

    assertEquals(List.of("Endpoint failed", "Failed login for \"bob\""), messages(logs("{\"file\":0,\"levels\":[\"SEVERE\",\"WARNING\"]}")));
    JsonObject bob = logs("{\"file\":0,\"query\":\"BOB\"}");
    assertEquals(List.of("User \"bob\" logged in", "Failed login for \"bob\""), messages(bob));
    assertEquals(1, bob.getAsJsonObject("counts").get("INFO").getAsInt());
    assertEquals(1, bob.getAsJsonObject("counts").get("WARNING").getAsInt());
    assertEquals("the stack trace is searched too", List.of("Endpoint failed"), messages(logs("{\"file\":0,\"query\":\"boom\"}")));
  }

  @Test
  public void aPageAtATime() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 450; i++) text.append(line(i, "INFO", "entry " + i));
    Files.writeString(logs.resolve("log.txt"), text, StandardCharsets.UTF_8);

    JsonObject first = logs("{\"file\":0}");
    JsonArray entries = first.getAsJsonArray("entries");
    assertEquals(200, entries.size());
    assertEquals("entry 449", messages(first).get(0));
    assertTrue(first.get("more").getAsBoolean());
    int last = entries.get(199).getAsJsonObject().get("index").getAsInt();

    JsonObject second = logs("{\"file\":0,\"before\":" + last + "}");
    assertEquals("entry 249", messages(second).get(0));
    JsonObject third = logs("{\"file\":0,\"before\":50}");
    assertEquals(50, third.getAsJsonArray("entries").size());
    assertFalse(third.get("more").getAsBoolean());
  }

  @Test
  public void archivesAreListedAndReadUnknownFilesAreNot() throws Exception {
    Files.writeString(logs.resolve("log.txt"), line(1, "INFO", "today"), StandardCharsets.UTF_8);
    try (OutputStream out = new LZ4FrameOutputStream(Files.newOutputStream(logs.resolve("log.txt.1.lz4")))) {
      out.write(line(1, "INFO", "yesterday").getBytes(StandardCharsets.UTF_8));
    }

    JsonObject archived = logs("{\"file\":1}");
    assertEquals(2, archived.getAsJsonArray("files").size());
    assertEquals(List.of("yesterday"), messages(archived));
    assertEquals(List.of(), messages(logs("{\"file\":7}")));
    assertEquals(List.of(), messages(logs("{\"file\":-1}")));
  }
}
