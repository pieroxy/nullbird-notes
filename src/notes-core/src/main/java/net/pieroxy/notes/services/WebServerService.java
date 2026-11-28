package net.pieroxy.notes.services;

import net.pieroxy.notes.api.ApiServlet;
import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.webserver.AccessValve;
import net.pieroxy.notes.webserver.ExportServlet;
import net.pieroxy.notes.webserver.FontServlet;
import net.pieroxy.notes.webserver.ImportServlet;
import net.pieroxy.notes.webserver.PublishedPageServlet;
import net.pieroxy.notes.webserver.SecurityHeadersFilter;
import net.pieroxy.notes.webserver.WebappResourceExtractor;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.modeler.Registry;

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
      "text/html,text/css,application/javascript,image/svg+xml,application/json,font/ttf,font/otf";

  private IServiceProvider serviceProvider;
  private Tomcat tomcat;

  @Override
  public List<Class<? extends Service>> getDependencies() {
    return List.of(SettingsService.class, SessionService.class, ClientGuardService.class, NoteStoreService.class, NoteService.class,
        ImportService.class, FontService.class, SecondFactorService.class, BackupService.class);
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

      // Tomcat's JMX beans are unused: without them, several servers can share a JVM (as in tests).
      Registry.disableRegistry();
      tomcat = new Tomcat();
      tomcat.setBaseDir(new File(settings.getDataFolder(), "tomcat").getAbsolutePath());
      tomcat.setConnector(newConnector(config.getHttpPort(), config.getAddress()));
      tomcat.getEngine().getPipeline().addValve(new AccessValve(serviceProvider));
      // In place of the host's default one, which would show Tomcat's version and stack traces.
      ErrorReportValve errorReport = new ErrorReportValve();
      errorReport.setShowReport(false);
      errorReport.setShowServerInfo(false);
      tomcat.getHost().getPipeline().addValve(errorReport);

      StandardContext ctx = (StandardContext) tomcat.addContext("", webappDir.getAbsolutePath());
      ctx.addWelcomeFile("index.html");
      tomcat.addServlet("", "default", new DefaultServlet());
      ctx.addServletMappingDecoded("/", "default");
      // Async: a long poll waits for changes without holding a thread (see BaseApiEndpoint).
      tomcat.addServlet("", "api", new ApiServlet(endpointServiceProvider())).setAsyncSupported(true);
      ctx.addServletMappingDecoded("/api/*", "api");
      tomcat.addServlet("", "export", new ExportServlet(endpointServiceProvider()));
      ctx.addServletMappingDecoded(ExportServlet.PATH, "export");
      tomcat.addServlet("", "import", new ImportServlet(endpointServiceProvider()));
      ctx.addServletMappingDecoded(ImportServlet.PATH, "import");
      tomcat.addServlet("", "fonts", new FontServlet(endpointServiceProvider()));
      ctx.addServletMappingDecoded(FontServlet.PATH, "fonts");
      tomcat.addServlet("", "published", new PublishedPageServlet());
      ctx.addServletMappingDecoded(PublishedPageServlet.PATH, "published");
      addSecurityHeaders(ctx);
      addMimeTypes(ctx);

      tomcat.start();
      String address = config.getAddress() == null || config.getAddress().isBlank() ? "*" : config.getAddress();
      LOGGER.info("Web server listening on " + address + ":" + config.getHttpPort());
    } catch (LifecycleException | IOException e) {
      // Not started, so ServiceProvider won't destroy it: release what it holds now.
      destroy();
      Throwable cause = e;
      while (cause.getCause() != null) cause = cause.getCause();
      throw new IllegalStateException("Could not start the web server on port " + config.getHttpPort() + ": " + cause, e);
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
    } finally {
      tomcat = null;
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
      public ImportService getImportService() {
        return scoped.getImportService();
      }

      @Override
      public FontService getFontService() {
        return scoped.getFontService();
      }

      @Override
      public WebServerService getWebServerService() {
        return WebServerService.this;
      }

      @Override
      public SecondFactorService getSecondFactorService() {
        return scoped.getSecondFactorService();
      }

      @Override
      public BackupService getBackupService() {
        return scoped.getBackupService();
      }
    };
  }

  private static Connector newConnector(int port, String address) {
    Connector c = new Connector();
    c.setPort(port);
    // Otherwise a port already in use is only logged, and the server runs without listening.
    c.setThrowOnFailure(true);
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

  private static void addSecurityHeaders(StandardContext ctx) {
    FilterDef def = new FilterDef();
    def.setFilterName("securityHeaders");
    def.setFilter(new SecurityHeadersFilter());
    def.setAsyncSupported("true");
    ctx.addFilterDef(def);
    FilterMap map = new FilterMap();
    map.setFilterName("securityHeaders");
    map.addURLPatternDecoded("/*");
    ctx.addFilterMap(map);
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
