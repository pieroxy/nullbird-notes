package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.PasswordGenerator;
import net.pieroxy.notes.config.PasswordHasher;
import net.pieroxy.notes.services.IServiceProvider;


/**
 * Gives another user a password the server picks, returned this once, and logs them out everywhere.
 * Not the caller's own: that's ChangePasswordApi, which asks for the current one.
 */
@Endpoint(method = ApiMethod.POST)
public class AdminResetPasswordApi extends AbstractUserChangeEndpoint<AdminResetPasswordApiInput> {

  public AdminResetPasswordApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  protected String describe(AdminResetPasswordApiInput input) {
    return "resetting the password of \"" + printable(input.getUser()) + "\"";
  }

  @Override
  protected AdminResult change(AdminResetPasswordApiInput input) throws Exception {
    String user = input.getUser();
    if (user.equals(input.getUsername())) return AdminResult.refused("Change your own password in My Account.");
    if (serviceProvider.getSettingsService().getUser(user) == null) return AdminResult.refused("There's no user \"" + user + "\".");

    String password = PasswordGenerator.generate();
    serviceProvider.getSettingsService().setPassword(user, PasswordHasher.hash(password));
    serviceProvider.getSessionService().invalidateAll(user);
    log(input, "reset the password of \"" + user + "\": logged out everywhere");
    return AdminResult.withPassword(password);
  }
}

@TypeScriptType
class AdminResetPasswordApiInput extends AuthenticatedApiInput {
  /** Whose password: the caller is getUsername(). */
  @Required
  private String user;

  public String getUser() {
    return user;
  }

  public void setUser(String user) {
    this.user = user;
  }
}
