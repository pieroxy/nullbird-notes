package net.pieroxy.notes.api.implementations.account;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SecondFactorService;

import java.util.logging.Logger;

/** Removes the logged-in user's mail second factor, given their password; a code pending to set one up goes too. */
@Endpoint(method = ApiMethod.POST)
public class RemoveMailFactorApi extends AbstractAuthenticatedEndpoint<RemoveMailFactorApiInput, AccountResult> {
  private final static Logger LOGGER = Logger.getLogger(RemoveMailFactorApi.class.getName());

  public RemoveMailFactorApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AccountResult processAuthenticated(RemoveMailFactorApiInput input) throws Exception {
    String username = input.getUsername();
    if (!CurrentPassword.check(serviceProvider, input, input.getPassword(), "Mail 2FA removal")) return AccountResult.refused("The password is wrong.");
    serviceProvider.getSecondFactorService().discard(SecondFactorService.enrollKey(username));
    var user = serviceProvider.getSettingsService().getUser(username);
    if (user == null || user.mailFactor() == null) return AccountResult.done();
    serviceProvider.getSettingsService().removeMailFactor(username);
    LOGGER.info("User \"" + username + "\" removed their mail 2FA from " + input.getClientAddress());
    SecondFactorNotice.mailChanged(serviceProvider, username, user.mailFactor().getAddress(), null);
    return AccountResult.done();
  }
}

@TypeScriptType
class RemoveMailFactorApiInput extends AuthenticatedApiInput {
  @Required
  private String password;

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }
}
