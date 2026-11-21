package net.pieroxy.notes.api.implementations.auth;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TooManyRequestsException;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.PasswordHasher;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.ClientGuardService;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.utils.RateLimiter;

import java.util.logging.Logger;

/**
 * Changes the logged-in user's password, given the current one. A wrong current password counts as
 * a failed login (see {@link ClientGuardService}). Once changed, the user is logged out everywhere
 * but here.
 */
@Endpoint(method = ApiMethod.POST)
public class ChangePasswordApi extends AbstractAuthenticatedEndpoint<ChangePasswordApiInput, ChangePasswordApiOutput> {
  private final static Logger LOGGER = Logger.getLogger(ChangePasswordApi.class.getName());
  public static final int MIN_LENGTH = 12;

  public ChangePasswordApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public ChangePasswordApiOutput processAuthenticated(ChangePasswordApiInput input) throws Exception {
    String username = input.getUsername();
    String client = input.getClientAddress();
    String newPassword = input.getNewPassword();
    if (newPassword.codePointCount(0, newPassword.length()) < MIN_LENGTH) {
      LOGGER.info("Password change for \"" + username + "\" from " + client + " refused: the new one is too short");
      return ChangePasswordApiOutput.refused("The new password must be at least " + MIN_LENGTH + " characters long.");
    }

    ClientGuardService guard = serviceProvider.getClientGuardService();
    try {
      guard.checkLogin(client);
    } catch (RateLimiter.RateExceededException e) {
      long retryAfterSeconds = Math.max(1, (e.getRetryAfterMillis() + 999) / 1000);
      LOGGER.warning("Password change refused for \"" + username + "\" from " + client + ": too many failed attempts");
      throw new TooManyRequestsException("Too many failed attempts, retry in " + retryAfterSeconds + " s.", retryAfterSeconds);
    }
    UserCredential user = serviceProvider.getSettingsService().getUser(username);
    if (user == null || !PasswordHasher.verify(input.getCurrentPassword(), user.getPassword())) {
      guard.loginFailed(client);
      LOGGER.warning("Password change for \"" + username + "\" from " + client + ": wrong current password");
      return ChangePasswordApiOutput.refused("The current password is wrong.");
    }
    guard.loginSucceeded(client);
    if (newPassword.equals(input.getCurrentPassword())) {
      LOGGER.info("Password change for \"" + username + "\" from " + client + " refused: the new one is the current one");
      return ChangePasswordApiOutput.refused("The new password is the same as the current one.");
    }

    serviceProvider.getSettingsService().setPassword(username, PasswordHasher.hash(newPassword));
    serviceProvider.getSessionService().invalidateOthers(username, input.getSessionId());
    LOGGER.info("User \"" + username + "\" changed their password from " + client + ": logged out of their other sessions");
    return new ChangePasswordApiOutput(true, null);
  }
}

@TypeScriptType
class ChangePasswordApiInput extends AuthenticatedApiInput {
  @Required
  private String currentPassword;
  @Required
  private String newPassword;

  public String getCurrentPassword() {
    return currentPassword;
  }

  public void setCurrentPassword(String currentPassword) {
    this.currentPassword = currentPassword;
  }

  public String getNewPassword() {
    return newPassword;
  }

  public void setNewPassword(String newPassword) {
    this.newPassword = newPassword;
  }
}

@TypeScriptType
class ChangePasswordApiOutput {
  @Required
  private boolean ok;
  /** Why it was refused, for the user: absent if ok. */
  private String error;

  public ChangePasswordApiOutput() {
  }

  public ChangePasswordApiOutput(boolean ok, String error) {
    this.ok = ok;
    this.error = error;
  }

  static ChangePasswordApiOutput refused(String error) {
    return new ChangePasswordApiOutput(false, error);
  }

  public boolean isOk() {
    return ok;
  }

  public void setOk(boolean ok) {
    this.ok = ok;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }
}
