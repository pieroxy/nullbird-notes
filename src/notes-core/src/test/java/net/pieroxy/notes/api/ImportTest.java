package net.pieroxy.notes.api;

import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** POST /import, then ImportRun or ImportCancel, through HTTP. */
public class ImportTest {
  private static final String A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final byte[] FILE = ("{\"activeNotes\": [{\"id\": \"" + A + "\", \"content\": \"Imported\\nnote\", \"tags\": [\"work\"],"
      + " \"creationDate\": \"2020-01-02T03:04:05.000Z\", \"lastModified\": \"2021-01-02T03:04:05.000Z\"}], \"trashedNotes\": []}")
      .getBytes(StandardCharsets.UTF_8);

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private TestServer server;

  @Before
  public void setUp() throws Exception {
    server = new TestServer(tmp.getRoot(), Map.of("alice", "alice-password", "bob", "bob-password"));
  }

  @After
  public void tearDown() {
    server.close();
  }

  private static String token(String token) {
    JsonObject input = new JsonObject();
    input.addProperty("token", token);
    return input.toString();
  }

  @Test
  public void requiresALogin() throws Exception {
    TestServer.Response response = server.client().upload("/import?name=notes.zip", FILE);
    assertEquals(401, response.status());
    assertFalse(response.json().get("ok").getAsBoolean());
  }

  @Test
  public void requiresTheWebAppsBuild() throws Exception {
    TestServer.Client alice = server.client().login("alice", "alice-password");
    assertEquals(409, alice.asBuild(null).upload("/import?name=notes.zip", FILE).status());
  }

  @Test
  public void checksThenRuns() throws Exception {
    TestServer.Client alice = server.client().login("alice", "alice-password");

    JsonObject preview = alice.upload("/import?name=notes.zip", FILE).result();
    assertEquals("notes.zip", preview.get("fileName").getAsString());
    assertEquals(1, preview.get("imported").getAsInt());
    assertEquals(0, alice.get("Changes", "{\"since\": 0, \"wait\": false}").result().getAsJsonArray("notes").size());

    JsonObject done = alice.post("ImportRun", token(preview.get("token").getAsString())).result();
    assertEquals(1, done.get("imported").getAsInt());
    assertFalse(done.has("token"));

    JsonObject note = alice.get("Changes", "{\"since\": 0, \"wait\": false}").result().getAsJsonArray("notes").get(0).getAsJsonObject();
    assertEquals(A, note.get("id").getAsString());
    assertEquals("Imported", note.get("title").getAsString());
    assertEquals("work", note.getAsJsonArray("tags").get(0).getAsString());
  }

  @Test
  public void anotherUserCantRunIt() throws Exception {
    TestServer.Client alice = server.client().login("alice", "alice-password");
    TestServer.Client bob = server.client().login("bob", "bob-password");
    String token = alice.upload("/import?name=notes.zip", FILE).result().get("token").getAsString();

    TestServer.Response response = bob.post("ImportRun", token(token));

    assertEquals(400, response.status());
    assertTrue(response.body(), response.json().get("error").getAsString().contains("no longer waiting"));
  }

  @Test
  public void cancelled() throws Exception {
    TestServer.Client alice = server.client().login("alice", "alice-password");
    String token = alice.upload("/import?name=notes.zip", FILE).result().get("token").getAsString();

    alice.post("ImportCancel", token(token)).result();

    assertEquals(400, alice.post("ImportRun", token(token)).status());
  }

  @Test
  public void notAnExport() throws Exception {
    TestServer.Client alice = server.client().login("alice", "alice-password");
    TestServer.Response response = alice.upload("/import?name=photo.jpg", new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2});
    assertEquals(400, response.status());
    assertTrue(response.body(), response.json().get("error").getAsString().contains("isn't an export"));
  }
}
