package net.pieroxy.notes.utils.logging;

import net.jpountz.lz4.LZ4FrameInputStream;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the application log back, as {@link OneLineLogFormatter} wrote it: the current file and the
 * archives {@link LogRotator} made of it. An entry is its first line plus the lines that follow
 * until the next entry: a stack trace, or a message spanning several lines.
 */
public final class LogReader {
  private static final Pattern ENTRY = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) (\\S{4}) +([A-Z]+) +(\\S+) ?(.*)$");

  private LogReader() {}

  /**
   * @param index   0 for the current file, N for archive N (the Nth rotation back).
   * @param size    on disk, compressed for an archive.
   * @param firstAt the time of its first entry, "" if it has none.
   */
  public record LogFile(int index, long size, String firstAt) {
  }

  /**
   * @param run     the instance ID of the process that wrote it: it changes at each restart.
   * @param details the lines after the first, "" if none.
   */
  public record Entry(String time, String run, String level, String source, String message, String details) {
  }

  /** The current file then the archives, newest first: those that exist. */
  public static List<LogFile> files(File logFile) throws IOException {
    List<LogFile> files = new ArrayList<>();
    if (logFile.isFile()) files.add(describe(logFile, 0));
    for (int i = 1; file(logFile, i).isFile(); i++) files.add(describe(file(logFile, i), i));
    return files;
  }

  /** @return the file holding {@code index} (see {@link LogFile#index()}); it may not exist. */
  public static File file(File logFile, int index) {
    return index == 0 ? logFile : new File(logFile.getPath() + "." + index + ".lz4");
  }

  /** Every entry of the file, oldest first, to {@code sink}. */
  public static void read(File logFile, int index, Consumer<Entry> sink) throws IOException {
    try (InputStream in = open(file(logFile, index), index)) {
      parse(in, sink);
    }
  }

  static void parse(InputStream in, Consumer<Entry> sink) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    Matcher first = null;
    StringBuilder details = new StringBuilder();
    for (String line; (line = reader.readLine()) != null; ) {
      Matcher matcher = ENTRY.matcher(line);
      if (matcher.matches()) {
        if (first != null || details.length() > 0) sink.accept(entry(first, details));
        first = matcher;
        details.setLength(0);
      } else {
        if (details.length() > 0) details.append('\n');
        details.append(line);
      }
    }
    if (first != null || details.length() > 0) sink.accept(entry(first, details));
  }

  /** Lines before any entry (a file not written by OneLineLogFormatter) make one with no time nor level. */
  private static Entry entry(Matcher first, StringBuilder details) {
    if (first == null) return new Entry("", "", "", "", "", details.toString());
    return new Entry(first.group(1), first.group(2), first.group(3), first.group(4), first.group(5), details.toString());
  }

  private static LogFile describe(File file, int index) throws IOException {
    String firstAt = "";
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(open(file, index), StandardCharsets.UTF_8))) {
      for (String line; (line = reader.readLine()) != null; ) {
        Matcher matcher = ENTRY.matcher(line);
        if (matcher.matches()) {
          firstAt = matcher.group(1);
          break;
        }
      }
    }
    return new LogFile(index, file.length(), firstAt);
  }

  private static InputStream open(File file, int index) throws IOException {
    InputStream in = Files.newInputStream(file.toPath());
    return index == 0 ? in : new LZ4FrameInputStream(in);
  }
}
