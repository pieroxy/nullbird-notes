package net.pieroxy.notes.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.pieroxy.notes.export.SimplenoteExport;
import net.pieroxy.notes.sync.NoteChange;
import net.pieroxy.notes.utils.Hashes;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** GET /export through HTTP. */
public class ExportTest {
  private static final Gson GSON = new Gson();

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

  private static void push(TestServer.Client client, String noteId, String content) throws Exception {
    JsonObject input = new JsonObject();
    input.addProperty("deviceId", "laptop");
    input.add("changes", GSON.toJsonTree(new NoteChange[] {
        new NoteChange("c-" + noteId, noteId, 0, null, content, null, Hashes.sha256(content), false, null)}));
    assertEquals(200, client.post("Push", input.toString()).status());
  }

  private static Map<String, String> unzip(byte[] zip) throws Exception {
    Map<String, String> entries = new HashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
      for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    return entries;
  }

  @Test
  public void requiresALogin() throws Exception {
    assertEquals(401, server.client().rawBytes("/export").statusCode());
  }

  @Test
  public void downloadsTheUsersNotesOnly() throws Exception {
    TestServer.Client alice = server.client().login("alice", "alice-password");
    TestServer.Client bob = server.client().login("bob", "bob-password");
    push(alice, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Alice's note\n");
    push(bob, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "Bob's note\n");

    HttpResponse<byte[]> response = alice.rawBytes("/export");

    assertEquals(200, response.statusCode());
    assertEquals("application/zip", response.headers().firstValue("Content-Type").orElse(""));
    String disposition = response.headers().firstValue("Content-Disposition").orElse("");
    assertTrue(disposition, disposition.matches("attachment; filename=\"notes-alice-\\d{4}-\\d{2}-\\d{2}\\.zip\""));
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
    Map<String, String> entries = unzip(response.body());
    assertEquals("Alice's note\n", entries.get("Alice's note.txt"));
    assertTrue(entries.get(SimplenoteExport.NOTES_JSON).contains("Alice's note"));
    assertFalse(entries.toString().contains("Bob"));
  }
}
