package net.pieroxy.notes.webserver;

import com.google.gson.Gson;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.pieroxy.notes.api.ApiServlet;
import net.pieroxy.notes.api.metadata.InvalidInputException;
import net.pieroxy.notes.api.metadata.ServletApiContext;
import net.pieroxy.notes.api.model.ApiResponse;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.FontService;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SessionService;
import net.pieroxy.notes.utils.BuildInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The installed fonts' files (see {@link FontService}), for logged-in users only: GET
 * /fonts/{file}. Named after their content, they never change: devices keep them, to work offline.
 * <p>
 * And an admin's upload of one: POST /fonts/ with the file as the body, as is, guarded as the API
 * is (session cookie, the web app's build header). Answers as the API does, with what the file is.
 */
public class FontServlet extends HttpServlet {
  private final static Logger LOGGER = Logger.getLogger(FontServlet.class.getName());
  public static final String PATH = "/fonts/*";
  private static final Gson GSON = new Gson();

  private final transient IServiceProvider serviceProvider;

  public FontServlet(IServiceProvider serviceProvider) {
    this.serviceProvider = serviceProvider;
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
    if (session(req) == null) {
      res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    String info = req.getPathInfo();
    Path file = serviceProvider.getFontService().file(info == null ? null : info.substring(1));
    if (file == null) {
      res.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    String name = file.getFileName().toString();
    res.setContentType("font/" + name.substring(name.lastIndexOf('.') + 1));
    res.setContentLengthLong(Files.size(file));
    Files.copy(file, res.getOutputStream());
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
    if (!"/".equals(req.getPathInfo())) {
      res.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    if (!BuildInfo.ID.equals(req.getHeader(ApiServlet.BUILD_HEADER))) {
      answer(res, HttpServletResponse.SC_CONFLICT, ApiResponse.buildErrResult("This page is out of date: reload it to get the new version."));
      return;
    }
    SessionService.Session session = session(req);
    if (session == null) {
      answer(res, HttpServletResponse.SC_UNAUTHORIZED, ApiResponse.buildErrResult("Session expired."));
      return;
    }
    UserCredential user = serviceProvider.getSettingsService().getUser(session.getUsername());
    if (user == null || !user.isAdmin()) {
      LOGGER.warning("\"" + session.getUsername() + "\" from " + req.getRemoteAddr() + " isn't an admin: font upload refused");
      answer(res, HttpServletResponse.SC_FORBIDDEN, ApiResponse.buildErrResult("Only admins can do that."));
      return;
    }
    if (req.getContentLengthLong() > FontService.MAX_FILE_BYTES) {
      answer(res, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, ApiResponse.buildErrResult(
          "The file is larger than " + FontService.MAX_FILE_BYTES / (1024 * 1024) + " MB, the most a font file may be."));
      return;
    }
    try {
      answer(res, HttpServletResponse.SC_OK, ApiResponse.buildOkResult(
          serviceProvider.getFontService().upload(session.getUsername(), req.getRemoteAddr(), req.getInputStream())));
    } catch (InvalidInputException e) {
      answer(res, HttpServletResponse.SC_BAD_REQUEST, ApiResponse.buildErrResult(e.getMessage()));
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "Font upload by \"" + session.getUsername() + "\" failed: " + e.getMessage());
      if (!res.isCommitted()) answer(res, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ApiResponse.buildErrResult("The upload failed: try again."));
    }
  }

  private SessionService.Session session(HttpServletRequest req) {
    return serviceProvider.getSessionService().use(ServletApiContext.sessionIdOf(req.getCookies()));
  }

  private static void answer(HttpServletResponse res, int status, ApiResponse<?> response) throws IOException {
    res.setStatus(status);
    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");
    res.setHeader("Cache-Control", "no-store");
    res.getWriter().write(GSON.toJson(response));
  }
}
