package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.PasswordGenerator;
import net.pieroxy.notes.config.PasswordHasher;
import net.pieroxy.notes.services.IServiceProvider;

import java.util.logging.Logger;

/**
 * Gives another user a password the server picks, returned this once, and logs them out everywhere.
 * Not the caller's own: that's ChangePasswordApi, which asks for the current one.
 */
@Endpoint(method = ApiMethod.POST)
public class AdminResetPasswordApi extends AbstractAdminEndpoint<AdminResetPasswordApiInput, AdminResult> {
  private final static Logger LOGGER = Logger.getLogger(AdminResetPasswordApi.class.getName());

  public AdminResetPasswordApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AdminResult processAdmin(AdminResetPasswordApiInput input) throws Exception {
    String user = input.getUser();
    if (user.equals(input.getUsername())) return AdminResult.refused("Change your own password in My Account.");
    if (serviceProvider.getSettingsService().getUser(user) == null) return AdminResult.refused("There's no user \"" + user + "\".");

    String password = PasswordGenerator.generate();
    serviceProvider.getSettingsService().setPassword(user, PasswordHasher.hash(password));
    serviceProvider.getSessionService().invalidateAll(user);
    LOGGER.info("\"" + input.getUsername() + "\" reset the password of \"" + user + "\": logged out everywhere");
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
