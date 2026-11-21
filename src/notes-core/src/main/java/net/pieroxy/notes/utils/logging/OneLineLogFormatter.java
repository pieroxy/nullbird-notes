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
      sb.append(sw);
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
    return timestamp + ' ' + RUN_ID + ' ' + rightAlign(level.getName(), LEVEL_WIDTH) + ' ' + rightAlign(source, SOURCE_WIDTH) + ' ' + message + '\n';
  }

  private static String rightAlign(String s, int width) {
    if (s == null) s = "";
    return s.length() >= width ? s.substring(s.length() - width) : " ".repeat(width - s.length()) + s;
  }
}
