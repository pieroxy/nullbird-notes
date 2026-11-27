package net.pieroxy.notes.api.implementations.account;

import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.mail.Mail;
import net.pieroxy.notes.services.IServiceProvider;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Tells the user their second factor changed: if it wasn't them, they learn that someone has their
 * password. Not sent is logged, and doesn't undo the change.
 */
public final class SecondFactorNotice {
  private final static Logger LOGGER = Logger.getLogger(SecondFactorNotice.class.getName());
  private static final String IF_NOT_YOU = "If you didn't do it, someone knows your Notes password: change it, and set up two-step verification again.\n";

  private SecondFactorNotice() {
  }

  /**
   * To the address the mail factor used.
   *
   * @param newAddress null if it was turned off.
   */
  static void mailChanged(IServiceProvider serviceProvider, String username, String oldAddress, String newAddress) {
    if (newAddress == null) {
      send(serviceProvider, username, oldAddress, "Notes: two-step verification by mail turned off",
          "Two-step verification by mail was turned off for your Notes account \"" + username + "\".\n\n");
    } else {
      send(serviceProvider, username, oldAddress, "Notes: two-step verification address changed",
          "Codes for your Notes account \"" + username + "\" are now mailed to " + newAddress + ", no longer to this address.\n\n");
    }
  }

  /**
   * To the mail factor's address, else the user's contact address, else nobody.
   *
   * @param user as before the change.
   * @param removed turned off, rather than moved to another app.
   */
  static void totpChanged(IServiceProvider serviceProvider, UserCredential user, boolean removed) {
    String to = user.mailFactor() != null ? user.mailFactor().getAddress() : user.getEmail();
    if (to == null || to.isBlank()) return;
    String username = user.getUsername();
    if (removed) {
      send(serviceProvider, username, to, "Notes: authenticator app turned off",
          "Codes from an authenticator app are no longer asked for your Notes account \"" + username + "\".\n\n");
    } else {
      send(serviceProvider, username, to, "Notes: authenticator app changed",
          "Codes for your Notes account \"" + username + "\" now come from a newly set up authenticator app: the previous one no longer works.\n\n");
    }
  }

  /**
   * To the mail factor's address, else the user's contact address, else nobody.
   *
   * @param user as before the reset.
   */
  public static void resetByAdmin(IServiceProvider serviceProvider, UserCredential user) {
    String to = user.mailFactor() != null ? user.mailFactor().getAddress() : user.getEmail();
    if (to == null || to.isBlank()) return;
    send(serviceProvider, user.getUsername(), to, "Notes: two-step verification reset",
        "An administrator turned off two-step verification for your Notes account \"" + user.getUsername() + "\": "
            + "only your password is asked at login now. Set it up again in My Account, Security.\n\n");
  }

  private static void send(IServiceProvider serviceProvider, String username, String to, String subject, String what) {
    try {
      serviceProvider.getSecondFactorService().sendNotice(new Mail(to, subject, what + IF_NOT_YOU));
      LOGGER.info("2FA notice for \"" + username + "\" sent to " + to + ": " + subject);
    } catch (IOException | IllegalArgumentException e) {
      LOGGER.warning("2FA notice for \"" + username + "\" to " + to + " not sent: " + e.getMessage());
    }
  }
}
