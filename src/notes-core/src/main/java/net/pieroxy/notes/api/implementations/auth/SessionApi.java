package net.pieroxy.notes.api.implementations.auth;

import net.pieroxy.notes.api.metadata.AbstractApiEndpoint;
import net.pieroxy.notes.api.metadata.ApiContext;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SessionService;

/**
 * Tells the webapp whether its session cookie (which it can't read) is still valid, so a reload
 * restores the logged-in state. Never requires authentication itself: "no" is a valid answer. A
 * stale cookie is cleared.
 */
@Endpoint(method = ApiMethod.GET)
public class SessionApi extends AbstractApiEndpoint<SessionApiInput, SessionApiOutput> {
  private final IServiceProvider serviceProvider;

  public SessionApi(IServiceProvider serviceProvider) {
    this.serviceProvider = serviceProvider;
  }

  @Override
  public SessionApiOutput process(SessionApiInput input, ApiContext context) {
    String sessionId = context.getSessionId();
    SessionService sessions = serviceProvider.getSessionService();
    SessionService.Session session = sessions.resume(sessionId);
    boolean secure = serviceProvider.getSettingsService().isSessionCookieSecure();
    if (session == null && sessionId != null) {
      context.clearSessionCookie(secure);
    } else if (session != null && sessions.cookieRefreshDue(session)) {
      context.setSessionCookie(sessionId, secure);
    }
    if (session == null) return new SessionApiOutput(false, null, false);
    // As credentials.json says now: it may have changed since the login.
    UserCredential user = serviceProvider.getSettingsService().getUser(session.getUsername());
    return new SessionApiOutput(true, session.getUsername(), user != null && user.isAdmin());
  }
}

@TypeScriptType
class SessionApiInput {
}

@TypeScriptType
class SessionApiOutput {
  @Required
  private boolean authenticated;
  /** Absent unless authenticated. */
  private String username;
  /** False unless authenticated. */
  @Required
  private boolean admin;

  public SessionApiOutput() {
  }

  public SessionApiOutput(boolean authenticated, String username, boolean admin) {
    this.authenticated = authenticated;
    this.username = username;
    this.admin = admin;
  }

  public boolean isAuthenticated() {
    return authenticated;
  }

  public void setAuthenticated(boolean authenticated) {
    this.authenticated = authenticated;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public boolean isAdmin() {
    return admin;
  }

  public void setAdmin(boolean admin) {
    this.admin = admin;
  }
}
