package net.pieroxy.notes.api.implementations.account;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;

import java.util.logging.Logger;

/**
 * First step of setting up an authenticator app, or moving to another: a new secret, for the app.
 * ConfirmTotpFactor saves it once the app gives a code. The password is asked, as for mail.
 */
@Endpoint(method = ApiMethod.POST)
public class StartTotpFactorApi extends AbstractAuthenticatedEndpoint<StartTotpFactorApiInput, TotpSetup> {
  private final static Logger LOGGER = Logger.getLogger(StartTotpFactorApi.class.getName());

  public StartTotpFactorApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public TotpSetup processAuthenticated(StartTotpFactorApiInput input) {
    if (!CurrentPassword.check(serviceProvider, input, input.getPassword(), "TOTP 2FA setup")) return new TotpSetup(false, "The password is wrong.", null);
    String secret = serviceProvider.getSecondFactorService().startTotpSetup(input.getUsername());
    LOGGER.info("TOTP 2FA setup for \"" + input.getUsername() + "\" started from " + input.getClientAddress());
    return new TotpSetup(true, null, secret);
  }
}

/**
 * @param error  why it was refused, for the user: absent if ok.
 * @param secret base32, for the app: absent unless ok.
 */
@TypeScriptType
record TotpSetup(@Required boolean ok, String error, String secret) {
}

@TypeScriptType
class StartTotpFactorApiInput extends AuthenticatedApiInput {
  @Required
  private String password;

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }
}
