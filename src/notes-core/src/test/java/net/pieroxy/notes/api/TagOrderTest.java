package net.pieroxy.notes.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.pieroxy.notes.sync.MetaChange;
import net.pieroxy.notes.sync.NoteChange;
import net.pieroxy.notes.sync.TagOrderChange;
import net.pieroxy.notes.utils.Hashes;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The tag order through HTTP: pushed with the notes, reported with the changes. */
public class TagOrderTest {
  private static final Gson GSON = new Gson();
  private static final String A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private TestServer server;
  private TestServer.Client alice;
  private int changes;

  @Before
  public void setUp() throws Exception {
    server = new TestServer(tmp.getRoot(), Map.of("alice", "alice-password"));
    alice = server.client().login("alice", "alice-password");
    note(A, "First");
    note(B, "Second");
    tags(A, List.of("work", "home"), null);
    tags(B, List.of("Zebra"), null);
  }

  @After
  public void tearDown() {
    server.close();
  }

  private void note(String id, String content) throws Exception {
    JsonObject input = new JsonObject();
    input.addProperty("deviceId", "laptop");
    input.add("changes", GSON.toJsonTree(List.of(new NoteChange("c" + (++changes), id, 0, null, content, null, Hashes.sha256(content), false, null))));
    alice.post("Push", input.toString()).result();
  }

  private void tags(String id, List<String> add, List<String> remove) throws Exception {
    JsonObject input = new JsonObject();
    input.addProperty("deviceId", "laptop");
    input.add("changes", new JsonArray());
    input.add("metaChanges", GSON.toJsonTree(List.of(new MetaChange("m" + (++changes), id, add, remove, null, null))));
    alice.post("Push", input.toString()).result();
  }

  private JsonObject pushOrder(int baseRevision, String... tags) throws Exception {
    JsonObject input = new JsonObject();
    input.addProperty("deviceId", "laptop");
    input.add("changes", new JsonArray());
    input.add("tagOrder", GSON.toJsonTree(new TagOrderChange(baseRevision, List.of(tags))));
    return alice.post("Push", input.toString()).result().getAsJsonObject("tagOrder");
  }

  private JsonObject changesSince(long since) throws Exception {
    return alice.get("Changes", "{\"since\":" + since + ",\"wait\":false}").result();
  }

  private static List<String> tagsOf(JsonObject order) {
    List<String> tags = new ArrayList<>();
    for (var tag : order.getAsJsonArray("tags")) tags.add(tag.getAsString());
    return tags;
  }

  @Test
  public void noneUntilTheFirstReorder() throws Exception {
    assertFalse(changesSince(0).has("tagOrder"));
  }

  @Test
  public void theFirstOrderIsCompletedWithTheTagsItLacksAlphabetically() throws Exception {
    JsonObject result = pushOrder(0, "work");

    assertEquals("APPLIED", result.get("status").getAsString());
    assertEquals(1, result.getAsJsonObject("order").get("revision").getAsInt());
    assertEquals(List.of("work", "home", "Zebra"), tagsOf(result.getAsJsonObject("order")));
    JsonObject changes = changesSince(0);
    assertEquals(List.of("work", "home", "Zebra"), tagsOf(changes.getAsJsonObject("tagOrder")));
    assertFalse("not again once past it", changesSince(changes.get("seq").getAsLong()).has("tagOrder"));
  }

  @Test
  public void aChangeOnAnOldRevisionIsStale() throws Exception {
    pushOrder(0, "Zebra", "home", "work");

    JsonObject stale = pushOrder(0, "home", "work", "Zebra");

    assertEquals("STALE", stale.get("status").getAsString());
    assertEquals(List.of("Zebra", "home", "work"), tagsOf(stale.getAsJsonObject("order")));
    assertEquals(1, stale.getAsJsonObject("order").get("revision").getAsInt());
  }

  @Test
  public void sendingTheSameOrderAgainChangesNothing() throws Exception {
    pushOrder(0, "Zebra", "home", "work");
    long seq = changesSince(0).get("seq").getAsLong();

    JsonObject again = pushOrder(1, "Zebra", "home", "work");

    assertEquals("APPLIED", again.get("status").getAsString());
    assertEquals(1, again.getAsJsonObject("order").get("revision").getAsInt());
    assertEquals(seq, changesSince(0).get("seq").getAsLong());
  }

  @Test
  public void newTagsGoAtTheEndAndUnusedOnesLeave() throws Exception {
    pushOrder(0, "work", "Zebra", "home");
    long seq = changesSince(0).get("seq").getAsLong();

    tags(B, List.of("art", "music"), null);
    JsonObject changes = changesSince(seq);
    assertEquals("new ones at the end, in the order added, not alphabetically",
        List.of("work", "Zebra", "home", "art", "music"), tagsOf(changes.getAsJsonObject("tagOrder")));
    assertEquals(2, changes.getAsJsonObject("tagOrder").get("revision").getAsInt());

    tags(B, List.of("home"), List.of("Zebra"));
    tags(A, null, List.of("home"));
    assertEquals("home is still used, so keeps its place; Zebra isn't any more",
        List.of("work", "home", "art", "music"), tagsOf(changesSince(0).getAsJsonObject("tagOrder")));

    tags(A, List.of("Zebra"), null);
    assertEquals("back, but new again: at the end", List.of("work", "home", "art", "music", "Zebra"),
        tagsOf(changesSince(0).getAsJsonObject("tagOrder")));
  }

  @Test
  public void invalidTagsAreRejected() throws Exception {
    JsonObject result = pushOrder(0, "work", "two words");

    assertEquals("REJECTED", result.get("status").getAsString());
    assertTrue(result.get("message").getAsString().contains("Invalid tag"));
    assertFalse(result.has("order"));
  }
}
