package net.pieroxy.notes.standalone;

import com.google.gson.Gson;
import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.services.ClientGuardService;
import net.pieroxy.notes.services.ServiceProvider;
import net.pieroxy.notes.services.SessionService;
import net.pieroxy.notes.services.SettingsService;
import net.pieroxy.notes.services.WebServerService;
import net.pieroxy.notes.utils.BuildInfo;
import net.pieroxy.notes.utils.logging.LogRetention;
import net.pieroxy.notes.utils.logging.LoggingBootstrap;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

public class Runner {
  private final static Logger LOGGER = Logger.getLogger(Runner.class.getName());
  private static ServiceProvider serviceProvider;

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("hash-password")) {
      HashPassword.run();
      return;
    }
    if (args.length != 1) {
      System.err.println("Usage: java -jar notes-core.jar <configDir>");
      System.err.println("       java -jar notes-core.jar hash-password");
      System.exit(1);
      return;
    }

    File configDir = new File(args[0]);
    File configFile = new File(configDir, "config.json");
    File credentialsFile = new File(configDir, "credentials.json");
    if (!credentialsFile.isFile()) {
      System.err.println(credentialsFile + " not found. See credentials.example.json, and run"
          + " \"java -jar notes-core.jar hash-password\" to hash each user's password.");
      System.exit(1);
      return;
    }
    Configuration config = new Configuration();
    if (configFile.isFile()) {
      try (Reader reader = Files.newBufferedReader(configFile.toPath(), StandardCharsets.UTF_8)) {
        config = new Gson().fromJson(reader, Configuration.class);
      }
    }
    try {
      LogRetention.validate(config.getKeepLogFiles());
    } catch (IllegalArgumentException e) {
      System.err.println(configFile + ": " + e.getMessage());
      System.exit(1);
      return;
    }
    File dataFolder = config.resolveDataFolder(configDir).getAbsoluteFile().toPath().normalize().toFile();
    Files.createDirectories(dataFolder.toPath());
    LoggingBootstrap.configure(new File(dataFolder, "logs/log.txt").getAbsolutePath(), LogRetention.days(config.getKeepLogFiles()));
    LOGGER.info("Config directory: " + configDir.getAbsoluteFile().toPath().normalize() + ", data folder: " + dataFolder);

    try {
      serviceProvider = new ServiceProvider(new SettingsService(config, credentialsFile, dataFolder), new SessionService(),
          new ClientGuardService(), new WebServerService());
      serviceProvider.init();
    } catch (IllegalStateException | IllegalArgumentException e) {
      LOGGER.severe(e.getMessage());
      if (serviceProvider != null) serviceProvider.destroy();
      System.exit(1);
      return;
    }

    Runtime.getRuntime().addShutdownHook(new Thread(Runner::shutdown, "shutdown-hook"));
    LOGGER.info("Started notes version " + BuildInfo.VERSION + " rev " + BuildInfo.REVISION);
  }

  private static void shutdown() {
    logDirectly("Shutting down...");
    serviceProvider.destroy();
    LoggingBootstrap.shutdown();
    logDirectly("Shutdown complete.");
  }

  /**
   * java.util.logging's own shutdown hook resets its handlers, and hooks run in no guaranteed
   * order: a LOGGER call here could silently disappear. So this writes straight to stderr and to
   * the log file.
   */
  private static void logDirectly(String message) {
    String line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " " + message;
    System.err.println(line);
    try (Writer writer = Files.newBufferedWriter(Path.of(LoggingBootstrap.getLogFile()), StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
      writer.write(line + System.lineSeparator());
    } catch (IOException ignored) {
      // Best effort: nothing more reliable to do at this stage of shutdown.
    }
  }
}
