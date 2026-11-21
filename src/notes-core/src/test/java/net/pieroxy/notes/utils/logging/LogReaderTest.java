package net.pieroxy.notes.utils.logging;

import net.jpountz.lz4.LZ4FrameOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.Assert.assertEquals;

public class LogReaderTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private static List<LogReader.Entry> parse(String text) throws Exception {
    List<LogReader.Entry> entries = new ArrayList<>();
    LogReader.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), entries::add);
    return entries;
  }

  @Test
  public void readsWhatTheFormatterWritesStackTracesIncluded() throws Exception {
    OneLineLogFormatter formatter = new OneLineLogFormatter();
    LogRecord info = new LogRecord(Level.INFO, "User \"alice\" logged in from 127.0.0.1");
    info.setSourceClassName("net.pieroxy.notes.api.implementations.auth.LoginApi");
    info.setSourceMethodName("process");
    LogRecord severe = new LogRecord(Level.SEVERE, "Endpoint failed");
    severe.setSourceClassName("Short");
    severe.setSourceMethodName("m");
    severe.setThrown(new IllegalStateException("boom"));

    List<LogReader.Entry> entries = parse(formatter.format(info) + formatter.format(severe));

    assertEquals(2, entries.size());
    LogReader.Entry first = entries.get(0);
    assertEquals("INFO", first.level());
    assertEquals("ntations.auth.LoginApi/process", first.source());
    assertEquals("User \"alice\" logged in from 127.0.0.1", first.message());
    assertEquals("", first.details());
    assertEquals(4, first.run().length());
    LogReader.Entry second = entries.get(1);
    assertEquals("SEVERE", second.level());
    assertEquals("Short/m", second.source());
    assertEquals("Endpoint failed", second.message());
    assertEquals("java.lang.IllegalStateException: boom", second.details().lines().findFirst().orElse(""));
  }

  @Test
  public void anEmptyMessageAndLinesBeforeAnyEntry() throws Exception {
    List<LogReader.Entry> entries = parse("stray line\n2026-11-21 06:00:00 Ab12    INFO  Some/thing\n");

    assertEquals(2, entries.size());
    assertEquals("", entries.get(0).level());
    assertEquals("stray line", entries.get(0).details());
    assertEquals("", entries.get(1).message());
    assertEquals("Some/thing", entries.get(1).source());
  }

  @Test
  public void theCurrentFileThenTheArchivesNewestFirst() throws Exception {
    File log = new File(tmp.getRoot(), "log.txt");
    Files.writeString(log.toPath(), "2026-11-21 00:00:01 Ab12    INFO  A/b today\n");
    try (OutputStream out = new LZ4FrameOutputStream(Files.newOutputStream(new File(tmp.getRoot(), "log.txt.1.lz4").toPath()))) {
      out.write("2026-11-20 00:00:01 Ab12 WARNING  A/b yesterday\n2026-11-20 10:00:00 Ab12    INFO  A/b later\n".getBytes(StandardCharsets.UTF_8));
    }

    List<LogReader.LogFile> files = LogReader.files(log);

    assertEquals(2, files.size());
    assertEquals(0, files.get(0).index());
    assertEquals("2026-11-21 00:00:01", files.get(0).firstAt());
    assertEquals(1, files.get(1).index());
    assertEquals("2026-11-20 00:00:01", files.get(1).firstAt());
    List<LogReader.Entry> archived = new ArrayList<>();
    LogReader.read(log, 1, archived::add);
    assertEquals(List.of("yesterday", "later"), archived.stream().map(LogReader.Entry::message).toList());
  }
}
