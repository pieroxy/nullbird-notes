package net.pieroxy.notes.api.metadata;

import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SessionService;

/**
 * Base for every endpoint that requires a logged-in session. {@link #process} checks the session
 * cookie before the endpoint's own logic runs, via a {@code final} override, so there's no check to
 * forget. The endpoint then reads the caller's identity from {@link AuthenticatedApiInput}.
 */
public abstract class AbstractAuthenticatedEndpoint<I extends AuthenticatedApiInput, O> extends AbstractApiEndpoint<I, O> {
  protected final IServiceProvider serviceProvider;

  protected AbstractAuthenticatedEndpoint(IServiceProvider serviceProvider) {
    this.serviceProvider = serviceProvider;
  }

  @Override
  public final O process(I input, ApiContext context) throws Exception {
    String sessionId = context.getSessionId();
    SessionService.Session session = serviceProvider.getSessionService().get(sessionId);
    if (session == null) {
      throw new NotAuthenticatedException("Session expired.");
    }
    input.setSessionId(sessionId);
    input.setUsername(session.getUsername());
    return processAuthenticated(input);
  }

  public abstract O processAuthenticated(I input) throws Exception;
}
