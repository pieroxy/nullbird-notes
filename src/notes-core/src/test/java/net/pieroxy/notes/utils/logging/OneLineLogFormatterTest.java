package net.pieroxy.notes.utils.logging;

import org.junit.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OneLineLogFormatterTest {

  private static final Pattern LINE_PATTERN = Pattern.compile(
      "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} ([0-9A-Za-z]{4}) ([A-Z ]{7}) (.{30}) (.*)\n$");

  @Test
  public void padsShortLevelAndSourceOnTheLeft() {
    OneLineLogFormatter formatter = new OneLineLogFormatter();
    LogRecord record = new LogRecord(Level.INFO, "User \"alice\" logged in from 127.0.0.1");
    record.setSourceClassName("net.pieroxy.notes.api.implementations.auth.LoginApi");
    record.setSourceMethodName("process");

    Matcher m = LINE_PATTERN.matcher(formatter.format(record));
    assertTrue(m.matches());
    assertEquals("   INFO", m.group(2));
    assertEquals("ntations.auth.LoginApi/process", m.group(3));
    assertEquals("User \"alice\" logged in from 127.0.0.1", m.group(4));
  }

  @Test
  public void doesNotPadWhenLevelExactlyFillsTheField() {
    OneLineLogFormatter formatter = new OneLineLogFormatter();
    LogRecord record = new LogRecord(Level.WARNING, "boom");
    record.setSourceClassName("x");
    record.setSourceMethodName("y");

    Matcher m = LINE_PATTERN.matcher(formatter.format(record));
    assertTrue(m.matches());
    assertEquals("WARNING", m.group(2));
  }

  @Test
  public void truncatesLongSourceKeepingTheTail() {
    OneLineLogFormatter formatter = new OneLineLogFormatter();
    LogRecord record = new LogRecord(Level.SEVERE, "Grabbing free space for /media/pieroxy/FAC5-5F2E");
    record.setSourceClassName("net.pieroxy.somewhere.grabbers.JavaSystemViewGrabber");
    record.setSourceMethodName("sys");

    String line = formatter.format(record);
    assertTrue(line.contains(" SEVERE bers.JavaSystemViewGrabber/sys "));
  }

  @Test
  public void runIdStaysStableAcrossFormatterInstances() {
    LogRecord record = new LogRecord(Level.INFO, "hello");
    record.setSourceClassName("x");
    record.setSourceMethodName("y");

    String first = new OneLineLogFormatter().format(record);
    String second = new OneLineLogFormatter().format(record);

    Matcher m1 = LINE_PATTERN.matcher(first);
    Matcher m2 = LINE_PATTERN.matcher(second);
    assertTrue(m1.matches());
    assertTrue(m2.matches());
    assertEquals(m1.group(1), m2.group(1));
  }

  @Test
  public void appendsStackTraceWhenThrowableIsPresent() {
    OneLineLogFormatter formatter = new OneLineLogFormatter();
    LogRecord record = new LogRecord(Level.SEVERE, "failed");
    record.setSourceClassName("x");
    record.setSourceMethodName("y");
    record.setThrown(new RuntimeException("boom"));

    String line = formatter.format(record);
    assertTrue(line.contains("java.lang.RuntimeException: boom"));
  }

  @Test
  public void escapesControlCharactersAndBidiOverrides() {
    assertEquals("tag\\x1b[31mred\\x00 \\u202eevil \\u2028next \\u0085",
        OneLineLogFormatter.clean("tag\u001b[31mred\u0000 ‮evil  next \u0085", false));
    assertEquals("emoji 👩‍💻 and é stay\tas is", OneLineLogFormatter.clean("emoji 👩‍💻 and é stay\tas is", false));
  }

  @Test
  public void keepsLineBreaksButIndentsThoseThatCouldPassForAnEntry() {
    assertEquals("first\nsecond\n  2026-01-01 x\n\tat y\n", OneLineLogFormatter.clean("first\r\nsecond\r2026-01-01 x\n\tat y\n", false));
    assertEquals("  1 leading digit", OneLineLogFormatter.clean("1 leading digit", true));
    assertEquals("1 leading digit", OneLineLogFormatter.clean("1 leading digit", false));
  }
}
