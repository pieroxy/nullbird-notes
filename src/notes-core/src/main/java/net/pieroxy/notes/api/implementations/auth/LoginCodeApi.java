package net.pieroxy.notes.api.implementations.auth;

import net.pieroxy.notes.api.metadata.AbstractApiEndpoint;
import net.pieroxy.notes.api.metadata.ApiContext;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.ClientGuardService;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SecondFactorService;

import java.util.logging.Logger;

/**
 * Finishes a login Login answered with a challenge, given the code mailed. A wrong code counts as a
 * failed login (see {@link ClientGuardService}). Without a valid challenge, the answer has none:
 * the login starts over, password first.
 */
@Endpoint(method = ApiMethod.POST)
public class LoginCodeApi extends AbstractApiEndpoint<LoginCodeApiInput, LoginApiOutput> {
  private final static Logger LOGGER = Logger.getLogger(LoginCodeApi.class.getName());

  private final IServiceProvider serviceProvider;

  public LoginCodeApi(IServiceProvider serviceProvider) {
    this.serviceProvider = serviceProvider;
  }

  @Override
  public LoginApiOutput process(LoginCodeApiInput input, ApiContext context) {
    String client = context.getRemoteAddr();
    SecondFactorService factors = serviceProvider.getSecondFactorService();
    String challenge = input.getChallenge();
    String username = factors.challengeUser(challenge);
    if (username == null) {
      LOGGER.info("Login code from " + client + ": no such challenge, or expired");
      return LoginApiOutput.refused("This login expired: log in again.");
    }
    SecondFactorService.Check check;
    try (ClientGuardService.PasswordCheck guard = LoginApi.startPasswordCheck(serviceProvider.getClientGuardService(), client, "Login code")) {
      check = factors.check(SecondFactorService.challengeKey(challenge), input.getCode());
      if (check.outcome() == SecondFactorService.Check.Outcome.RIGHT) guard.succeeded();
    }
    switch (check.outcome()) {
      case WRONG -> {
        LOGGER.warning("Login for \"" + username + "\" from " + client + ": wrong code");
        return new LoginApiOutput(false, null, false, challenge, null, "This code is wrong.");
      }
      case NONE -> {
        factors.endChallenge(challenge);
        LOGGER.warning("Login for \"" + username + "\" from " + client + ": no valid code anymore");
        return LoginApiOutput.refused("This code expired, or was mistyped too many times: log in again.");
      }
      default -> {
        factors.endChallenge(challenge);
        UserCredential user = serviceProvider.getSettingsService().getUser(username);
        if (user == null) return LoginApiOutput.refused(null);
        return LoginApi.openSession(serviceProvider, user, context, " with a code by mail");
      }
    }
  }
}

@TypeScriptType
class LoginCodeApiInput {
  @Required
  private String challenge;
  @Required
  private String code;

  public String getChallenge() {
    return challenge;
  }

  public void setChallenge(String challenge) {
    this.challenge = challenge;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }
}
