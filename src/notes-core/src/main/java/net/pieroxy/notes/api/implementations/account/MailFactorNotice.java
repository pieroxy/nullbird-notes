package net.pieroxy.notes.api.implementations.account;

import net.pieroxy.notes.mail.Mail;
import net.pieroxy.notes.services.IServiceProvider;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Tells the address a mail factor used that it no longer does: if the change wasn't the user's, they
 * learn that someone has their password. Not sent is logged, and doesn't undo the change.
 */
final class MailFactorNotice {
  private final static Logger LOGGER = Logger.getLogger(MailFactorNotice.class.getName());

  private MailFactorNotice() {
  }

  /** @param newAddress null if two-step verification was turned off. */
  static void send(IServiceProvider serviceProvider, String username, String oldAddress, String newAddress) {
    String what = newAddress == null
        ? "Two-step verification was turned off for your Notes account \"" + username + "\".\n\n"
        : "Codes for your Notes account \"" + username + "\" are now mailed to " + newAddress + ", no longer to this address.\n\n";
    try {
      Mail mail = new Mail(oldAddress, newAddress == null ? "Notes: two-step verification turned off" : "Notes: two-step verification address changed",
          what + "If you didn't do it, someone knows your Notes password: change it, and set up two-step verification again.\n");
      serviceProvider.getSecondFactorService().sendNotice(mail);
      LOGGER.info("Mail 2FA notice for \"" + username + "\" sent to " + oldAddress);
    } catch (IOException | IllegalArgumentException e) {
      LOGGER.warning("Mail 2FA notice for \"" + username + "\" to " + oldAddress + " not sent: " + e.getMessage());
    }
  }
}
