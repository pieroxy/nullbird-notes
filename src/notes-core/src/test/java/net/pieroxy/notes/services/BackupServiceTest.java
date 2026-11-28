package net.pieroxy.notes.services;

import net.pieroxy.notes.config.BackupConfiguration;
import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.sync.NoteChange;
import net.pieroxy.notes.utils.Hashes;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BackupServiceTest {
  private static final String NOTE = "00000001-0000-4000-8000-000000000001";

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private final MovableClock clock = new MovableClock();
  private final List<LogRecord> logged = Collections.synchronizedList(new ArrayList<>());
  private final Handler handler = new Handler() {
    @Override
    public void publish(LogRecord record) {
      logged.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  };
  private Path configDir;
  private Path data;
  private Path folder;
  private NoteService notes;
  private BackupService backups;
  private int revision;

  private static class MovableClock extends Clock {
    long millis = Instant.parse("2026-01-01T03:00:00Z").toEpochMilli();

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }

    long advance(Duration duration) {
      return millis += duration.toMillis();
    }
  }

  @Before
  public void setUp() throws IOException {
    configDir = tmp.newFolder("config").toPath();
    data = configDir.resolve("data");
    Files.createDirectories(data);
    folder = tmp.newFolder("backups").toPath();
    Files.writeString(configDir.resolve("config.json"), "{\"httpPort\": 9000}");
    Files.writeString(configDir.resolve("credentials.json"), "{\"users\": []}");
    Files.writeString(data.resolve("sessions.json"), "{}");
    Files.createDirectories(data.resolve("logs"));
    Files.writeString(data.resolve("logs").resolve("notes.log"), "a log line");

    Configuration config = new Configuration();
    config.setMaxNoteSizeMb(1);
    SettingsService settings = new SettingsService(config, configDir.resolve("config.json").toFile(), configDir.resolve("credentials.json").toFile(),
        data.toFile());
    NoteStoreService stores = new NoteStoreService();
    notes = new NoteService();
    backups = new BackupService(clock);
    ServiceProvider provider = new ServiceProvider(settings, new SessionService(), new ClientGuardService(), stores, notes,
        new ImportService(), new FontService(), new WebServerService(), new SecondFactorService(), backups);
    stores.init(provider);
    notes.init(provider);
    backups.init(provider);
    backups.start();
    backups.configure(new BackupConfiguration(folder.toString(), BackupConfiguration.Frequency.HOURLY, 3));
    Logger.getLogger(BackupService.class.getName()).addHandler(handler);
  }

  @After
  public void tearDown() {
    Logger.getLogger(BackupService.class.getName()).removeHandler(handler);
    backups.destroy();
    notes.destroy();
  }

  private void write(String content) throws IOException {
    notes.push("alice", "device", new NoteChange("change-" + revision, NOTE, revision, null, content, null, Hashes.sha256(content), false, null));
    revision++;
  }

  private List<String> files() throws IOException {
    try (Stream<Path> list = Files.list(folder)) {
      return list.map(p -> p.getFileName().toString()).sorted().toList();
    }
  }

  private String refusal(String path) {
    return assertThrows(IllegalArgumentException.class, () -> backups.check(path)).getMessage();
  }

  @Test
  public void theFolderMustExistBeOutsideTheDataAndHoldBackupsOnly() throws IOException {
    assertEquals("Choose a folder.", refusal(" "));
    assertTrue(refusal("backups").contains("full path"));
    assertTrue(refusal(folder.resolve("missing").toString()).contains("doesn't exist"));
    assertFalse("never created", Files.exists(folder.resolve("missing")));
    Path file = Files.writeString(tmp.getRoot().toPath().resolve("file.txt"), "x");
    assertTrue(refusal(file.toString()).contains("isn't a folder"));
    Path inside = Files.createDirectories(data.resolve("backups"));
    assertTrue(refusal(inside.toString()).contains("inside the data folder"));
    assertTrue(refusal(configDir.getParent().toString()).contains("is inside this folder"));

    assertEquals(folder, backups.check(folder.toString()));
    Files.writeString(folder.resolve("notes-backup-2025-12-31T03-00-00Z.zip"), "");
    Files.createDirectory(folder.resolve("lost+found"));
    assertEquals(folder, backups.check(folder + "/"));
    assertEquals("the check leaves nothing", List.of("lost+found", "notes-backup-2025-12-31T03-00-00Z.zip"), files());

    Files.writeString(folder.resolve("photo.jpg"), "");
    assertTrue(refusal(folder.toString()).contains("\"photo.jpg\""));
  }

  @Test
  public void aBackupHoldsTheNotesUsersAndSettingsReadableByTheServerOnly() throws Exception {
    write("Groceries\nmilk");
    Files.writeString(folder.resolve("notes-backup-2025-12-31T03-00-00Z.zip.partial"), "a crash");

    backups.run(true);

    assertEquals("the crash's leftover gone", List.of("notes-backup-2026-01-01T03-00-00Z.zip"), files());
    Path zip = folder.resolve("notes-backup-2026-01-01T03-00-00Z.zip");
    try (ZipFile file = new ZipFile(zip.toFile())) {
      List<String> names = file.stream().map(ZipEntry::getName).toList();
      assertTrue(names.contains("config.json"));
      assertTrue(names.contains("credentials.json"));
      assertTrue(names.contains("data/users/alice/notes/" + NOTE + "/content.txt"));
      assertTrue(names.stream().noneMatch(n -> n.contains("sessions") || n.contains("logs")));
      String content = new String(file.getInputStream(file.getEntry("data/users/alice/notes/" + NOTE + "/content.txt")).readAllBytes(), StandardCharsets.UTF_8);
      assertEquals("Groceries\nmilk", content);
    }
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(zip)));
    BackupService.Status status = backups.status();
    assertEquals("notes-backup-2026-01-01T03-00-00Z.zip", status.lastBackupFile());
    assertEquals(Long.valueOf(Files.size(zip)), status.lastBackupSize());
    assertNull(status.lastError());
    assertEquals(Long.valueOf(clock.millis() + Duration.ofHours(1).toMillis()), status.nextAt());
  }

  @Test
  public void nothingChangedNoBackupUnlessAskedForOrTheLastOneIsGone() throws IOException {
    write("one");
    backups.run(false);
    clock.advance(Duration.ofHours(1));

    backups.run(false);
    assertEquals(1, files().size());

    write("two");
    backups.run(false);
    assertEquals(2, files().size());

    clock.advance(Duration.ofHours(1));
    backups.run(true);
    assertEquals("asked for", 3, files().size());

    Files.delete(folder.resolve(backups.status().lastBackupFile()));
    clock.advance(Duration.ofHours(1));
    backups.run(false);
    assertEquals(3, files().size());
  }

  @Test
  public void theOldestBeyondTheNumberToKeepGo() throws IOException {
    for (int i = 0; i < 5; i++) {
      write("version " + i);
      backups.run(false);
      clock.advance(Duration.ofHours(1));
    }

    assertEquals(List.of("notes-backup-2026-01-01T05-00-00Z.zip", "notes-backup-2026-01-01T06-00-00Z.zip", "notes-backup-2026-01-01T07-00-00Z.zip"),
        files());
  }

  @Test
  public void dueOnTimeAndRightAwayForAnotherFolder() throws IOException {
    write("one");
    backups.runIfDue();
    assertEquals("the first one right away", 1, files().size());
    write("two");

    clock.advance(Duration.ofMinutes(59));
    backups.runIfDue();
    assertEquals(1, files().size());
    clock.advance(Duration.ofMinutes(1));
    backups.runIfDue();
    assertEquals(2, files().size());

    Path other = tmp.newFolder("other").toPath();
    backups.configure(new BackupConfiguration(other.toString(), BackupConfiguration.Frequency.DAILY, 3));
    clock.advance(Duration.ofMinutes(1));
    backups.runIfDue();
    try (Stream<Path> list = Files.list(other)) {
      assertEquals("another folder: one of its own, though nothing changed", 1, list.count());
    }
    assertEquals(Long.valueOf(clock.millis() + Duration.ofDays(1).toMillis()), backups.status().nextAt());
  }

  @Test
  public void aFolderGoneFailsSevereNothingCreatedTriedAgainAnHourLater() throws IOException {
    write("one");
    backups.configure(new BackupConfiguration(folder.toString(), BackupConfiguration.Frequency.DAILY, 3));
    backups.run(true);
    Files.delete(folder.resolve(backups.status().lastBackupFile()));
    Files.delete(folder);
    long failedAt = clock.advance(Duration.ofDays(1));

    backups.runIfDue();

    assertFalse(Files.exists(folder));
    BackupService.Status status = backups.status();
    assertTrue(status.lastError(), status.lastError().contains("doesn't exist"));
    assertEquals(Long.valueOf(failedAt), status.lastErrorAt());
    assertEquals(Long.valueOf(failedAt + Duration.ofHours(1).toMillis()), status.nextAt());
    assertTrue(logged.stream().anyMatch(r -> r.getLevel() == Level.SEVERE && r.getMessage().contains("doesn't exist")));

    Files.createDirectory(folder);
    clock.advance(Duration.ofMinutes(30));
    backups.runIfDue();
    assertEquals("not before the hour", 0, files().size());
    clock.advance(Duration.ofMinutes(30));
    backups.runIfDue();
    assertEquals(1, files().size());
    assertNull(backups.status().lastError());
  }

  @Test
  public void whatsDueSurvivesARestart() throws IOException {
    write("one");
    backups.run(true);
    BackupService.Status before = backups.status();
    backups.destroy();

    BackupService restarted = new BackupService(clock);
    try {
      ServiceProvider provider = new ServiceProvider(new SettingsService(new Configuration(), configDir.resolve("config.json").toFile(),
          configDir.resolve("credentials.json").toFile(), data.toFile()), new SessionService(), new ClientGuardService(), new NoteStoreService(),
          new NoteService(), new ImportService(), new FontService(), new WebServerService(), new SecondFactorService(), restarted);
      restarted.init(provider.scopedFor(restarted));
      restarted.start();
      restarted.configure(new BackupConfiguration(folder.toString(), BackupConfiguration.Frequency.HOURLY, 3));
      assertEquals(before.lastBackupFile(), restarted.status().lastBackupFile());
      assertEquals(before.nextAt(), restarted.status().nextAt());
      assertNotNull(restarted.status().nextAt());
    } finally {
      restarted.destroy();
    }
  }
}
