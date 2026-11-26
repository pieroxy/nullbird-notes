package net.pieroxy.notes.api.implementations.admin;

import com.google.gson.JsonObject;
import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.ConfigValidation;
import net.pieroxy.notes.config.MailOutConfiguration;
import net.pieroxy.notes.services.IServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Writes the outgoing mail settings to config.json's "mailOut", checked first; they apply at once.
 * The saved password stays unless a new one is given, and goes with the user name.
 */
@Endpoint(method = ApiMethod.POST)
public class AdminSaveMailOutApi extends AbstractAdminEndpoint<AdminSaveMailOutApiInput, AdminResult> {
  private final static Logger LOGGER = Logger.getLogger(AdminSaveMailOutApi.class.getName());

  public AdminSaveMailOutApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AdminResult processAdmin(AdminSaveMailOutApiInput input) throws Exception {
    String who = "Admin \"" + input.getUsername() + "\" from " + input.getClientAddress();
    MailOutConfiguration saved = serviceProvider.getSettingsService().readSavedConfig().getMailOut();
    if (saved == null) saved = new MailOutConfiguration();
    MailOutConfiguration wanted = new MailOutConfiguration();
    wanted.setHost(trimmed(input.getHost()));
    wanted.setPort(input.getPort());
    wanted.setSecurity(input.getSecurity());
    wanted.setUsername(trimmed(input.getSmtpUsername()));
    wanted.setFrom(trimmed(input.getFrom()));
    if (!wanted.getUsername().isEmpty()) {
      boolean newPassword = input.getPassword() != null && !input.getPassword().isEmpty();
      wanted.setPassword(newPassword ? input.getPassword() : saved.getPassword());
    }
    try {
      ConfigValidation.validate(wanted);
    } catch (IllegalArgumentException e) {
      LOGGER.info(who + " was refused changing the outgoing mail settings: " + AbstractUserChangeEndpoint.printable(e.getMessage()));
      return AdminResult.refused(e.getMessage());
    }

    AdminMailOut before = AdminMailOut.of(saved, "");
    AdminMailOut after = AdminMailOut.of(wanted, "");
    List<String> changes = new ArrayList<>();
    if (!before.host().equals(after.host())) changes.add("host \"" + before.host() + "\" → \"" + after.host() + "\"");
    if (before.port() != after.port()) changes.add("port " + before.port() + " → " + after.port());
    if (before.security() != after.security()) changes.add("security " + before.security() + " → " + after.security());
    if (!before.username().equals(after.username())) changes.add("username \"" + before.username() + "\" → \"" + after.username() + "\"");
    if (!Objects.equals(saved.getPassword(), wanted.getPassword())) changes.add(wanted.getPassword() == null ? "password removed" : "password changed");
    if (!before.from().equals(after.from())) changes.add("from \"" + before.from() + "\" → \"" + after.from() + "\"");
    if (changes.isEmpty()) return AdminResult.done();

    JsonObject mailOut = new JsonObject();
    mailOut.addProperty("host", wanted.getHost());
    mailOut.addProperty("port", wanted.getPort());
    mailOut.addProperty("security", wanted.getSecurity().name());
    mailOut.addProperty("username", wanted.getUsername());
    if (wanted.getPassword() != null) mailOut.addProperty("password", wanted.getPassword());
    mailOut.addProperty("from", wanted.getFrom());
    serviceProvider.getSettingsService().updateSavedConfig(json -> json.add("mailOut", mailOut));
    LOGGER.info(who + " changed the outgoing mail settings: " + AbstractUserChangeEndpoint.printable(String.join(", ", changes)));
    return AdminResult.done();
  }

  private static String trimmed(String value) {
    return value == null ? "" : value.trim();
  }
}

/** {@code smtpUsername}: {@code username} is the admin's, from the session. */
@TypeScriptType
class AdminSaveMailOutApiInput extends AuthenticatedApiInput {
  /** Blank: no mail is sent. */
  @Required
  private String host;
  @Required
  private int port;
  @Required
  private MailOutConfiguration.Security security;
  /** Blank: no authentication, and the saved password goes. */
  @Required
  private String smtpUsername;
  /** Absent or empty: the saved one stays. */
  private String password;
  @Required
  private String from;

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public MailOutConfiguration.Security getSecurity() {
    return security;
  }

  public void setSecurity(MailOutConfiguration.Security security) {
    this.security = security;
  }

  public String getSmtpUsername() {
    return smtpUsername;
  }

  public void setSmtpUsername(String smtpUsername) {
    this.smtpUsername = smtpUsername;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getFrom() {
    return from;
  }

  public void setFrom(String from) {
    this.from = from;
  }
}
