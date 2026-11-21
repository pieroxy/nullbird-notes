package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.PasswordGenerator;
import net.pieroxy.notes.config.PasswordHasher;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SettingsService;

import java.time.Clock;
import java.util.regex.Pattern;

/**
 * Adds a user, with a password the server picks and returns this once. Refuses a username whose data
 * folder already exists: the new user would get the notes of whoever had that name before.
 */
@Endpoint(method = ApiMethod.POST)
public class AdminAddUserApi extends AbstractUserChangeEndpoint<AdminAddUserApiInput> {
  /** Loose: something@something.tld, no spaces. Whether it reaches anyone, only sending to it tells. */
  static final Pattern EMAIL_PATTERN = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
  static final int MAX_EMAIL_LENGTH = 254;

  private final Clock clock;

  public AdminAddUserApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
    this.clock = Clock.systemUTC();
  }

  @Override
  protected String describe(AdminAddUserApiInput input) {
    return "adding user \"" + printable(input.getNewUsername()) + "\"";
  }

  @Override
  protected AdminResult change(AdminAddUserApiInput input) throws Exception {
    String username = input.getNewUsername().trim();
    if (!SettingsService.USERNAME_PATTERN.matcher(username).matches()) {
      return AdminResult.refused("A username is 1 to 64 lowercase letters, digits, \".\", \"_\" or \"-\", starting with a letter or a digit.");
    }
    String email = input.getEmail() == null ? "" : input.getEmail().trim();
    if (!email.isEmpty() && (email.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(email).matches())) {
      return AdminResult.refused("That email address isn't valid.");
    }
    SettingsService settings = serviceProvider.getSettingsService();
    if (settings.getUser(username) != null) return AdminResult.refused("There's already a user \"" + username + "\".");
    if (serviceProvider.getNoteStoreService().hasData(username)) {
      return AdminResult.refused("There's data left for \"" + username + "\" from a user removed by hand: pick another name, "
          + "or delete users/" + username + " in the data folder first.");
    }

    String password = PasswordGenerator.generate();
    settings.addUser(username, email.isEmpty() ? null : email, input.isAdmin(), PasswordHasher.hash(password),
        clock.instant());
    log(input, "added user \"" + username + "\"" + (input.isAdmin() ? ", an admin" : "") + (email.isEmpty() ? "" : ", email " + email));
    return AdminResult.withPassword(password);
  }
}

@TypeScriptType
class AdminAddUserApiInput extends AuthenticatedApiInput {
  @Required
  private String newUsername;
  /** Optional. */
  private String email;
  @Required
  private boolean admin;

  public String getNewUsername() {
    return newUsername;
  }

  public void setNewUsername(String newUsername) {
    this.newUsername = newUsername;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public boolean isAdmin() {
    return admin;
  }

  public void setAdmin(boolean admin) {
    this.admin = admin;
  }
}
