package net.pieroxy.notes.webserver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import net.pieroxy.notes.services.FontService;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Security and caching headers, on every response. The page loads nothing but its own script,
 * stylesheet, icons, manifest, service worker and installed fonts, and calls nothing but /api/: the CSP allows exactly that. HSTS is left to
 * the reverse proxy, the only one to know whether the site is served over HTTPS.
 */
public class SecurityHeadersFilter extends HttpFilter {
  static final String CSP = String.join("; ",
      "default-src 'none'",
      "script-src 'self'",
      "style-src 'self'",
      "img-src 'self'",
      // The fonts the admin installed (/fonts/), loaded by the page's script (see Fonts.ts).
      "font-src 'self'",
      "connect-src 'self'",
      // The web app manifest, which makes the app installable.
      "manifest-src 'self'",
      // The service worker (/sw.js), which keeps the app on the device to open offline.
      "worker-src 'self'",
      "base-uri 'none'",
      "form-action 'none'",
      "frame-ancestors 'none'",
      "require-trusted-types-for 'script'",
      // Only the policy that registers the service worker, and only for /sw.js (see ServiceWorker.ts).
      "trusted-types notes-sw");
  /** Named after their content's hash by the webapp's build: a new content is a new name. */
  private static final Pattern IMMUTABLE = Pattern.compile("/bundle-[0-9a-f]+\\.(js|css)(\\.map)?");
  private static final Pattern FONT = Pattern.compile("/fonts/" + FontService.FILE_PATTERN.pattern());

  @Override
  protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
    res.setHeader("Content-Security-Policy", CSP);
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Referrer-Policy", "no-referrer");
    res.setHeader("X-Frame-Options", "DENY");
    res.setHeader("Cross-Origin-Opener-Policy", "same-origin");
    res.setHeader("Cross-Origin-Resource-Policy", "same-origin");
    res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
    res.setHeader("Cache-Control", cacheControl(req.getRequestURI()));
    // A published note's link, if it leaks, isn't to be found through a search engine.
    if (req.getRequestURI().startsWith("/p/")) res.setHeader("X-Robots-Tag", "noindex, nofollow");
    chain.doFilter(req, res);
  }

  /**
   * The user's notes are never kept by a cache, published ones included (unpublished, they're gone);
   * anything that isn't immutable is revalidated.
   */
  static String cacheControl(String path) {
    if (path.startsWith("/api/") || path.startsWith("/p/")) return "no-store";
    if (IMMUTABLE.matcher(path).matches()) return "public, max-age=31536000, immutable";
    // Named after their content too; for logged-in users only.
    if (FONT.matcher(path).matches()) return "private, max-age=31536000, immutable";
    return "no-cache";
  }
}
