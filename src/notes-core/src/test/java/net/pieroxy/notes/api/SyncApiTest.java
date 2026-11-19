package net.pieroxy.notes.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.pieroxy.notes.sync.NoteChange;
import net.pieroxy.notes.sync.Splice;
import net.pieroxy.notes.utils.Hashes;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The sync endpoints through HTTP: two devices of one user, and another user. */
public class SyncApiTest {
  private static final String NOTE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final Gson GSON = new Gson();

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private TestServer server;
  private TestServer.Client laptop;
  private TestServer.Client phone;

  @Before
  public void setUp() throws Exception {
    server = new TestServer(tmp.getRoot(), Map.of("alice", "alice-password", "bob", "bob-password"));
    laptop = server.client().login("alice", "alice-password");
    phone = server.client().login("alice", "alice-password");
  }

  @After
  public void tearDown() {
    server.close();
  }

  private static String pushInput(String deviceId, NoteChange... changes) {
    JsonObject input = new JsonObject();
    input.addProperty("deviceId", deviceId);
    input.add("changes", GSON.toJsonTree(changes));
    return input.toString();
  }

  private static NoteChange edit(String changeId, int baseRevision, String content) {
    return new NoteChange(changeId, NOTE, baseRevision, null, content, null, Hashes.sha256(content), false);
  }

  private static JsonObject onlyResult(TestServer.Response response) {
    JsonArray results = response.result().getAsJsonArray("results");
    assertEquals(1, results.size());
    return results.get(0).getAsJsonObject();
  }

  @Test
  public void everyEndpointRequiresASession() throws Exception {
    TestServer.Client anonymous = server.client();

    assertEquals(401, anonymous.get("ServerInfo", "{}").status());
    assertEquals(401, anonymous.get("Changes", "{\"since\":0}").status());
    assertEquals(401, anonymous.post("GetNotes", "{\"notes\":[]}").status());
    assertEquals(401, anonymous.post("Push", pushInput("d", edit("c1", 0, "x"))).status());
  }

  @Test
  public void serverInfoGivesTheNoteSizeLimit() throws Exception {
    assertEquals(1024 * 1024, laptop.get("ServerInfo", "{}").result().get("maxNoteSize").getAsLong());
  }

  @Test
  public void aNoteTravelsFromOneDeviceToTheOther() throws Exception {
    JsonObject pushed = onlyResult(laptop.post("Push", pushInput("laptop", edit("c1", 0, "Hello\nworld\n"))));
    assertEquals("APPLIED", pushed.get("status").getAsString());

    JsonObject changes = phone.get("Changes", "{\"since\":0}").result();
    assertEquals(1, changes.get("seq").getAsLong());
    assertFalse(changes.get("more").getAsBoolean());
    JsonObject info = changes.getAsJsonArray("notes").get(0).getAsJsonObject();
    assertEquals(NOTE, info.get("id").getAsString());
    assertEquals("Hello", info.get("title").getAsString());

    JsonObject note = phone.post("GetNotes", "{\"notes\":[{\"id\":\"" + NOTE + "\"}]}").result()
        .getAsJsonArray("notes").get(0).getAsJsonObject();
    String content = GSON.fromJson(note.get("content"), Splice.class).apply("");
    assertEquals("Hello\nworld\n", content);
    assertEquals(Hashes.sha256(content), note.getAsJsonObject("note").get("contentHash").getAsString());
  }

  @Test
  public void aLongPollWakesUpWhenAnotherDevicePushes() throws Exception {
    onlyResult(laptop.post("Push", pushInput("laptop", edit("c1", 0, "Hello\n"))));
    CompletableFuture<TestServer.Response> poll = laptop.getAsync("Changes", "{\"since\":1,\"wait\":true}");
    Thread.sleep(300);
    assertFalse(poll.isDone());

    String edited = "Hello\nfrom the phone\n";
    Splice splice = Splice.compute("Hello\n", edited);
    NoteChange change = new NoteChange("c2", NOTE, 1, null, null, splice, Hashes.sha256(edited), false);
    assertEquals("APPLIED", onlyResult(phone.post("Push", pushInput("phone", change))).get("status").getAsString());

    JsonObject changes = poll.get(5, TimeUnit.SECONDS).result();
    assertEquals(2, changes.get("seq").getAsLong());
    assertEquals(2, changes.getAsJsonArray("notes").get(0).getAsJsonObject().get("revision").getAsInt());

    JsonObject note = laptop.post("GetNotes", "{\"notes\":[{\"id\":\"" + NOTE + "\",\"haveRevision\":1}]}").result()
        .getAsJsonArray("notes").get(0).getAsJsonObject();
    Splice delta = GSON.fromJson(note.get("content"), Splice.class);
    assertEquals(new Splice(6, 0, "from the phone\n"), delta);
    assertEquals(edited, delta.apply("Hello\n"));
  }

  @Test
  public void aBatchAppliesEachChangeOnItsOwnInOrder() throws Exception {
    NoteChange bad = new NoteChange("c1", NOTE, 0, null, "x", null, Hashes.sha256("not x"), false);
    NoteChange good = edit("c2", 0, "fine");
    JsonArray results = laptop.post("Push", pushInput("laptop", bad, good)).result().getAsJsonArray("results");

    assertEquals("c1", results.get(0).getAsJsonObject().get("changeId").getAsString());
    assertEquals("REJECTED", results.get(0).getAsJsonObject().get("status").getAsString());
    assertEquals("BASE_MISMATCH", results.get(0).getAsJsonObject().get("reason").getAsString());
    assertEquals("c2", results.get(1).getAsJsonObject().get("changeId").getAsString());
    assertEquals("APPLIED", results.get(1).getAsJsonObject().get("status").getAsString());
  }

  @Test
  public void refusesTooManyChangesAtOnce() throws Exception {
    List<NoteChange> changes = new ArrayList<>();
    for (int i = 0; i <= 100; i++) changes.add(edit("c" + i, 0, "x"));
    TestServer.Response response = laptop.post("Push", pushInput("laptop", changes.toArray(new NoteChange[0])));

    assertEquals(400, response.status());
    assertTrue(response.body(), response.body().contains("At most 100 changes"));
  }

  @Test
  public void refusesAChangeMissingARequiredField() throws Exception {
    TestServer.Response response = laptop.post("Push", "{\"deviceId\":\"laptop\",\"changes\":[{\"changeId\":\"c1\",\"noteId\":\"" + NOTE
        + "\",\"deleted\":false,\"content\":\"x\"}]}");

    assertEquals(400, response.status());
    assertTrue(response.body(), response.body().contains("changes[0].baseRevision"));
  }

  @Test
  public void anotherUserSeesNothing() throws Exception {
    onlyResult(laptop.post("Push", pushInput("laptop", edit("c1", 0, "secret"))));
    TestServer.Client bob = server.client().login("bob", "bob-password");

    JsonObject changes = bob.get("Changes", "{\"since\":0}").result();
    assertEquals(0, changes.getAsJsonArray("notes").size());
    JsonObject notes = bob.post("GetNotes", "{\"notes\":[{\"id\":\"" + NOTE + "\"}]}").result();
    assertEquals(NOTE, notes.getAsJsonArray("missing").get(0).getAsString());
  }
}
