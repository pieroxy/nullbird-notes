package net.pieroxy.notes.api.implementations.account;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.mail.Mail;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SecondFactorService;
import net.pieroxy.notes.utils.RateLimiter;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * First step of setting up mail as a second factor, or changing its address: mails a code to the
 * address, which ConfirmMailFactor takes. The password is asked: a session left open mustn't be
 * enough to tie the account to another mailbox.
 */
@Endpoint(method = ApiMethod.POST)
public class SendMailFactorCodeApi extends AbstractAuthenticatedEndpoint<SendMailFactorCodeApiInput, AccountResult> {
  private final static Logger LOGGER = Logger.getLogger(SendMailFactorCodeApi.class.getName());

  public SendMailFactorCodeApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AccountResult processAuthenticated(SendMailFactorCodeApiInput input) throws Exception {
    String username = input.getUsername();
    String address = input.getAddress() == null ? "" : input.getAddress().trim();
    if (address.length() > 254 || !Mail.ADDRESS.matcher(address).matches()) return AccountResult.refused("This isn't an email address.");
    if (!CurrentPassword.check(serviceProvider, input, input.getPassword(), "Mail 2FA setup")) return AccountResult.refused("The password is wrong.");
    try {
      serviceProvider.getSecondFactorService().sendCode(SecondFactorService.enrollKey(username), username, code -> new Mail(address,
          "Notes code: " + code,
          "Your code to confirm this address for two-step verification in Notes:\n\n" + code + "\n\n"
              + "It's valid for " + SecondFactorService.CODE_LIFETIME.toMinutes() + " minutes.\n\n"
              + "If you didn't ask for it, someone knows your Notes password: change it.\n"));
    } catch (RateLimiter.RateExceededException e) {
      long minutes = Math.max(1, (e.getRetryAfterMillis() + 59_999) / 60_000);
      LOGGER.info("Mail 2FA code for \"" + username + "\" from " + input.getClientAddress() + " refused: too many sent");
      return AccountResult.refused("Too many codes sent: retry in " + minutes + " min.");
    } catch (IOException e) {
      String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      LOGGER.warning("Mail 2FA code for \"" + username + "\" to " + address + " from " + input.getClientAddress() + " not sent: " + error);
      return AccountResult.refused("The code couldn't be sent: " + error);
    }
    LOGGER.info("Mail 2FA code for \"" + username + "\" sent to " + address + " from " + input.getClientAddress());
    return AccountResult.done();
  }
}

@TypeScriptType
class SendMailFactorCodeApiInput extends AuthenticatedApiInput {
  @Required
  private String address;
  @Required
  private String password;

  public String getAddress() {
    return address;
  }

  public void setAddress(String address) {
    this.address = address;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }
}
