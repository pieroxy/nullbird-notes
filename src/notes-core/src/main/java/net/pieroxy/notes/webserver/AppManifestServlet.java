package net.pieroxy.notes.webserver;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * GET {@value #PATH}: the {@link AppManifest}, for the native apps. Anonymous, as the files it lists:
 * the login page is one of them. Made once, at startup: the files don't change until the next one.
 */
public class AppManifestServlet extends HttpServlet {
  public static final String PATH = "/app/manifest.json";
  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

  private final transient byte[] body;

  public AppManifestServlet(AppManifest manifest) {
    this.body = GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
    res.setContentType("application/json;charset=utf-8");
    res.setContentLength(body.length);
    res.getOutputStream().write(body);
  }
}
