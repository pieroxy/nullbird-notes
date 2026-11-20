package net.pieroxy.notes.webserver;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.pieroxy.notes.api.metadata.ServletApiContext;
import net.pieroxy.notes.export.SimplenoteExport;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SessionService;

import java.io.IOException;
import java.time.LocalDate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GET /export: the logged-in user's notes as a zip download (see {@link SimplenoteExport}). Not an
 * API endpoint: those answer JSON. The session cookie is SameSite=Strict, so another site can't
 * have a browser download it.
 */
public class ExportServlet extends HttpServlet {
  private final static Logger LOGGER = Logger.getLogger(ExportServlet.class.getName());
  public static final String PATH = "/export";

  private final transient IServiceProvider serviceProvider;

  public ExportServlet(IServiceProvider serviceProvider) {
    this.serviceProvider = serviceProvider;
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
    SessionService.Session session = serviceProvider.getSessionService().use(ServletApiContext.sessionIdOf(req.getCookies()));
    if (session == null) {
      res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Log in to export your notes.");
      return;
    }
    String username = session.getUsername();
    res.setContentType("application/zip");
    res.setHeader("Content-Disposition", "attachment; filename=\"notes-" + username + "-" + LocalDate.now() + ".zip\"");
    res.setHeader("Cache-Control", "no-store");
    try {
      SimplenoteExport.write(serviceProvider.getNoteStoreService().getStore(username), res.getOutputStream());
      LOGGER.info("User \"" + username + "\" exported their notes from " + req.getRemoteAddr());
    } catch (IOException e) {
      // Usually the download cancelled. Once the zip has started, there's no error to send: it ends cut short.
      LOGGER.log(Level.WARNING, "Export for \"" + username + "\" failed: " + e.getMessage());
      if (!res.isCommitted()) res.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
  }
}
