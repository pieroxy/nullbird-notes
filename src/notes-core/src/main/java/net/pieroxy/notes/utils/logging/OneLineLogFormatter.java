package net.pieroxy.notes.utils.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Formats each log entry on a single line: timestamp, random instance ID (4 base62 characters,
 * generated once at process startup, to immediately spot in the logs if two instances are running
 * at the same time, and to tell one run from the next), level and origin (class/method) aligned to a fixed width (truncated from the left if
 * they overflow, to always keep the most significant part), then the message.
 * <p>
 * A message holds what users typed (tag names, IDs…): it can't make a line that reads as an entry of
 * its own. Its other lines, and a stack trace's, are indented when they start with a digit, as an entry
 * does with its date: they're never taken for one (see {@link LogReader}). Control characters, Unicode line separators and bidi overrides are escaped.
 */
public final class OneLineLogFormatter extends Formatter {
  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final int LEVEL_WIDTH = 7;
  private static final int SOURCE_WIDTH = 30;
  // Base62, not base64: letters and digits only, so the ID stays one word for grep and double-click.
  private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final String RUN_ID = randomRunId();

  private static String randomRunId() {
    StringBuilder id = new StringBuilder(4);
    for (int i = 0; i < 4; i++) id.append(BASE62.charAt(ThreadLocalRandom.current().nextInt(BASE62.length())));
    return id.toString();
  }

  @Override
  public String format(LogRecord record) {
    String source = record.getSourceClassName() + "/" + record.getSourceMethodName();
    StringBuilder sb = new StringBuilder(line(record.getMillis(), record.getLevel(), source, formatMessage(record)));

    if (record.getThrown() != null) {
      StringWriter sw = new StringWriter();
      record.getThrown().printStackTrace(new PrintWriter(sw));
      sb.append(clean(sw.toString(), true));
    }
    return sb.toString();
  }

  /**
   * One entry's line, newline included, as {@link #format} writes it: for what's written without
   * java.util.logging (see Runner's shutdown), so that it reads back like any other entry.
   *
   * @param source class/method.
   */
  public static String line(long millis, Level level, String source, String message) {
    String timestamp = TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    return timestamp + ' ' + RUN_ID + ' ' + rightAlign(level.getName(), LEVEL_WIDTH) + ' ' + rightAlign(source, SOURCE_WIDTH) + ' ' + clean(message, false) + '\n';
  }

  /**
   * @param continued true: its first line is a continuation too (a stack trace after the message).
   * @return {@code text} with no continuation line starting with a digit, and nothing that a
   *         terminal or the log viewer would show as a line break, or use to reorder the text.
   */
  static String clean(String text, boolean continued) {
    if (text == null) return "";
    StringBuilder sb = new StringBuilder(text.length() + 16);
    boolean lineStart = continued;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\r' || c == '\n') {
        if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        sb.append('\n');
        lineStart = true;
        continue;
      }
      if (lineStart && c >= '0' && c <= '9') sb.append("  ");
      lineStart = false;
      if (c == '\t') sb.append(c);
      else if (c < 0x20 || c == 0x7f) sb.append(String.format("\\x%02x", (int) c));
      else if ((c >= 0x80 && c <= 0x9f) || c == 0x2028 || c == 0x2029 || (c >= 0x202a && c <= 0x202e)
          || (c >= 0x2066 && c <= 0x2069) || c == 0x200e || c == 0x200f || c == 0x061c) sb.append(String.format("\\u%04x", (int) c));
      else sb.append(c);
    }
    return sb.toString();
  }

  private static String rightAlign(String s, int width) {
    if (s == null) s = "";
    return s.length() >= width ? s.substring(s.length() - width) : " ".repeat(width - s.length()) + s;
  }
}
