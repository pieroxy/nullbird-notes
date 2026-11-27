package net.pieroxy.notes.api.implementations.account;

import net.pieroxy.notes.api.implementations.auth.LoginApi;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.config.PasswordHasher;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.ClientGuardService;
import net.pieroxy.notes.services.IServiceProvider;

import java.util.logging.Logger;

/** The logged-in user's password, asked again before a change to their second factor. */
final class CurrentPassword {
  private final static Logger LOGGER = Logger.getLogger(CurrentPassword.class.getName());

  private CurrentPassword() {
  }

  /**
   * A wrong one counts as a failed login (see {@link ClientGuardService}), and is logged.
   *
   * @param what for the log, e.g. "Mail 2FA removal".
   * @throws net.pieroxy.notes.api.metadata.TooManyRequestsException after too many failed attempts.
   */
  static boolean check(IServiceProvider serviceProvider, AuthenticatedApiInput input, String password, String what) {
    String username = input.getUsername();
    UserCredential user = serviceProvider.getSettingsService().getUser(username);
    boolean ok;
    try (ClientGuardService.PasswordCheck check = LoginApi.startPasswordCheck(serviceProvider.getClientGuardService(), input.getClientAddress(),
        what + " for \"" + username + "\"")) {
      ok = user != null && password != null && PasswordHasher.verify(password, user.getPassword());
      if (ok) check.succeeded();
    }
    if (!ok) LOGGER.warning(what + " for \"" + username + "\" from " + input.getClientAddress() + ": wrong password");
    return ok;
  }
}
