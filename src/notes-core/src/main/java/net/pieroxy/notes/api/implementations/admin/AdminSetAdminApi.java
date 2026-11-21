package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;


/**
 * Makes another user an admin, or not. Not the caller: an admin can't demote themselves, so there's
 * always at least one admin left.
 */
@Endpoint(method = ApiMethod.POST)
public class AdminSetAdminApi extends AbstractUserChangeEndpoint<AdminSetAdminApiInput> {

  public AdminSetAdminApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  protected String describe(AdminSetAdminApiInput input) {
    return "making \"" + printable(input.getUser()) + "\" " + (input.isAdmin() ? "an admin" : "no longer an admin");
  }

  @Override
  protected AdminResult change(AdminSetAdminApiInput input) throws Exception {
    String user = input.getUser();
    if (user.equals(input.getUsername())) return AdminResult.refused("You can't change your own admin rights.");
    if (serviceProvider.getSettingsService().getUser(user) == null) return AdminResult.refused("There's no user \"" + user + "\".");

    serviceProvider.getSettingsService().setAdmin(user, input.isAdmin());
    log(input, "made \"" + user + "\" " + (input.isAdmin() ? "an admin" : "no longer an admin"));
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
