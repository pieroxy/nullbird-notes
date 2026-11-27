package net.pieroxy.notes.api.implementations.auth;

import net.pieroxy.notes.config.SecondFactor;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.mail.Mail;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SecondFactorService;
import net.pieroxy.notes.utils.RateLimiter;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * The second factor of a login. The preferred one is asked; mail only while the server can send it,
 * else the authenticator app.
 */
final class SecondFactorLogin {
  private final static Logger LOGGER = Logger.getLogger(SecondFactorLogin.class.getName());

  private SecondFactorLogin() {
  }

  /**
   * After the right password: a challenge, its code mailed if by mail.
   *
   * @return the answer to give; null if the user has no second factor: they're in.
   */
  static LoginApiOutput start(IServiceProvider serviceProvider, UserCredential user, String client) throws IOException {
    SecondFactor.TotpFactor totp = user.totpFactor();
    SecondFactor.MailFactor mail = user.mailFactor();
    if (totp == null && mail == null) return null;
    String username = user.getUsername();
    SecondFactorService factors = serviceProvider.getSecondFactorService();
    boolean mailUsable = mail != null && factors.mailOut() != null;
    if (totp == null && !mailUsable) {
      LOGGER.warning("Login for \"" + username + "\" from " + client + " refused: their code is mailed, and no SMTP server is saved");
      return LoginApiOutput.refused("Your account asks for a code sent by mail, but this server can't send mail: ask its administrator.");
    }
    boolean byMail = mailUsable && (totp == null || SecondFactor.MAIL.equals(user.getSecondFactor().getPreferred()));
    String challenge = factors.startChallenge(username, byMail ? SecondFactor.MAIL : SecondFactor.TOTP);
    if (!byMail) {
      LOGGER.info("Login for \"" + username + "\" from " + client + ": right password, TOTP code asked");
      return challengeOutput(challenge, SecondFactor.TOTP, null, mailUsable, null);
    }
    String error = mailCode(serviceProvider, username, mail.getAddress(), challenge, client);
    if (error != null) {
      factors.endChallenge(challenge);
      return LoginApiOutput.refused(error);
    }
    return challengeOutput(challenge, SecondFactor.MAIL, mail.getAddress(), totp != null, null);
  }

  /**
   * Mails challenge {@code challenge}'s code.
   *
   * @return why it wasn't sent, for the user; null if it was.
   */
  static String mailCode(IServiceProvider serviceProvider, String username, String address, String challenge, String client) {
    try {
      serviceProvider.getSecondFactorService().sendCode(SecondFactorService.challengeKey(challenge), username, code -> new Mail(address,
          "Notes code: " + code,
          "Your code to log in to Notes as \"" + username + "\":\n\n" + code + "\n\n"
              + "It's valid for " + SecondFactorService.CODE_LIFETIME.toMinutes() + " minutes.\n\n"
              + "If you didn't just log in, someone knows your Notes password: change it.\n"));
    } catch (RateLimiter.RateExceededException e) {
      LOGGER.warning("Login for \"" + username + "\" from " + client + ": too many codes sent");
      return "Too many codes sent: retry in " + Math.max(1, (e.getRetryAfterMillis() + 59_999) / 60_000) + " min.";
    } catch (IOException e) {
      String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      LOGGER.warning("Login for \"" + username + "\" from " + client + ": code to " + address + " not sent: " + error);
      return "Your code couldn't be mailed: " + error;
    }
    LOGGER.info("Login for \"" + username + "\" from " + client + ": code mailed to " + address);
    return null;
  }

  /**
   * @param address    where the code went, by mail.
   * @param otherUsable the other method can be switched to.
   */
  static LoginApiOutput challengeOutput(String challenge, String method, String address, boolean otherUsable, String error) {
    String other = SecondFactor.MAIL.equals(method) ? SecondFactor.TOTP : SecondFactor.MAIL;
    return LoginApiOutput.challenge(challenge, method, address == null ? null : masked(address), otherUsable ? other : null, error);
  }

  /** {@link #challengeOutput(String, String, String, boolean, String)} for {@code user} as they are now. */
  static LoginApiOutput challengeOutput(IServiceProvider serviceProvider, UserCredential user, String challenge, String method, String error)
      throws IOException {
    boolean byMail = SecondFactor.MAIL.equals(method);
    boolean otherUsable = byMail ? user.totpFactor() != null : user.mailFactor() != null && serviceProvider.getSecondFactorService().mailOut() != null;
    return challengeOutput(challenge, method, byMail && user.mailFactor() != null ? user.mailFactor().getAddress() : null, otherUsable, error);
  }

  /** "bob@example.com" as "b***@example.com": enough for its owner to recognize. */
  static String masked(String address) {
    int at = address.indexOf('@');
    return at < 1 ? address : address.charAt(0) + "***" + address.substring(at);
  }
}
