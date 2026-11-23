package net.pieroxy.notes.webserver;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** /p/{token}: the published note page, whatever the token; the page asks /api/PublishedNote for the note. */
public class PublishedPageServlet extends HttpServlet {
  public static final String PATH = "/p/*";

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
    req.getRequestDispatcher("/published.html").forward(req, res);
  }
}
