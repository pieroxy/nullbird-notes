package net.pieroxy.notes.webserver;

import com.google.gson.Gson;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.pieroxy.notes.api.ApiServlet;
import net.pieroxy.notes.api.metadata.InvalidInputException;
import net.pieroxy.notes.api.metadata.ServletApiContext;
import net.pieroxy.notes.api.metadata.TooManyRequestsException;
import net.pieroxy.notes.api.model.ApiResponse;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.ImportService;
import net.pieroxy.notes.services.SessionService;
import net.pieroxy.notes.utils.BuildInfo;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /import?name=&lt;file name&gt;: the file to import as the request body, as is. Checks it and
 * answers, as the API does, with what importing it would do (see {@link ImportService#check}); the
 * ImportRun and ImportCancel endpoints then finish it. Not an API endpoint: those take JSON, and
 * no more than a note's size.
 * <p>
 * Guarded as the API is: the session cookie (SameSite=Strict), and the web app's build header,
 * which another site can't send without the browser asking this server first.
 */
public class ImportServlet extends HttpServlet {
  private final static Logger LOGGER = Logger.getLogger(ImportServlet.class.getName());
  public static final String PATH = "/import";
  private static final Gson GSON = new Gson();

  private final transient IServiceProvider serviceProvider;

  public ImportServlet(IServiceProvider serviceProvider) {
    this.serviceProvider = serviceProvider;
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
    if (!BuildInfo.ID.equals(req.getHeader(ApiServlet.BUILD_HEADER))) {
      answer(res, HttpServletResponse.SC_CONFLICT, ApiResponse.buildErrResult("This page is out of date: reload it to get the new version."));
      return;
    }
    SessionService.Session session = serviceProvider.getSessionService().use(ServletApiContext.sessionIdOf(req.getCookies()));
    if (session == null) {
      answer(res, HttpServletResponse.SC_UNAUTHORIZED, ApiResponse.buildErrResult("Session expired."));
      return;
    }
    if (req.getContentLengthLong() > ImportService.MAX_UPLOAD_BYTES) {
      answer(res, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, ApiResponse.buildErrResult(
          "The file is larger than " + ImportService.MAX_UPLOAD_BYTES / (1024 * 1024) + " MB, the most one import takes."));
      return;
    }
    try {
      answer(res, HttpServletResponse.SC_OK, ApiResponse.buildOkResult(serviceProvider.getImportService()
          .check(session.getUsername(), req.getRemoteAddr(), req.getParameter("name"), req.getInputStream())));
    } catch (InvalidInputException e) {
      answer(res, HttpServletResponse.SC_BAD_REQUEST, ApiResponse.buildErrResult(e.getMessage()));
    } catch (TooManyRequestsException e) {
      res.setHeader("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
      answer(res, 429, ApiResponse.buildErrResult(e.getMessage()));
    } catch (IOException e) {
      // Usually the upload cut short.
      LOGGER.log(Level.WARNING, "Import upload for \"" + session.getUsername() + "\" failed: " + e.getMessage());
      if (!res.isCommitted()) answer(res, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ApiResponse.buildErrResult("The upload failed: try again."));
    }
  }

  private static void answer(HttpServletResponse res, int status, ApiResponse<?> response) throws IOException {
    res.setStatus(status);
    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");
    res.setHeader("Cache-Control", "no-store");
    res.getWriter().write(GSON.toJson(response));
  }
}
