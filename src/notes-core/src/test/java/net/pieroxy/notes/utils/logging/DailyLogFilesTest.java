package net.pieroxy.notes.utils.logging;

import net.jpountz.lz4.LZ4FrameInputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DailyLogFilesTest {
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private final DailyLogFiles files = new DailyLogFiles("access-", ".log");

  @Test
  public void appendsToTodaysFile() throws IOException {
    files.append(tmp.getRoot(), TODAY, "one", 30);
    files.append(tmp.getRoot(), TODAY, "two", 30);

    assertEquals(List.of("one", "two"), Files.readAllLines(files.fileFor(tmp.getRoot(), TODAY).toPath()));
  }

  @Test
  public void theFirstWriteOfADayCompressesTheEarlierDays() throws IOException {
    files.append(tmp.getRoot(), TODAY.minusDays(2), "older", 30);
    files.append(tmp.getRoot(), TODAY.minusDays(1), "yesterday", 30);
    files.append(tmp.getRoot(), TODAY, "today", 30);

    File yesterday = files.fileFor(tmp.getRoot(), TODAY.minusDays(1));
    assertFalse(yesterday.exists());
    assertEquals("yesterday" + System.lineSeparator(), decompress(new File(yesterday.getPath() + ".lz4")));
    assertTrue(new File(files.fileFor(tmp.getRoot(), TODAY.minusDays(2)).getPath() + ".lz4").isFile());
    assertEquals(3, files.list(tmp.getRoot()).size());
  }

  @Test
  public void deletesTheDaysBeyondTheRetention() throws IOException {
    files.append(tmp.getRoot(), TODAY.minusDays(3), "expired", 3);
    files.append(tmp.getRoot(), TODAY.minusDays(2), "kept", 3);
    files.append(tmp.getRoot(), TODAY, "today", 3);

    List<DailyLogFiles.DayFile> left = files.list(tmp.getRoot());
    assertEquals(List.of(TODAY, TODAY.minusDays(2)), left.stream().map(DailyLogFiles.DayFile::day).toList());
  }

  @Test
  public void keepsEverythingForever() throws IOException {
    files.append(tmp.getRoot(), TODAY.minusDays(400), "ancient", LogRetention.FOREVER);
    files.append(tmp.getRoot(), TODAY, "today", LogRetention.FOREVER);

    assertEquals(2, files.list(tmp.getRoot()).size());
  }

  private static String decompress(File file) throws IOException {
    try (InputStream in = new LZ4FrameInputStream(new FileInputStream(file))) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
