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
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.ClientGuardService;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.utils.RateLimiter;

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
   */
  @Override
  public LoginApiOutput process(LoginApiInput input, ApiContext context) {
    String client = context.getRemoteAddr();
    UserCredential user = serviceProvider.getSettingsService().getUser(input.getLogin());
    boolean ok;
    try (ClientGuardService.PasswordCheck check = startPasswordCheck(serviceProvider.getClientGuardService(), client, "Login")) {
      ok = PasswordHasher.verify(input.getPassword(), user != null ? user.getPassword() : UNKNOWN_USER_HASH) && user != null;
      if (ok) check.succeeded();
    }
    if (!ok) {
      LOGGER.warning("Failed login for \"" + printable(input.getLogin()) + "\" from " + client);
      return new LoginApiOutput(false, null, false);
    }
    String sessionId = serviceProvider.getSessionService().create(user.getUsername(), context.getUserAgent());
    context.setSessionCookie(sessionId, serviceProvider.getSettingsService().isSessionCookieSecure());
    LOGGER.info("User \"" + user.getUsername() + "\" logged in from " + client);
    return new LoginApiOutput(true, user.getUsername(), user.isAdmin());
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

@TypeScriptType
class LoginApiOutput {
  @Required
  private boolean ok;
  /** Absent unless ok. */
  private String username;
  /** False unless ok. */
  @Required
  private boolean admin;

  public LoginApiOutput() {
  }

  public LoginApiOutput(boolean ok, String username, boolean admin) {
    this.ok = ok;
    this.username = username;
    this.admin = admin;
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
}
