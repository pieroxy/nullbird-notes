package net.pieroxy.notes.services;

import net.pieroxy.notes.api.metadata.InvalidInputException;
import net.pieroxy.notes.api.metadata.NotFoundException;
import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.fonts.FontFamily;
import net.pieroxy.notes.fonts.FontFile;
import net.pieroxy.notes.fonts.FontStyle;
import net.pieroxy.notes.fonts.TestFonts;
import net.pieroxy.notes.fonts.UploadedFont;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class FontServiceTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private final MovableClock clock = new MovableClock();
  /** Tests run in parallel: the logger has the others' lines too. */
  private final List<String> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
  private final Handler handler = new Handler() {
    @Override
    public void publish(LogRecord record) {
      logs.add(record.getMessage());
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  };
  private FontService fonts;

  private static class MovableClock extends Clock {
    long millis = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

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
  }

  @Before
  public void setUp() {
    fonts = start();
    Logger.getLogger(FontService.class.getName()).addHandler(handler);
  }

  @After
  public void tearDown() {
    Logger.getLogger(FontService.class.getName()).removeHandler(handler);
  }

  /** A new service over the same folder, as after a restart. */
  private FontService start() {
    SettingsService settings = new SettingsService(new Configuration(), new File(tmp.getRoot(), "credentials.json"), tmp.getRoot());
    FontService service = new FontService(clock);
    ServiceProvider provider = new ServiceProvider(settings, new SessionService(), new ClientGuardService(), new NoteStoreService(),
        new NoteService(), new ImportService(), service, new WebServerService());
    service.init(provider.scopedFor(service));
    service.start();
    return service;
  }

  private UploadedFont upload(byte[] data) throws IOException {
    return fonts.upload("admin", "1.2.3.4", new ByteArrayInputStream(data));
  }

  private boolean logged(String text) {
    return logs.stream().anyMatch(line -> line.contains(text));
  }

  private Path folder() {
    return tmp.getRoot().toPath().resolve("fonts");
  }

  @Test
  public void anUploadedFileIsKeptUnderItsContentsName() throws IOException {
    byte[] data = TestFonts.ttf(700, false, "Some Sans", null, null);
    UploadedFont uploaded = upload(data);
    assertTrue(uploaded.file().matches("[0-9a-f]{64}\\.ttf"));
    assertEquals("truetype", uploaded.format());
    assertEquals(data.length, uploaded.size());
    assertEquals("Some Sans", uploaded.familyName());
    assertEquals(FontStyle.BOLD, uploaded.suggestedStyle());
    assertNotNull(fonts.file(uploaded.file()));
    assertTrue(logged("Admin \"admin\" from 1.2.3.4 uploaded the font file " + uploaded.file()));
    assertEquals(uploaded.file(), upload(data).file());
  }

  @Test
  public void notAFontOrTooLargeIsRefusedAndNotKept() throws IOException {
    assertThrows(InvalidInputException.class, () -> upload("not a font at all".getBytes()));
    byte[] large = java.util.Arrays.copyOf(TestFonts.ttf(400, false, "Big", null, null), (int) FontService.MAX_FILE_BYTES + 1);
    assertTrue(assertThrows(InvalidInputException.class, () -> upload(large)).getMessage().contains("5 MB"));
    try (var files = Files.list(folder())) {
      assertEquals(0, files.count());
    }
  }

  @Test
  public void aFamilyOfUpToFourFilesWithWhatTheySay() throws IOException {
    String regular = upload(TestFonts.ttf(400, false, "Flex", 100, 900)).file();
    String italic = upload(TestFonts.woff2(true)).file();
    List<FontFamily> list = fonts.save("admin", "1.2.3.4", null, "  Flex  ", false, Map.of(FontStyle.ITALIC, italic, FontStyle.REGULAR, regular));
    assertEquals(1, list.size());
    FontFamily family = list.get(0);
    assertTrue(family.id().matches("[0-9a-f]{16}"));
    assertEquals("Flex", family.name());
    assertEquals(List.of(FontStyle.REGULAR, FontStyle.ITALIC), family.files().stream().map(FontFile::style).toList());
    FontFile first = family.files().get(0);
    assertTrue(first.variable());
    assertEquals(Integer.valueOf(100), first.weightMin());
    assertEquals(Integer.valueOf(900), first.weightMax());
    assertEquals("woff2", family.files().get(1).format());
    assertTrue(family.files().get(1).variable());
    assertNull(family.files().get(1).weightMin());
    assertTrue(logged("installed the font \"Flex\" (" + family.id() + "): [regular " + regular + ", italic " + italic + "]"));
  }

  @Test
  public void namesAndFilesAreChecked() throws IOException {
    String file = upload(TestFonts.ttf(400, false, "A", null, null)).file();
    assertThrows(InvalidInputException.class, () -> fonts.save("admin", "ip", null, " ", false, Map.of(FontStyle.REGULAR, file)));
    assertThrows(InvalidInputException.class, () -> fonts.save("admin", "ip", null, "x".repeat(61), false, Map.of(FontStyle.REGULAR, file)));
    assertThrows(InvalidInputException.class, () -> fonts.save("admin", "ip", null, "Bad\nname", false, Map.of(FontStyle.REGULAR, file)));
    assertThrows(InvalidInputException.class, () -> fonts.save("admin", "ip", null, "Empty", false, Map.of()));
    assertThrows(InvalidInputException.class, () -> fonts.save("admin", "ip", null, "Gone", false, Map.of(FontStyle.REGULAR, "0".repeat(64) + ".ttf")));
    assertThrows(InvalidInputException.class, () -> fonts.save("admin", "ip", null, "Sneaky", false, Map.of(FontStyle.REGULAR, "../fonts.json")));
    fonts.save("admin", "ip", null, "Taken", false, Map.of(FontStyle.REGULAR, file));
    assertTrue(assertThrows(InvalidInputException.class, () -> fonts.save("admin", "ip", null, "TAKEN", false, Map.of(FontStyle.REGULAR, file)))
        .getMessage().contains("already a font named \"Taken\""));
    assertThrows(NotFoundException.class, () -> fonts.save("admin", "ip", "0123456789abcdef", "Other", false, Map.of(FontStyle.REGULAR, file)));
  }

  @Test
  public void aFamilyChangedKeepsItsIdAndTheListStaysByName() throws IOException {
    String a = upload(TestFonts.ttf(400, false, "A", null, null)).file();
    String b = upload(TestFonts.ttf(700, false, "B", null, null)).file();
    String id = fonts.save("admin", "ip", null, "Zeta", false, Map.of(FontStyle.REGULAR, a)).get(0).id();
    fonts.save("admin", "ip", null, "Alpha", false, Map.of(FontStyle.REGULAR, b));
    List<FontFamily> list = fonts.save("admin", "ip", id, "Mono", true, Map.of(FontStyle.REGULAR, a, FontStyle.BOLD, b));
    assertEquals(List.of("Alpha", "Mono"), list.stream().map(FontFamily::name).toList());
    FontFamily changed = list.get(1);
    assertEquals(id, changed.id());
    assertTrue(changed.monospace());
    assertEquals(2, changed.files().size());
  }

  @Test
  public void keptAcrossRestarts() throws IOException {
    String file = upload(TestFonts.otf(400, false, "Kept")).file();
    List<FontFamily> saved = fonts.save("admin", "ip", null, "Kept", true, Map.of(FontStyle.REGULAR, file));
    assertEquals(saved, start().list());
  }

  @Test
  public void deletingAFamilyDropsItsFilesAfterAWhileUnlessAnotherHasThem() throws IOException {
    String shared = upload(TestFonts.ttf(400, false, "Shared", null, null)).file();
    String own = upload(TestFonts.ttf(700, false, "Own", null, null)).file();
    String id = fonts.save("admin", "ip", null, "One", false, Map.of(FontStyle.REGULAR, shared, FontStyle.BOLD, own)).get(0).id();
    fonts.save("admin", "ip", null, "Two", false, Map.of(FontStyle.REGULAR, shared));

    assertEquals(List.of("Two"), fonts.delete("admin", "ip", id).stream().map(FontFamily::name).toList());
    assertNotNull("just unused: kept for now", fonts.file(own));
    assertTrue(logged("deleted the font \"One\" (" + id + ")"));
    assertThrows(NotFoundException.class, () -> fonts.delete("admin", "ip", id));

    clock.millis += FontService.UNUSED_KEPT_FOR.toMillis() + 1;
    String third = upload(TestFonts.ttf(400, true, "Third", null, null)).file();
    fonts.save("admin", "ip", null, "Three", false, Map.of(FontStyle.REGULAR, third));
    assertNull(fonts.file(own));
    assertNotNull(fonts.file(shared));
  }

  @Test
  public void aFileUploadedButNeverUsedGoesOnRestart() throws IOException {
    String unused = upload(TestFonts.ttf(400, false, "Unused", null, null)).file();
    Files.writeString(folder().resolve("fonts.json.tmp"), "left by a crash");
    start();
    assertNull(fonts.file(unused));
    assertFalse(Files.exists(folder().resolve("fonts.json.tmp")));
  }

  @Test
  public void onlyFontFileNamesAreServed() throws IOException {
    upload(TestFonts.ttf(400, false, "A", null, null));
    assertNull(fonts.file("fonts.json"));
    assertNull(fonts.file("../credentials.json"));
    assertNull(fonts.file(null));
    assertNull(fonts.file("0".repeat(64) + ".ttf"));
  }
}
