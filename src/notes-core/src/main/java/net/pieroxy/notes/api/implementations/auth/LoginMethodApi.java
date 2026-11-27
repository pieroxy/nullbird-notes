package net.pieroxy.notes.api.implementations.auth;

import net.pieroxy.notes.api.metadata.AbstractApiEndpoint;
import net.pieroxy.notes.api.metadata.ApiContext;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.SecondFactor;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SecondFactorService;

import java.util.logging.Logger;

/**
 * Which code a login's challenge asks: by mail mails a new one (also to send another), "totp" asks
 * the app's. The challenge stays the same, its expiry too. Mail not sent, it stays as it was, with why.
 */
@Endpoint(method = ApiMethod.POST)
public class LoginMethodApi extends AbstractApiEndpoint<LoginMethodApiInput, LoginApiOutput> {
  private final static Logger LOGGER = Logger.getLogger(LoginMethodApi.class.getName());

  private final IServiceProvider serviceProvider;

  public LoginMethodApi(IServiceProvider serviceProvider) {
    this.serviceProvider = serviceProvider;
  }

  @Override
  public LoginApiOutput process(LoginMethodApiInput input, ApiContext context) throws Exception {
    SecondFactorService factors = serviceProvider.getSecondFactorService();
    String challenge = input.getChallenge();
    String current = factors.challengeMethod(challenge);
    UserCredential user = serviceProvider.getSettingsService().getUser(factors.challengeUser(challenge));
    if (current == null || user == null) return LoginApiOutput.refused("This login expired: log in again.");
    String client = context.getRemoteAddr();
    if (SecondFactor.TOTP.equals(input.getMethod())) {
      if (user.totpFactor() == null) return SecondFactorLogin.challengeOutput(serviceProvider, user, challenge, current, "You have no authenticator app set up.");
      factors.switchChallenge(challenge, SecondFactor.TOTP);
      LOGGER.info("Login for \"" + user.getUsername() + "\" from " + client + ": TOTP code asked");
      return SecondFactorLogin.challengeOutput(serviceProvider, user, challenge, SecondFactor.TOTP, null);
    }
    if (user.mailFactor() == null || factors.mailOut() == null) {
      return SecondFactorLogin.challengeOutput(serviceProvider, user, challenge, current, "Codes can't be mailed to you.");
    }
    // Switched first: switching voids the challenge's mailed code, this one included if it came before.
    factors.switchChallenge(challenge, SecondFactor.MAIL);
    String error = SecondFactorLogin.mailCode(serviceProvider, user.getUsername(), user.mailFactor().getAddress(), challenge, client);
    if (error != null) {
      factors.switchChallenge(challenge, current);
      return SecondFactorLogin.challengeOutput(serviceProvider, user, challenge, current, error);
    }
    return SecondFactorLogin.challengeOutput(serviceProvider, user, challenge, SecondFactor.MAIL, null);
  }
}

@TypeScriptType
class LoginMethodApiInput {
  @Required
  private String challenge;
  /** "mail" or "totp". */
  @Required
  private String method;

  public String getChallenge() {
    return challenge;
  }

  public void setChallenge(String challenge) {
    this.challenge = challenge;
  }

  public String getMethod() {
    return method;
  }

  public void setMethod(String method) {
    this.method = method;
  }
}
