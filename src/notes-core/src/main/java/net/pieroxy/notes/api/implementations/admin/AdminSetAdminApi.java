package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;

import java.util.logging.Logger;

/**
 * Makes another user an admin, or not. Not the caller: an admin can't demote themselves, so there's
 * always at least one admin left.
 */
@Endpoint(method = ApiMethod.POST)
public class AdminSetAdminApi extends AbstractAdminEndpoint<AdminSetAdminApiInput, AdminResult> {
  private final static Logger LOGGER = Logger.getLogger(AdminSetAdminApi.class.getName());

  public AdminSetAdminApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AdminResult processAdmin(AdminSetAdminApiInput input) throws Exception {
    String user = input.getUser();
    if (user.equals(input.getUsername())) return AdminResult.refused("You can't change your own admin rights.");
    if (serviceProvider.getSettingsService().getUser(user) == null) return AdminResult.refused("There's no user \"" + user + "\".");

    serviceProvider.getSettingsService().setAdmin(user, input.isAdmin());
    LOGGER.info("\"" + input.getUsername() + "\" made \"" + user + "\" " + (input.isAdmin() ? "an admin" : "no longer an admin"));
    return AdminResult.done();
  }
}

@TypeScriptType
class AdminSetAdminApiInput extends AuthenticatedApiInput {
  /** Whose flag: the caller is getUsername(). */
  @Required
  private String user;
  @Required
  private boolean admin;

  public String getUser() {
    return user;
  }

  public void setUser(String user) {
    this.user = user;
  }

  public boolean isAdmin() {
    return admin;
  }

  public void setAdmin(boolean admin) {
    this.admin = admin;
  }
}
