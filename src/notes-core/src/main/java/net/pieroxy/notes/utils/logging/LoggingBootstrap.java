package net.pieroxy.notes.utils.logging;

import java.util.logging.Handler;
import java.util.logging.Logger;

/** Logs go to the console only, one line per entry (see {@link OneLineLogFormatter}). */
public final class LoggingBootstrap {
  private LoggingBootstrap() {}

  public static void configure() {
    for (Handler handler : Logger.getLogger("").getHandlers()) {
      handler.setFormatter(new OneLineLogFormatter());
    }
  }
}
