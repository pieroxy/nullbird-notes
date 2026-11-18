package net.pieroxy.notes.api.implementations.auth;

import net.pieroxy.notes.api.metadata.AbstractApiEndpoint;
import net.pieroxy.notes.api.metadata.ApiContext;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.TypeScriptType;
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
    SessionService.Session session = serviceProvider.getSessionService().get(sessionId);
    if (session == null && sessionId != null) {
      context.clearSessionCookie(serviceProvider.getSettingsService().isSessionCookieSecure());
    }
    return new SessionApiOutput(session != null, session != null ? session.getUsername() : null);
  }
}

@TypeScriptType
class SessionApiInput {
}

@TypeScriptType
class SessionApiOutput {
  private boolean authenticated;
  /** Null unless authenticated. */
  private String username;

  public SessionApiOutput() {
  }

  public SessionApiOutput(boolean authenticated, String username) {
    this.authenticated = authenticated;
    this.username = username;
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
}
