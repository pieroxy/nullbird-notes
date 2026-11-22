package net.pieroxy.notes.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.pieroxy.notes.storage.NoteWrite;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** AdminConfig and AdminSaveConfig through HTTP. */
public class AdminConfigApiTest {
  private static final Logger API_LOGGER = Logger.getLogger("net.pieroxy.notes.api");

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private TestServer server;
  private TestServer.Client alice;
  private final List<LogRecord> records = new CopyOnWriteArrayList<>();
  private final Handler capture = new Handler() {
    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
  };

  @Before
  public void setUp() throws Exception {
    API_LOGGER.addHandler(capture);
    server = new TestServer(tmp.getRoot(), Map.of("alice", "alice-password", "bob", "bob-password"), Set.of("alice"));
    alice = server.client().login("alice", "alice-password");
  }

  @After
  public void tearDown() {
    server.close();
    API_LOGGER.removeHandler(capture);
  }

  private File configFile() {
    return new File(tmp.getRoot(), "config.json");
  }

  private static String save(int port, String address, String proxies, boolean secureCookie, int keepLogFiles, int maxNoteSizeMb) {
    return "{\"httpPort\":" + port + ",\"address\":\"" + address + "\",\"trustedProxies\":" + proxies + ",\"secureCookie\":" + secureCookie
        + ",\"keepLogFiles\":" + keepLogFiles + ",\"maxNoteSizeMb\":" + maxNoteSizeMb + "}";
  }

  @Test
  public void onlyAdminsMayCall() throws Exception {
    TestServer.Client bob = server.client().login("bob", "bob-password");

    assertEquals(403, bob.get("AdminConfig", "{}").status());
    assertEquals(403, bob.post("AdminSaveConfig", save(9000, "", "[]", false, 30, 10)).status());
    assertFalse(configFile().exists());
  }

  @Test
  public void runningAndSavedWithDefaultsFilledIn() throws Exception {
    JsonObject config = alice.get("AdminConfig", "{}").result();

    JsonObject running = config.getAsJsonObject("running");
    assertEquals("127.0.0.1", running.get("address").getAsString());
    assertEquals(1, running.get("maxNoteSizeMb").getAsInt());
    assertEquals(30, running.get("keepLogFiles").getAsInt());
    JsonObject saved = config.getAsJsonObject("saved");
    assertEquals("no config.json: all defaults", 8080, saved.get("httpPort").getAsInt());
    assertEquals("", saved.get("address").getAsString());
    assertEquals(10, saved.get("maxNoteSizeMb").getAsInt());
    assertEquals(0, saved.getAsJsonArray("trustedProxies").size());
    assertTrue("secure unless turned off", saved.get("secureCookie").getAsBoolean());
    assertEquals(configFile().getAbsolutePath(), config.get("configFile").getAsString());
    assertEquals(new File(tmp.getRoot(), "data").getAbsolutePath(), config.get("dataFolderPath").getAsString());
  }

