package net.pieroxy.notes.api.implementations.account;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.SecondFactor;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.IServiceProvider;

import java.util.logging.Logger;

/** Which of the user's second factors login asks first. No password: either still has to be passed. */
@Endpoint(method = ApiMethod.POST)
public class SetPreferredFactorApi extends AbstractAuthenticatedEndpoint<SetPreferredFactorApiInput, AccountResult> {
  private final static Logger LOGGER = Logger.getLogger(SetPreferredFactorApi.class.getName());

  public SetPreferredFactorApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AccountResult processAuthenticated(SetPreferredFactorApiInput input) throws Exception {
    String method = input.getMethod();
    UserCredential user = serviceProvider.getSettingsService().getUser(input.getUsername());
    boolean has = user != null && (SecondFactor.MAIL.equals(method) ? user.mailFactor() != null : SecondFactor.TOTP.equals(method) && user.totpFactor() != null);
    if (!has) return AccountResult.refused("You don't have this method set up.");
    serviceProvider.getSettingsService().setPreferredFactor(input.getUsername(), method);
    LOGGER.info("User \"" + input.getUsername() + "\" prefers " + method + " 2FA, from " + input.getClientAddress());
    return AccountResult.done();
  }
}

@TypeScriptType
class SetPreferredFactorApiInput extends AuthenticatedApiInput {
  /** "mail" or "totp". */
  @Required
  private String method;

  public String getMethod() {
    return method;
  }

  public void setMethod(String method) {
    this.method = method;
  }
}
