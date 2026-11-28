package net.pieroxy.notes.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.pieroxy.notes.sync.NoteChange;
import net.pieroxy.notes.utils.Hashes;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** NoteHistory and NoteVersion through HTTP. */
public class HistoryApiTest {
  private static final String NOTE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String OTHER = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  private static final Gson GSON = new Gson();

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private TestServer server;
  private TestServer.Client alice;

  @Before
  public void setUp() throws Exception {
    server = new TestServer(tmp.getRoot(), Map.of("alice", "alice-password", "bob", "bob-password"));
    alice = server.client().login("alice", "alice-password");
  }

  @After
  public void tearDown() {
    if (server != null) server.close();
  }

  private void push(String deviceId, int baseRevision, String content) throws Exception {
    JsonObject input = new JsonObject();
    input.addProperty("deviceId", deviceId);
    input.add("changes", GSON.toJsonTree(new NoteChange[]{
        new NoteChange("c" + baseRevision, NOTE, baseRevision, null, content, null, Hashes.sha256(content), false, null)}));
    JsonArray results = alice.post("Push", input.toString()).result().getAsJsonArray("results");
    assertEquals("APPLIED", results.get(0).getAsJsonObject().get("status").getAsString());
  }

  @Test
  public void sessionsNewestFirstAndEachVersionWithTheOneBefore() throws Exception {
    push("laptop", 0, "Groceries\nmilk");
    push("laptop", 1, "Groceries\nmilk\neggs");
    push("phone", 2, "Groceries\noat milk\neggs");

    JsonArray entries = alice.post("NoteHistory", "{\"noteId\":\"" + NOTE + "\"}").result().getAsJsonArray("entries");

    assertEquals("the laptop's session, then the phone's", 2, entries.size());
    JsonObject phone = entries.get(0).getAsJsonObject();
    JsonObject laptop = entries.get(1).getAsJsonObject();
    assertEquals(3, phone.get("revision").getAsInt());
    assertEquals("EDITED", phone.get("kind").getAsString());
    assertEquals(2, laptop.get("revision").getAsInt());
    assertEquals(2, laptop.get("saves").getAsInt());
    assertEquals("CREATED", laptop.get("kind").getAsString());

    JsonObject version = alice.post("NoteVersion", "{\"noteId\":\"" + NOTE + "\",\"revision\":3,\"base\":2}").result();
    assertEquals("Groceries\noat milk\neggs", version.get("text").getAsString());
    assertEquals("Groceries\nmilk\neggs", version.get("baseText").getAsString());
    JsonObject first = alice.post("NoteVersion", "{\"noteId\":\"" + NOTE + "\",\"revision\":2}").result();
    assertFalse(first.has("baseText") && !first.get("baseText").isJsonNull());
  }

  @Test
  public void unknownNotesAndVersionsAndOtherUsersNotesAreNotFound() throws Exception {
    push("laptop", 0, "secret");

    assertEquals(404, alice.post("NoteHistory", "{\"noteId\":\"" + OTHER + "\"}").status());
    assertEquals(404, alice.post("NoteVersion", "{\"noteId\":\"" + NOTE + "\",\"revision\":2}").status());
    assertEquals(404, alice.post("NoteVersion", "{\"noteId\":\"" + NOTE + "\",\"revision\":1,\"base\":7}").status());
    assertEquals(400, alice.post("NoteHistory", "{\"noteId\":\"../x\"}").status());
    TestServer.Client bob = server.client().login("bob", "bob-password");
    assertEquals(404, bob.post("NoteHistory", "{\"noteId\":\"" + NOTE + "\"}").status());
    assertEquals(404, bob.post("NoteVersion", "{\"noteId\":\"" + NOTE + "\",\"revision\":1}").status());
    assertTrue(server.client().post("NoteHistory", "{\"noteId\":\"" + NOTE + "\"}").status() == 401);
  }
}