  @Test
  public void notesOver1MbAreListedByUserDeletedOnesAside() throws Exception {
    var store = server.services().getNoteStoreService().getStore("alice");
    String big = "x".repeat(1_500_000);
    store.commit("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 0, new NoteWrite(big, false, "c1", "d", null));
    store.commit("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", 0, new NoteWrite(big, false, "c2", "d", null));
    store.commit("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", 1, new NoteWrite(big, true, "c3", "d", null));
    store.commit("cccccccc-cccc-4ccc-8ccc-cccccccccccc", 0, new NoteWrite("small", false, "c4", "d", null));

    var notes = alice.get("AdminConfig", "{}").result().getAsJsonArray("largeNotes");

    assertEquals(1, notes.size());
    assertEquals("alice", notes.get(0).getAsJsonObject().get("username").getAsString());
    assertEquals(1_500_000, notes.get(0).getAsJsonObject().get("size").getAsLong());
    assertFalse("bob has no data: none created", new File(tmp.getRoot(), "data/users/bob").exists());
  }

  @Test
  public void writesOnlyWhatChangedKeepingTheRestForTheNextStart() throws Exception {
    Files.writeString(configFile().toPath(), "{\"dataFolder\":\"data\",\"future\":{\"x\":1},\"httpPort\":8080}", StandardCharsets.UTF_8);

    JsonObject result = alice.post("AdminSaveConfig", save(9000, " 127.0.0.1 ", "[\" 10.0.0.0/8 \",\"\"]", false, 30, 5)).result();

    assertTrue(result.toString(), result.get("ok").getAsBoolean());
    JsonObject file = JsonParser.parseString(Files.readString(configFile().toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
    assertEquals(9000, file.get("httpPort").getAsInt());
    assertEquals("127.0.0.1", file.get("address").getAsString());
    assertEquals("[\"10.0.0.0/8\"]", file.get("trustedProxies").toString());
    assertFalse(file.get("secureCookie").getAsBoolean());
    assertEquals(5, file.get("maxNoteSizeMb").getAsInt());
    assertFalse("30 is the default: unchanged, not written", file.has("keepLogFiles"));
    assertEquals("data", file.get("dataFolder").getAsString());
    assertEquals("{\"x\":1}", file.get("future").toString());

    JsonObject config = alice.get("AdminConfig", "{}").result();
    assertEquals(9000, config.getAsJsonObject("saved").get("httpPort").getAsInt());
    assertEquals("in effect: still the startup's", 1, config.getAsJsonObject("running").get("maxNoteSizeMb").getAsInt());
    assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING
        && r.getMessage().contains("Admin \"alice\" from 127.0.0.1 changed " + configFile().getAbsolutePath())
        && r.getMessage().contains("httpPort 8080 → 9000") && r.getMessage().contains("maxNoteSizeMb 10 → 5")
        && !r.getMessage().contains("keepLogFiles")));
  }

  @Test
  public void nothingChangedNothingWritten() throws Exception {
    assertTrue(alice.post("AdminSaveConfig", save(8080, "", "[]", true, 30, 10)).result().get("ok").getAsBoolean());
    assertFalse(configFile().exists());
  }

  @Test
  public void refusesWhatTheServerCouldntStartWith() throws Exception {
    Files.writeString(configFile().toPath(), "{\"httpPort\":8080}", StandardCharsets.UTF_8);
    for (String input : new String[] {save(0, "", "[]", false, 30, 10), save(65536, "", "[]", false, 30, 10),
        save(8080, "no-such-host.invalid", "[]", false, 30, 10), save(8080, "192.0.2.1", "[]", false, 30, 10),
        save(8080, "", "[\"not a proxy\"]", false, 30, 10), save(8080, "", "[]", false, -1, 10), save(8080, "", "[]", false, 30, 0)}) {
      JsonObject result = alice.post("AdminSaveConfig", input).result();
      assertFalse(input, result.get("ok").getAsBoolean());
      assertTrue(input, result.has("error"));
    }
    assertEquals("{\"httpPort\":8080}", Files.readString(configFile().toPath(), StandardCharsets.UTF_8));
  }

  @Test
  public void addressesForwardingRequestsAreListedTheAdminsOwnMarked() throws Exception {
    assertEquals(0, alice.get("AdminConfig", "{}").result().getAsJsonArray("forwarders").size());
    alice.rawForwardedFor("/", "203.0.113.7");
    alice.rawForwardedFor("/", "203.0.113.8");

    var forwarders = alice.get("AdminConfig", "{}").result().getAsJsonArray("forwarders");

    assertEquals(1, forwarders.size());
    JsonObject proxy = forwarders.get(0).getAsJsonObject();
    assertEquals("127.0.0.1", proxy.get("address").getAsString());
    assertEquals(2, proxy.get("forwarded").getAsLong());
    assertEquals("the AdminConfig call counts too", 3, proxy.get("requests").getAsLong());
    assertEquals(2, proxy.get("clients").getAsInt());
    assertTrue(proxy.get("privateAddress").getAsBoolean());
    assertTrue("alice's own request came from it", proxy.get("yours").getAsBoolean());
  }
}
