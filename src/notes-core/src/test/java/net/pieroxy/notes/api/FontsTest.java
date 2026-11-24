package net.pieroxy.notes.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.pieroxy.notes.fonts.TestFonts;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** POST /fonts/ (admins), AdminSaveFont, AdminDeleteFont, Fonts, and GET /fonts/{file}, through HTTP. */
public class FontsTest {
  private static final byte[] REGULAR = TestFonts.ttf(400, false, "Test Sans", 100, 900);
  private static final byte[] BOLD = TestFonts.ttf(700, false, "Test Sans", null, null);

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private TestServer server;
  private TestServer.Client alice;

  @Before
  public void setUp() throws Exception {
    server = new TestServer(tmp.getRoot(), Map.of("alice", "alice-password", "bob", "bob-password"), Set.of("alice"));
    alice = server.client().login("alice", "alice-password");
  }

  @After
  public void tearDown() {
    server.close();
  }

  private static String save(String id, String name, boolean monospace, String... styleFiles) {
    JsonObject input = new JsonObject();
    if (id != null) input.addProperty("id", id);
    input.addProperty("name", name);
    input.addProperty("monospace", monospace);
    JsonArray files = new JsonArray();
    for (int i = 0; i < styleFiles.length; i += 2) {
      JsonObject file = new JsonObject();
      file.addProperty("style", styleFiles[i]);
      file.addProperty("file", styleFiles[i + 1]);
      files.add(file);
    }
    input.add("files", files);
    return input.toString();
  }

  private String uploaded(TestServer.Client client, byte[] font) throws Exception {
    return client.upload("/fonts/", font).result().get("file").getAsString();
  }

  @Test
  public void anAdminInstallsAFontAndEveryUserSeesIt() throws Exception {
    JsonObject upload = alice.upload("/fonts/", REGULAR).result();
    assertEquals("Test Sans", upload.get("familyName").getAsString());
    assertEquals("REGULAR", upload.get("suggestedStyle").getAsString());
    assertTrue(upload.get("variable").getAsBoolean());
    String regular = upload.get("file").getAsString();
    String bold = uploaded(alice, BOLD);

    JsonArray fonts = alice.post("AdminSaveFont", save(null, "Test Sans", false, "REGULAR", regular, "BOLD", bold)).result().getAsJsonArray("fonts");
    assertEquals(1, fonts.size());

    TestServer.Client bob = server.client().login("bob", "bob-password");
    JsonObject font = bob.post("Fonts", "{}").result().getAsJsonArray("fonts").get(0).getAsJsonObject();
    assertEquals("Test Sans", font.get("name").getAsString());
    assertEquals(2, font.getAsJsonArray("files").size());
    JsonObject first = font.getAsJsonArray("files").get(0).getAsJsonObject();
    assertEquals(100, first.get("weightMin").getAsInt());
    assertEquals(900, first.get("weightMax").getAsInt());

    HttpResponse<byte[]> file = bob.rawBytes("/fonts/" + regular);
    assertEquals(200, file.statusCode());
    assertArrayEquals(REGULAR, file.body());
    assertEquals("font/ttf", file.headers().firstValue("Content-Type").orElse(""));
    assertEquals("private, max-age=31536000, immutable", file.headers().firstValue("Cache-Control").orElse(""));
  }

  @Test
  public void onlyAdminsInstallChangeOrDelete() throws Exception {
    TestServer.Client bob = server.client().login("bob", "bob-password");
    assertEquals(403, bob.upload("/fonts/", REGULAR).status());
    String file = uploaded(alice, REGULAR);
    assertEquals(403, bob.post("AdminSaveFont", save(null, "Mine", false, "REGULAR", file)).status());
    String id = alice.post("AdminSaveFont", save(null, "Theirs", false, "REGULAR", file)).result()
        .getAsJsonArray("fonts").get(0).getAsJsonObject().get("id").getAsString();
    assertEquals(403, bob.post("AdminDeleteFont", "{\"id\":\"" + id + "\"}").status());
    assertEquals(0, alice.post("AdminDeleteFont", "{\"id\":\"" + id + "\"}").result().getAsJsonArray("fonts").size());
  }

  @Test
  public void uploadsAreGuardedAsTheApiIs() throws Exception {
    assertEquals(401, server.client().upload("/fonts/", REGULAR).status());
    assertEquals(409, server.client().login("alice", "alice-password").asBuild(null).upload("/fonts/", REGULAR).status());
    assertEquals(404, alice.upload("/fonts/elsewhere", REGULAR).status());
    TestServer.Response notAFont = alice.upload("/fonts/", "hello".getBytes());
    assertEquals(400, notAFont.status());
    assertFalse(notAFont.json().get("ok").getAsBoolean());
    byte[] large = Arrays.copyOf(REGULAR, 5 * 1024 * 1024 + 1);
    assertEquals(413, alice.upload("/fonts/", large).status());
  }

  @Test
  public void filesAreForLoggedInUsersOnly() throws Exception {
    String file = uploaded(alice, REGULAR);
    assertEquals(401, server.client().rawBytes("/fonts/" + file).statusCode());
    assertEquals(404, alice.rawBytes("/fonts/" + "0".repeat(64) + ".ttf").statusCode());
    assertEquals(404, alice.rawBytes("/fonts/fonts.json").statusCode());
  }

  @Test
  public void twoFilesForOneStyleIsRefused() throws Exception {
    String a = uploaded(alice, REGULAR);
    String b = uploaded(alice, BOLD);
    TestServer.Response response = alice.post("AdminSaveFont", save(null, "Twice", false, "REGULAR", a, "REGULAR", b));
    assertEquals(400, response.status());
    assertEquals(400, alice.post("AdminSaveFont", save(null, "Unknown", false, "HEAVY", a)).status());
  }

  @Test
  public void thePagesMayLoadFonts() throws Exception {
    assertTrue(alice.raw("/").headers().firstValue("Content-Security-Policy").orElse("").contains("font-src 'self'"));
  }
}
