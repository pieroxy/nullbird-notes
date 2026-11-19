package net.pieroxy.notes.services;

import net.pieroxy.notes.api.ApiServlet;
import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.webserver.AccessValve;
import net.pieroxy.notes.webserver.WebappResourceExtractor;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.startup.Tomcat;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Embedded Tomcat serving the webapp's static files and {@code /api/*}. */
public class WebServerService implements Service {
  private final static Logger LOGGER = Logger.getLogger(WebServerService.class.getName());
  // Below this, compressing costs more (CPU, framing overhead) than it saves on the wire.
  private final static int COMPRESSION_MIN_SIZE_BYTES = 1024;
  private final static String COMPRESSIBLE_MIME_TYPES =
      "text/html,text/css,application/javascript,image/svg+xml,application/json";

  private IServiceProvider serviceProvider;
  private Tomcat tomcat;

  @Override
  public List<Class<? extends Service>> getDependencies() {
    return List.of(SettingsService.class, SessionService.class, ClientGuardService.class, NoteStoreService.class, NoteService.class);
  }

  @Override
  public void init(IServiceProvider serviceProvider) {
    this.serviceProvider = serviceProvider;
  }

  @Override
  public void start() {
    SettingsService settings = serviceProvider.getSettingsService();
    Configuration config = settings.getConfig();
    if (config.getHttpPort() < 1 || config.getHttpPort() > 65535) {
      throw new IllegalStateException("httpPort configured to an invalid value of " + config.getHttpPort());
    }

    try {
      File webappDir = new File(settings.getDataFolder(), "webapp-ui");
      WebappResourceExtractor.extract(webappDir);

      tomcat = new Tomcat();
      tomcat.setBaseDir(new File(settings.getDataFolder(), "tomcat").getAbsolutePath());
      tomcat.setConnector(newConnector(config.getHttpPort(), config.getAddress()));
      tomcat.getEngine().getPipeline().addValve(new AccessValve(serviceProvider));

      StandardContext ctx = (StandardContext) tomcat.addContext("", webappDir.getAbsolutePath());
      ctx.addWelcomeFile("index.html");
      tomcat.addServlet("", "default", new DefaultServlet());
      ctx.addServletMappingDecoded("/", "default");
      tomcat.addServlet("", "api", new ApiServlet(endpointServiceProvider()));
      ctx.addServletMappingDecoded("/api/*", "api");
      addMimeTypes(ctx);

      tomcat.start();
      String address = config.getAddress() == null || config.getAddress().isBlank() ? "*" : config.getAddress();
      LOGGER.info("Web server listening on " + address + ":" + config.getHttpPort());
    } catch (LifecycleException | IOException e) {
      throw new IllegalStateException("Could not start the web server", e);
    }
  }

  @Override
  public void destroy() {
    if (tomcat == null) return;
    try {
      tomcat.stop();
      tomcat.destroy();
    } catch (LifecycleException e) {
      LOGGER.log(Level.WARNING, "Could not stop the web server cleanly", e);
    }
  }

  /**
   * What API endpoints get: this service's scoped provider, plus this service itself, which it
   * can't declare in {@link #getDependencies()} without creating a cycle.
   */
  private IServiceProvider endpointServiceProvider() {
    IServiceProvider scoped = serviceProvider;
    return new IServiceProvider() {
      @Override
      public SettingsService getSettingsService() {
        return scoped.getSettingsService();
      }

      @Override
      public SessionService getSessionService() {
        return scoped.getSessionService();
      }

      @Override
      public ClientGuardService getClientGuardService() {
        return scoped.getClientGuardService();
      }

      @Override
      public NoteStoreService getNoteStoreService() {
        return scoped.getNoteStoreService();
      }

      @Override
      public NoteService getNoteService() {
        return scoped.getNoteService();
      }

      @Override
      public WebServerService getWebServerService() {
        return WebServerService.this;
      }
    };
  }

  private static Connector newConnector(int port, String address) {
    Connector c = new Connector();
    c.setPort(port);
    if (address != null && !address.isBlank()) {
      c.setProperty("address", address);
    }
    c.setProperty("compression", "on");
    c.setProperty("compressionMinSize", String.valueOf(COMPRESSION_MIN_SIZE_BYTES));
    c.setProperty("compressibleMimeType", COMPRESSIBLE_MIME_TYPES);
    // DefaultServlet serves static files via NIO sendfile by default, which writes straight to
    // the socket and bypasses the output filters, compression included.
    c.setProperty("useSendfile", "false");
    return c;
  }

  private static void addMimeTypes(StandardContext ctx) {
    ctx.addMimeMapping("js", "application/javascript;charset=utf-8");
    ctx.addMimeMapping("html", "text/html;charset=utf-8");
    ctx.addMimeMapping("css", "text/css;charset=utf-8");
    ctx.addMimeMapping("svg", "image/svg+xml");
    ctx.addMimeMapping("png", "image/png");
    ctx.addMimeMapping("ico", "image/x-icon");
    ctx.addMimeMapping("webmanifest", "application/manifest+json");
  }
}
