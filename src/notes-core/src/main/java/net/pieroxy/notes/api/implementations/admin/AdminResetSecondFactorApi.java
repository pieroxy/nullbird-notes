package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.implementations.account.SecondFactorNotice;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SecondFactorService;

/**
 * Turns off another user's second factors, all of them, and voids their setups under way: for a
 * lost phone. Their sessions stay. They're told by mail, where there's an address to.
 */
@Endpoint(method = ApiMethod.POST)
public class AdminResetSecondFactorApi extends AbstractUserChangeEndpoint<AdminResetSecondFactorApiInput> {

  public AdminResetSecondFactorApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  protected String describe(AdminResetSecondFactorApiInput input) {
    return "resetting the 2FA of \"" + printable(input.getUser()) + "\"";
  }

  @Override
  protected AdminResult change(AdminResetSecondFactorApiInput input) throws Exception {
    String username = input.getUser();
    if (username.equals(input.getUsername())) return AdminResult.refused("Change your own two-step verification in My Account.");
    UserCredential user = serviceProvider.getSettingsService().getUser(username);
    if (user == null) return AdminResult.refused("There's no user \"" + username + "\".");
    SecondFactorService factors = serviceProvider.getSecondFactorService();
    factors.discard(SecondFactorService.enrollKey(username));
    factors.endTotpSetup(username);
    if (user.getSecondFactor() == null) return AdminResult.done();
    serviceProvider.getSettingsService().removeSecondFactor(username);
    log(input, "reset the 2FA of \"" + username + "\"");
    SecondFactorNotice.resetByAdmin(serviceProvider, user);
    return AdminResult.done();
  }
}

@TypeScriptType
class AdminResetSecondFactorApiInput extends AuthenticatedApiInput {
  /** Whose: the caller is getUsername(). */
  @Required
  private String user;

  public String getUser() {
    return user;
  }

  public void setUser(String user) {
    this.user = user;
  }
}
