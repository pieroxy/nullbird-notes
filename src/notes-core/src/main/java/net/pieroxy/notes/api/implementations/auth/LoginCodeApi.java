package net.pieroxy.notes.api.implementations.auth;

import net.pieroxy.notes.api.metadata.AbstractApiEndpoint;
import net.pieroxy.notes.api.metadata.ApiContext;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.SecondFactor;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.ClientGuardService;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SecondFactorService;
import net.pieroxy.notes.utils.RateLimiter;

import java.util.logging.Logger;

/**
 * Finishes a login Login answered with a challenge, given the code it asks: mailed, or from the
 * authenticator app. A wrong code counts as a failed login (see {@link ClientGuardService}). Without
 * a valid challenge, the answer has none: the login starts over, password first.
 */
@Endpoint(method = ApiMethod.POST)
public class LoginCodeApi extends AbstractApiEndpoint<LoginCodeApiInput, LoginApiOutput> {
  private final static Logger LOGGER = Logger.getLogger(LoginCodeApi.class.getName());

  private final IServiceProvider serviceProvider;

  public LoginCodeApi(IServiceProvider serviceProvider) {
    this.serviceProvider = serviceProvider;
  }

  @Override
  public LoginApiOutput process(LoginCodeApiInput input, ApiContext context) throws Exception {
    String client = context.getRemoteAddr();
    SecondFactorService factors = serviceProvider.getSecondFactorService();
    String challenge = input.getChallenge();
    String username = factors.challengeUser(challenge);
    String method = factors.challengeMethod(challenge);
    UserCredential user = serviceProvider.getSettingsService().getUser(username);
    if (username == null || user == null) {
      LOGGER.info("Login code from " + client + ": no such challenge, or expired");
      return LoginApiOutput.refused("This login expired: log in again.");
    }
    if (SecondFactor.TOTP.equals(method)) return totp(user, challenge, input.getCode(), context);

    SecondFactorService.Check check;
    try (ClientGuardService.PasswordCheck guard = LoginApi.startPasswordCheck(serviceProvider.getClientGuardService(), client, "Login code")) {
      check = factors.check(SecondFactorService.challengeKey(challenge), input.getCode());
      if (check.outcome() == SecondFactorService.Check.Outcome.RIGHT) guard.succeeded();
    }
    switch (check.outcome()) {
      case WRONG -> {
        LOGGER.warning("Login for \"" + username + "\" from " + client + ": wrong code");
        return SecondFactorLogin.challengeOutput(serviceProvider, user, challenge, method, "This code is wrong.");
      }
      case NONE -> {
        factors.endChallenge(challenge);
        LOGGER.warning("Login for \"" + username + "\" from " + client + ": no valid code anymore");
        return LoginApiOutput.refused("This code expired, or was mistyped too many times: log in again.");
      }
      default -> {
        factors.endChallenge(challenge);
        return LoginApi.openSession(serviceProvider, user, context, " with a code by mail");
      }
    }
  }

  /** A wrong code keeps the challenge: the next one from the app may be typed, within the per-user limit. */
  private LoginApiOutput totp(UserCredential user, String challenge, String code, ApiContext context) throws Exception {
    String username = user.getUsername();
    String client = context.getRemoteAddr();
    SecondFactor.TotpFactor totp = user.totpFactor();
    if (totp == null) {
      serviceProvider.getSecondFactorService().endChallenge(challenge);
      return LoginApiOutput.refused("This login expired: log in again.");
    }
    boolean used;
    try (ClientGuardService.PasswordCheck guard = LoginApi.startPasswordCheck(serviceProvider.getClientGuardService(), client, "Login code")) {
      Long step = serviceProvider.getSecondFactorService().verifyTotp(username, totp.getSecret(), code, totp.getLastUsedStep());
      // Recorded at once: the same code, at the same time, from elsewhere, is refused.
      used = step != null && serviceProvider.getSettingsService().useTotpStep(username, step);
      if (used) guard.succeeded();
    } catch (RateLimiter.RateExceededException e) {
      LOGGER.warning("Login for \"" + username + "\" from " + client + " refused: too many wrong TOTP codes");
      return SecondFactorLogin.challengeOutput(serviceProvider, user, challenge, SecondFactor.TOTP,
          "Too many wrong codes: retry in " + Math.max(1, (e.getRetryAfterMillis() + 59_999) / 60_000) + " min.");
    }
    if (!used) {
      LOGGER.warning("Login for \"" + username + "\" from " + client + ": wrong TOTP code");
      return SecondFactorLogin.challengeOutput(serviceProvider, user, challenge, SecondFactor.TOTP, "This code is wrong.");
    }
    serviceProvider.getSecondFactorService().endChallenge(challenge);
    return LoginApi.openSession(serviceProvider, user, context, " with a code from their authenticator app");
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
