package net.pieroxy.notes.standalone;

import com.google.gson.Gson;
import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.services.ClientGuardService;
import net.pieroxy.notes.services.ServiceProvider;
import net.pieroxy.notes.services.SessionService;
import net.pieroxy.notes.services.SettingsService;
import net.pieroxy.notes.services.WebServerService;
import net.pieroxy.notes.utils.BuildInfo;
import net.pieroxy.notes.utils.logging.LoggingBootstrap;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    LoggingBootstrap.configure();
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
    File dataFolder = config.resolveDataFolder(configDir).getAbsoluteFile().toPath().normalize().toFile();
    Files.createDirectories(dataFolder.toPath());
    LOGGER.info("Config directory: " + configDir.getAbsoluteFile().toPath().normalize() + ", data folder: " + dataFolder);

    serviceProvider = new ServiceProvider(new SettingsService(config, credentialsFile, dataFolder), new SessionService(),
        new ClientGuardService(), new WebServerService());
    try {
      serviceProvider.init();
    } catch (IllegalStateException | IllegalArgumentException e) {
      LOGGER.severe(e.getMessage());
      serviceProvider.destroy();
      System.exit(1);
      return;
    }

    Runtime.getRuntime().addShutdownHook(new Thread(Runner::shutdown, "shutdown-hook"));
    LOGGER.info("Started notes version " + BuildInfo.VERSION + " rev " + BuildInfo.REVISION);
  }

  private static void shutdown() {
    logDirectly("Shutting down...");
    serviceProvider.destroy();
    logDirectly("Shutdown complete.");
  }

  /**
   * java.util.logging's own shutdown hook resets its handlers, and hooks run in no guaranteed
   * order: a LOGGER call here could silently disappear.
   */
  private static void logDirectly(String message) {
    System.err.println(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " " + message);
  }
}
