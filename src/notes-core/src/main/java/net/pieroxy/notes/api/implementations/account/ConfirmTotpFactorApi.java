package net.pieroxy.notes.api.implementations.account;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SecondFactorService;
import net.pieroxy.notes.totp.Totp;
import net.pieroxy.notes.utils.RateLimiter;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Second step of StartTotpFactor: a code from the app proves it set up, and the secret becomes the
 * user's TOTP factor, replacing any previous one. A wrong code made by a clock off by up to
 * {@link #CLOCK_CHECK_STEPS} steps says so: the usual reason a setup fails.
 */
@Endpoint(method = ApiMethod.POST)
public class ConfirmTotpFactorApi extends AbstractAuthenticatedEndpoint<ConfirmTotpFactorApiInput, AccountResult> {
  private final static Logger LOGGER = Logger.getLogger(ConfirmTotpFactorApi.class.getName());
  /** 10 minutes either way. */
  static final int CLOCK_CHECK_STEPS = 20;

  public ConfirmTotpFactorApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AccountResult processAuthenticated(ConfirmTotpFactorApiInput input) throws Exception {
    String username = input.getUsername();
    SecondFactorService factors = serviceProvider.getSecondFactorService();
    String secret = factors.pendingTotpSecret(username);
    if (secret == null) return AccountResult.refused("This setup expired: start again.");
    Long step;
    try {
      step = factors.verifyTotp(username, secret, input.getCode(), Long.MIN_VALUE);
    } catch (RateLimiter.RateExceededException e) {
      LOGGER.warning("TOTP 2FA setup for \"" + username + "\" from " + input.getClientAddress() + " refused: too many wrong codes");
      return AccountResult.refused("Too many wrong codes: retry in " + Math.max(1, (e.getRetryAfterMillis() + 59_999) / 60_000) + " min.");
    }
    if (step == null) {
      Long offset = factors.totpOffsetSteps(secret, input.getCode(), CLOCK_CHECK_STEPS);
      LOGGER.info("TOTP 2FA setup for \"" + username + "\" from " + input.getClientAddress() + ": wrong code"
          + (offset == null ? "" : ", from a clock " + offset * Totp.PERIOD_SECONDS + " s off"));
      if (offset == null) return AccountResult.refused("This code is wrong.");
      return AccountResult.refused("This code is from a clock about " + duration(Math.abs(offset) * Totp.PERIOD_SECONDS) + (offset > 0 ? " ahead of" : " behind")
          + " this server's: check that your phone sets its time automatically.");
    }
    UserCredential before = serviceProvider.getSettingsService().getUser(username);
    serviceProvider.getSettingsService().setTotpFactor(username, secret, Instant.now(), step);
    factors.endTotpSetup(username);
    LOGGER.info("User \"" + username + "\" set up TOTP 2FA from " + input.getClientAddress());
    if (before != null && before.totpFactor() != null) SecondFactorNotice.totpChanged(serviceProvider, before, false);
    return AccountResult.done();
  }

  private static String duration(long seconds) {
    return seconds < 120 ? seconds + " s" : Math.round(seconds / 60.0) + " min";
  }
}

@TypeScriptType
class ConfirmTotpFactorApiInput extends AuthenticatedApiInput {
  @Required
  private String code;

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }
}
