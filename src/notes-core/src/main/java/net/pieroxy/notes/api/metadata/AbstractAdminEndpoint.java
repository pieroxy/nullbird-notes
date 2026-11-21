package net.pieroxy.notes.api.metadata;

import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.IServiceProvider;

import java.util.logging.Logger;

/**
 * Base for every endpoint only admins may call. {@link #processAuthenticated} checks the caller's
 * admin flag in credentials.json as it is now, via a {@code final} override, so there's no check to
 * forget, and a flag removed takes effect at once.
 */
public abstract class AbstractAdminEndpoint<I extends AuthenticatedApiInput, O> extends AbstractAuthenticatedEndpoint<I, O> {
  private final static Logger LOGGER = Logger.getLogger(AbstractAdminEndpoint.class.getName());

  protected AbstractAdminEndpoint(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public final O processAuthenticated(I input) throws Exception {
    UserCredential caller = serviceProvider.getSettingsService().getUser(input.getUsername());
    if (caller == null || !caller.isAdmin()) {
      LOGGER.warning("\"" + input.getUsername() + "\" from " + input.getClientAddress() + " isn't an admin: " + getClass().getSimpleName() + " refused");
      throw new ForbiddenException("Only admins can do that.");
    }
    return processAdmin(input);
  }

  /** {@code input}'s username is an admin's. */
  public abstract O processAdmin(I input) throws Exception;
}
