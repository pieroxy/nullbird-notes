package net.pieroxy.notes.webserver;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import net.pieroxy.notes.api.metadata.ServletApiContext;
import net.pieroxy.notes.services.ClientGuardService;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SessionService;
import net.pieroxy.notes.utils.RateLimiter;
import net.pieroxy.notes.utils.logging.AccessLog;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * The web server's front door, on the engine's pipeline: sees every request, whether it's for the
 * API, a static file or a path that doesn't exist. As a Tomcat {@link org.apache.catalina.AccessLog},
 * Tomcat also calls {@link #log} once each response is fully sent (so the byte count includes
 * buffered output) and for requests rejected before the pipeline (e.g. malformed ones).
 */
public class AccessValve extends ValveBase implements org.apache.catalina.AccessLog {
  private final static Logger LOGGER = Logger.getLogger(AccessValve.class.getName());
  private final static int TOO_MANY_REQUESTS = 429;

  private final IServiceProvider serviceProvider;
  private final AtomicBoolean untrustedForwardingWarned = new AtomicBoolean();
  private boolean requestAttributesEnabled;

  public AccessValve(IServiceProvider serviceProvider) {
    super(true);
    this.serviceProvider = serviceProvider;
  }

  /**
   * Behind a trusted reverse proxy, swaps the proxy's address for the client's (see
   * {@link TrustedProxies}), for everything downstream. An untrusted address forwarding requests is
   * likely a proxy left out: counted (see {@link ClientGuardService#countRequest}), and with none
   * configured, warned about once. Refuses a banned client outright, and counts its 404s otherwise
   * (see {@link ClientGuardService#notFound}).
   */
  @Override
  public void invoke(Request request, Response response) throws IOException, ServletException {
    TrustedProxies trustedProxies = serviceProvider.getSettingsService().getTrustedProxies();
    ClientGuardService guard = serviceProvider.getClientGuardService();
    String peer = request.getRemoteAddr();
    Enumeration<String> headers = request.getHeaders("X-Forwarded-For");
    String forwardedFor = headers == null || !headers.hasMoreElements() ? null : String.join(",", Collections.list(headers));
    if (trustedProxies.isEmpty() || !trustedProxies.isTrusted(peer)) {
      guard.countRequest(peer, forwardedFor);
      if (trustedProxies.isEmpty() && forwardedFor != null && !untrustedForwardingWarned.getAndSet(true)) {
        LOGGER.warning("A request from " + peer + " carries X-Forwarded-For (" + lastHop(forwardedFor) + "), but trustedProxies is empty. If " + peer
            + " is a reverse proxy, every client looks like it: one client's failed logins or 404s lock everyone out."
            + " Set trustedProxies to its address in config.json. Logged once.");
      }
    } else {
      String client = trustedProxies.clientAddress(peer, forwardedFor);
      if (!client.equals(peer)) {
        request.setRemoteAddr(client);
        request.setRemoteHost(client);
      }
    }

    String client = request.getRemoteAddr();
    try {
      guard.checkBanned(client);
    } catch (RateLimiter.RateExceededException e) {
      response.setStatus(TOO_MANY_REQUESTS);
      response.setHeader("Retry-After", String.valueOf(Math.max(1, (e.getRetryAfterMillis() + 999) / 1000)));
      response.setContentType("text/plain;charset=UTF-8");
      response.getWriter().write("Too many requests.");
      return;
    }

    getNext().invoke(request, response);

    // A trusted proxy as the client means its forwarding header was missing: banning it would ban
    // everyone behind it.
    if (response.getStatus() == HttpServletResponse.SC_NOT_FOUND && sessionUser(request) == null
        && !trustedProxies.isTrusted(client) && guard.notFound(client, request.getRequestURI())) {
      LOGGER.warning("Banned " + client + " for " + ClientGuardService.BAN_DURATION.toMinutes() + " min: "
          + ClientGuardService.MAX_NOT_FOUND + " requests answered 404 within "
          + ClientGuardService.NOT_FOUND_WINDOW.toSeconds() + " s, last one " + request.getRequestURI());
    }
  }

  /** The header's rightmost address, as a log can show it: only the characters of an IP address, at most 45. */
  private static String lastHop(String forwardedFor) {
    String hop = forwardedFor.substring(forwardedFor.lastIndexOf(',') + 1).trim().replaceAll("[^0-9A-Fa-f:.\\[\\]]", "");
    return hop.substring(0, Math.min(hop.length(), 45));
  }

  /** {@code time} is ignored: its unit changed across Tomcat versions, so the duration comes from the request's own start. */
  @Override
  public void log(Request request, Response response, long time) {
    org.apache.coyote.Request coyote = request.getCoyoteRequest();
    ZonedDateTime start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(coyote.getStartTime()), ZoneId.systemDefault());
    long durationMs = Math.max(0, (System.nanoTime() - coyote.getStartTimeNanos()) / 1_000_000);
    AccessLog.record(start, AccessLog.format(request.getRemoteAddr(), sessionUser(request), start, request.getMethod(),
        request.getRequestURI(), request.getQueryString(), request.getProtocol(), response.getStatus(),
        response.getBytesWritten(false), request.getHeader("Referer"), request.getHeader("User-Agent"), durationMs));
  }

  @Override
  public void setRequestAttributesEnabled(boolean requestAttributesEnabled) {
    this.requestAttributesEnabled = requestAttributesEnabled;
  }

  @Override
  public boolean getRequestAttributesEnabled() {
    return requestAttributesEnabled;
  }

  /** The username when the request carries a valid session, null otherwise. */
  private String sessionUser(Request request) {
    SessionService.Session session = serviceProvider.getSessionService().get(ServletApiContext.sessionIdOf(request.getCookies()));
    return session != null ? session.getUsername() : null;
  }
}
