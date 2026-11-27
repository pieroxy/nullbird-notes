package net.pieroxy.notes.api.implementations.account;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SecondFactorService;

import java.time.Instant;
import java.util.logging.Logger;

/** Second step of SendMailFactorCode: the code mailed proves the address, which becomes the user's mail factor. */
@Endpoint(method = ApiMethod.POST)
public class ConfirmMailFactorApi extends AbstractAuthenticatedEndpoint<ConfirmMailFactorApiInput, AccountResult> {
  private final static Logger LOGGER = Logger.getLogger(ConfirmMailFactorApi.class.getName());

  public ConfirmMailFactorApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AccountResult processAuthenticated(ConfirmMailFactorApiInput input) throws Exception {
    String username = input.getUsername();
    SecondFactorService.Check check = serviceProvider.getSecondFactorService().check(MailFactorKeys.enroll(username), input.getCode());
    switch (check.outcome()) {
      case WRONG -> {
        LOGGER.info("Mail 2FA setup for \"" + username + "\" from " + input.getClientAddress() + ": wrong code");
        return AccountResult.refused("This code is wrong.");
      }
      case NONE -> {
        LOGGER.info("Mail 2FA setup for \"" + username + "\" from " + input.getClientAddress() + ": no valid code");
        return AccountResult.refused("No valid code: it expired, or was mistyped too many times. Send a new one.");
      }
      default -> {
        var user = serviceProvider.getSettingsService().getUser(username);
        String previous = user == null || user.mailFactor() == null ? null : user.mailFactor().getAddress();
        serviceProvider.getSettingsService().setMailFactor(username, check.address(), Instant.now());
        LOGGER.info("User \"" + username + "\" set up mail 2FA to " + check.address() + " from " + input.getClientAddress());
        if (previous != null && !previous.equalsIgnoreCase(check.address())) MailFactorNotice.send(serviceProvider, username, previous, check.address());
        return AccountResult.done();
      }
    }
  }
}

@TypeScriptType
class ConfirmMailFactorApiInput extends AuthenticatedApiInput {
  @Required
  private String code;

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }
}
