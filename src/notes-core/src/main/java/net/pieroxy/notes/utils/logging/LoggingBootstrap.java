package net.pieroxy.notes.utils.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Configures java.util.logging at startup, for everything that logs through it (Tomcat included,
 * via JULI). First lifts the ceiling of the handlers already in place (by default, the JDK's
 * ConsoleHandler filters out anything below INFO), so that a logger set to a finer level is
 * actually output. Then adds a log file receiving everything that passes through, rotated daily
 * (lz4 compression + pruning, see {@link LogRotator}) for as long as the program runs. The first
 * rotation is timed to midnight (local time) so that every archived file holds a full day.
 */
public final class LoggingBootstrap {
  private final static Logger LOGGER = Logger.getLogger(LoggingBootstrap.class.getName());
  private final static long ROTATION_PERIOD_HOURS = 24;

  private static String logFile;
  private static int keepLogFiles;
  private static FileHandler fileHandler;
  private static ScheduledExecutorService rotationScheduler;

  private LoggingBootstrap() {}

  /** @param keepLogFiles days to keep, {@link LogRetention#FOREVER} for all (see {@link LogRetention#days}). */
  public static synchronized void configure(String logFile, int keepLogFiles) {
    // Tomcat's logging shim (org.apache.juli.logging.DirectJDKLog) replaces the root logger's
    // ConsoleHandler formatter the first time any Tomcat class logs, unless this property points
    // it at ours. Must be set before any Tomcat class loads.
    System.setProperty("org.apache.juli.formatter", OneLineLogFormatter.class.getName());
    for (Handler handler : Logger.getLogger("").getHandlers()) {
      handler.setLevel(Level.ALL);
      handler.setFormatter(new OneLineLogFormatter());
    }

    LoggingBootstrap.logFile = logFile;
    LoggingBootstrap.keepLogFiles = keepLogFiles;
    openFileHandler();
    rotationScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "log-rotation");
      t.setDaemon(true);
      return t;
    });
    rotationScheduler.scheduleAtFixedRate(
        LoggingBootstrap::rotate,
        millisUntilNextMidnight(),
        TimeUnit.HOURS.toMillis(ROTATION_PERIOD_HOURS),
        TimeUnit.MILLISECONDS);
  }

  /** Also governs the access log's retention (see AccessLog). */
  public static synchronized int getKeepLogFiles() {
    return keepLogFiles;
  }

  /** Null until {@link #configure}d. */
  public static synchronized String getLogFile() {
    return logFile;
  }

  public static void shutdown() {
    if (rotationScheduler != null) {
      rotationScheduler.shutdownNow();
    }
  }

  private static long millisUntilNextMidnight() {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
    return Duration.between(now, nextMidnight).toMillis();
  }

  private static synchronized void openFileHandler() {
    try {
      Path parent = Path.of(logFile).toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      FileHandler handler = new FileHandler(logFile, true);
      handler.setEncoding("UTF-8");
      handler.setFormatter(new OneLineLogFormatter());
      handler.setLevel(Level.ALL);
      Logger.getLogger("").addHandler(handler);
      fileHandler = handler;
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, "Could not open log file " + logFile, e);
    }
  }

  private static synchronized void rotate() {
    if (fileHandler == null) return;
    Logger.getLogger("").removeHandler(fileHandler);
    fileHandler.close();
    try {
      LogRotator.rotate(logFile, keepLogFiles);
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, "Could not rotate log file " + logFile, e);
    }
    openFileHandler();
  }
}
