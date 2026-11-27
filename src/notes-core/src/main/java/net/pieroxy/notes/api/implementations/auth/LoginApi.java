package net.pieroxy.notes.api.implementations.auth;

import net.pieroxy.notes.api.metadata.AbstractApiEndpoint;
import net.pieroxy.notes.api.metadata.ApiContext;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TooManyRequestsException;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.PasswordHash;
import net.pieroxy.notes.config.PasswordHasher;
import net.pieroxy.notes.config.SecondFactor;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.mail.Mail;
import net.pieroxy.notes.services.ClientGuardService;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SecondFactorService;
import net.pieroxy.notes.utils.RateLimiter;

import java.io.IOException;
import java.util.logging.Logger;

@Endpoint(method = ApiMethod.POST)
public class LoginApi extends AbstractApiEndpoint<LoginApiInput, LoginApiOutput> {
  private final static Logger LOGGER = Logger.getLogger(LoginApi.class.getName());
  /** Verified against for an unknown user, so that it takes as long as a wrong password. */
  private final static PasswordHash UNKNOWN_USER_HASH = PasswordHasher.hash("unknown user");

  private final IServiceProvider serviceProvider;

  public LoginApi(IServiceProvider serviceProvider) {
    this.serviceProvider = serviceProvider;
  }

  /**
   * Refused outright, before the password is even checked, once the client's address failed too
   * many times, or while the server checks as many passwords as it takes at once (see
   * {@link ClientGuardService#startPasswordCheck}). An unknown user fails like a wrong password.
   * With a second factor, the right password mails a code and answers a challenge, which LoginCode
   * takes with the code: no session until then.
   */
  @Override
  public LoginApiOutput process(LoginApiInput input, ApiContext context) throws Exception {
    String client = context.getRemoteAddr();
    UserCredential user = serviceProvider.getSettingsService().getUser(input.getLogin());
    boolean ok;
    try (ClientGuardService.PasswordCheck check = startPasswordCheck(serviceProvider.getClientGuardService(), client, "Login")) {
      ok = PasswordHasher.verify(input.getPassword(), user != null ? user.getPassword() : UNKNOWN_USER_HASH) && user != null;
      if (ok) check.succeeded();
    }
    if (!ok) {
      LOGGER.warning("Failed login for \"" + printable(input.getLogin()) + "\" from " + client);
      return LoginApiOutput.refused(null);
    }
    SecondFactor.MailFactor mail = user.mailFactor();
    if (mail != null) return mailChallenge(user.getUsername(), mail.getAddress(), client);
    return openSession(serviceProvider, user, context, "");
  }

  private LoginApiOutput mailChallenge(String username, String address, String client) throws IOException {
    SecondFactorService factors = serviceProvider.getSecondFactorService();
    if (factors.mailOut() == null) {
      LOGGER.warning("Login for \"" + username + "\" from " + client + " refused: their code is mailed, and no SMTP server is saved");
      return LoginApiOutput.refused("Your account asks for a code sent by mail, but this server can't send mail: ask its administrator.");
    }
    String challenge = factors.startChallenge(username);
    try {
      factors.sendCode(SecondFactorService.challengeKey(challenge), username, code -> new Mail(address,
          "Notes code: " + code,
          "Your code to log in to Notes as \"" + username + "\":\n\n" + code + "\n\n"
              + "It's valid for " + SecondFactorService.CODE_LIFETIME.toMinutes() + " minutes.\n\n"
              + "If you didn't just log in, someone knows your Notes password: change it.\n"));
    } catch (RateLimiter.RateExceededException e) {
      factors.endChallenge(challenge);
      long minutes = Math.max(1, (e.getRetryAfterMillis() + 59_999) / 60_000);
      LOGGER.warning("Login for \"" + username + "\" from " + client + " refused: too many codes sent");
      return LoginApiOutput.refused("Too many codes sent: retry in " + minutes + " min.");
    } catch (IOException e) {
      factors.endChallenge(challenge);
      String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      LOGGER.warning("Login for \"" + username + "\" from " + client + ": code to " + address + " not sent: " + error);
      return LoginApiOutput.refused("Your code couldn't be mailed: " + error);
    }
    LOGGER.info("Login for \"" + username + "\" from " + client + ": right password, code mailed to " + address);
    return LoginApiOutput.challenge(challenge, masked(address));
  }

