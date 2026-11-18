package net.pieroxy.notes.api.implementations.auth;

import net.pieroxy.notes.api.metadata.AbstractApiEndpoint;
import net.pieroxy.notes.api.metadata.ApiContext;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;

/** Not authenticated: logging out with an already expired session must still clear the cookie. */
@Endpoint(method = ApiMethod.POST)
public class LogoutApi extends AbstractApiEndpoint<LogoutApiInput, LogoutApiOutput> {
  private final IServiceProvider serviceProvider;

  public LogoutApi(IServiceProvider serviceProvider) {
    this.serviceProvider = serviceProvider;
  }

  @Override
  public LogoutApiOutput process(LogoutApiInput input, ApiContext context) {
    serviceProvider.getSessionService().invalidate(context.getSessionId());
    context.clearSessionCookie(serviceProvider.getSettingsService().isSessionCookieSecure());
    return new LogoutApiOutput();
  }
}

@TypeScriptType
class LogoutApiInput {
}

@TypeScriptType
class LogoutApiOutput {
}
