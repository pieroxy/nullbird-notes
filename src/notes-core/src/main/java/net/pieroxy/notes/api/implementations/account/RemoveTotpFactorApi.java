package net.pieroxy.notes.api.implementations.account;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.IServiceProvider;

import java.util.logging.Logger;

/** Removes the logged-in user's TOTP second factor, given their password; a setup under way goes too. */
@Endpoint(method = ApiMethod.POST)
public class RemoveTotpFactorApi extends AbstractAuthenticatedEndpoint<RemoveTotpFactorApiInput, AccountResult> {
  private final static Logger LOGGER = Logger.getLogger(RemoveTotpFactorApi.class.getName());

  public RemoveTotpFactorApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AccountResult processAuthenticated(RemoveTotpFactorApiInput input) throws Exception {
    String username = input.getUsername();
    if (!CurrentPassword.check(serviceProvider, input, input.getPassword(), "TOTP 2FA removal")) return AccountResult.refused("The password is wrong.");
    serviceProvider.getSecondFactorService().endTotpSetup(username);
    UserCredential user = serviceProvider.getSettingsService().getUser(username);
    if (user == null || user.totpFactor() == null) return AccountResult.done();
    serviceProvider.getSettingsService().removeTotpFactor(username);
    LOGGER.info("User \"" + username + "\" removed their TOTP 2FA from " + input.getClientAddress());
    SecondFactorNotice.totpChanged(serviceProvider, user, true);
    return AccountResult.done();
  }
}

@TypeScriptType
class RemoveTotpFactorApiInput extends AuthenticatedApiInput {
  @Required
  private String password;

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }
}