  /**
   * Logs {@code user} in: a session, its cookie.
   *
   * @param how for the log, appended: "" or e.g. " with a code by mail".
   */
  static LoginApiOutput openSession(IServiceProvider serviceProvider, UserCredential user, ApiContext context, String how) {
    String sessionId = serviceProvider.getSessionService().create(user.getUsername(), context.getUserAgent());
    context.setSessionCookie(sessionId, serviceProvider.getSettingsService().isSessionCookieSecure());
    LOGGER.info("User \"" + user.getUsername() + "\" logged in from " + context.getRemoteAddr() + how);
    return new LoginApiOutput(true, user.getUsername(), user.isAdmin(), null, null, null);
  }

  /** "bob@example.com" as "b***@example.com": enough for its owner to recognize. */
  static String masked(String address) {
    int at = address.indexOf('@');
    return at < 1 ? address : address.charAt(0) + "***" + address.substring(at);
  }

  /**
   * {@link ClientGuardService#startPasswordCheck}, its refusals as HTTP 429s, for every endpoint
   * checking a password.
   *
   * @param what for the log: "Login", "Password change"...
   */
  public static ClientGuardService.PasswordCheck startPasswordCheck(ClientGuardService guard, String client, String what) {
    try {
      return guard.startPasswordCheck(client);
    } catch (ClientGuardService.Busy e) {
      // Not logged: under a flood, that would be a line per request.
      throw new TooManyRequestsException("Please retry later.", 1);
    } catch (RateLimiter.RateExceededException e) {
      long retryAfterSeconds = Math.max(1, (e.getRetryAfterMillis() + 999) / 1000);
      LOGGER.warning(what + " refused for " + client + ": too many failed attempts, retry in " + retryAfterSeconds + " s");
      throw new TooManyRequestsException("Too many failed attempts, retry in " + retryAfterSeconds + " s.", retryAfterSeconds);
    }
  }

  /** The submitted login, safe to log: control characters escaped, so it can't forge a log line. */
  private static String printable(String login) {
    if (login == null) return "";
    StringBuilder sb = new StringBuilder();
    for (char c : login.toCharArray()) {
      if (c < 0x20 || c == 0x7f) sb.append(String.format("\\x%02x", (int) c));
      else sb.append(c);
    }
    return sb.toString();
  }
}

@TypeScriptType
class LoginApiInput {
  @Required
  private String login;
  @Required
  private String password;

  public String getLogin() {
    return login;
  }

  public void setLogin(String login) {
    this.login = login;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }
}

/** Also LoginCode's. */
@TypeScriptType
class LoginApiOutput {
  @Required
  private boolean ok;
  /** Absent unless ok. */
  private String username;
  /** False unless ok. */
  @Required
  private boolean admin;
  /** Not ok, the password right: a code is asked, to give LoginCode with this. */
  private String challenge;
  /** With a challenge: where the code went, partly hidden. */
  private String sentTo;
  /** Not ok: why, for the user. Absent, with no challenge: a wrong login or password. */
  private String error;

  public LoginApiOutput() {
  }

  public LoginApiOutput(boolean ok, String username, boolean admin, String challenge, String sentTo, String error) {
    this.ok = ok;
    this.username = username;
    this.admin = admin;
    this.challenge = challenge;
    this.sentTo = sentTo;
    this.error = error;
  }

  static LoginApiOutput refused(String error) {
    return new LoginApiOutput(false, null, false, null, null, error);
  }

  static LoginApiOutput challenge(String challenge, String sentTo) {
    return new LoginApiOutput(false, null, false, challenge, sentTo, null);
  }

  public boolean isOk() {
    return ok;
  }

  public void setOk(boolean ok) {
    this.ok = ok;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public boolean isAdmin() {
    return admin;
  }

  public void setAdmin(boolean admin) {
    this.admin = admin;
  }

  public String getChallenge() {
    return challenge;
  }

  public void setChallenge(String challenge) {
    this.challenge = challenge;
  }

  public String getSentTo() {
    return sentTo;
  }

  public void setSentTo(String sentTo) {
    this.sentTo = sentTo;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }
}
