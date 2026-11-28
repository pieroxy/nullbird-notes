package net.pieroxy.notes.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** AdminBackup, AdminSaveBackup, AdminBackupNow and AdminDeactivateBackup through HTTP. */
public class AdminBackupApiTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private TestServer server;
  private TestServer.Client alice;
  private Path folder;

  @Before
  public void setUp() throws Exception {
    server = new TestServer(tmp.newFolder("config"), Map.of("alice", "alice-password", "bob", "bob-password"), Set.of("alice"));
    alice = server.client().login("alice", "alice-password");
    folder = tmp.newFolder("backups").toPath();
  }

  @After
  public void tearDown() {
    server.close();
  }

  private JsonObject savedConfig() throws Exception {
    File file = new File(tmp.getRoot(), "config/config.json");
    return file.exists() ? JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
  }

  private static String save(String folder, String every, int keep) {
    return "{\"folder\":\"" + folder + "\",\"every\":\"" + every + "\",\"keep\":" + keep + "}";
  }

  private long backupsIn(Path dir) throws Exception {
    try (Stream<Path> list = Files.list(dir)) {
      return list.filter(p -> p.getFileName().toString().endsWith(".zip")).count();
    }
  }

  /** Once the backup asked for is made. */
  private JsonObject settled() throws Exception {
    for (int i = 0; i < 100; i++) {
      JsonObject status = alice.get("AdminBackup", "{}").result();
      if (!status.get("running").getAsBoolean()) return status;
      Thread.sleep(50);
    }
    throw new AssertionError("still running");
  }

  @Test
  public void onlyAdminsMayCall() throws Exception {
    TestServer.Client bob = server.client().login("bob", "bob-password");

    assertEquals(403, bob.get("AdminBackup", "{}").status());
    assertEquals(403, bob.post("AdminSaveBackup", save(folder.toString(), "DAILY", 7)).status());
    assertEquals(403, bob.post("AdminBackupNow", "{}").status());
    assertEquals(403, bob.post("AdminDeactivateBackup", "{}").status());
    assertFalse(savedConfig().has("backup"));
  }

  @Test
  public void offByDefaultAFolderThatIsntThereRefused() throws Exception {
    JsonObject status = alice.get("AdminBackup", "{}").result();
    assertFalse(status.get("active").getAsBoolean());
    assertEquals("DAILY", status.get("every").getAsString());
    assertEquals(14, status.get("keep").getAsInt());

    JsonObject refused = alice.post("AdminSaveBackup", save(folder.resolve("usb").toString(), "DAILY", 7)).result();
    assertFalse(refused.get("ok").getAsBoolean());
    assertTrue(refused.get("error").getAsString().contains("doesn't exist"));
    refused = alice.post("AdminSaveBackup", save(folder.toString(), "DAILY", 0)).result();
    assertTrue(refused.get("error").getAsString().contains("between 1 and"));
    assertFalse(alice.post("AdminBackupNow", "{}").result().get("ok").getAsBoolean());
    assertFalse(savedConfig().has("backup"));
  }

  @Test
  public void activatedSavedAndABackupRightAwayDeactivatedTheBackupsStay() throws Exception {
    JsonObject result = alice.post("AdminSaveBackup", save(folder.toString(), "HOURLY", 5)).result();
    assertTrue(result.toString(), result.get("ok").getAsBoolean());

    JsonObject saved = savedConfig().getAsJsonObject("backup");
    assertEquals(folder.toString(), saved.get("folder").getAsString());
    assertEquals("HOURLY", saved.get("every").getAsString());
    assertEquals(5, saved.get("keep").getAsInt());
    JsonObject status = settled();
    assertTrue(status.get("active").getAsBoolean());
    assertTrue(status.get("lastBackupFile").getAsString().startsWith("notes-backup-"));
    assertTrue(status.get("nextAt").getAsLong() > System.currentTimeMillis());
    assertEquals(1, backupsIn(folder));

    Thread.sleep(1100);
    assertTrue(alice.post("AdminBackupNow", "{}").result().get("ok").getAsBoolean());
    settled();
    assertEquals("asked for, made though nothing changed", 2, backupsIn(folder));

    assertTrue(alice.post("AdminDeactivateBackup", "{}").result().get("ok").getAsBoolean());
    assertFalse(savedConfig().has("backup"));
    status = alice.get("AdminBackup", "{}").result();
    assertFalse(status.get("active").getAsBoolean());
    assertNull(status.get("nextAt"));
    assertEquals(2, backupsIn(folder));
  }
}
