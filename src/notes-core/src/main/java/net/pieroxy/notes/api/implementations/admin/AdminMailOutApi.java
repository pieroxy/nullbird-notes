package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.MailOutConfiguration;
import net.pieroxy.notes.services.IServiceProvider;

/** config.json's outgoing mail settings, as saved: they apply at once. The password never leaves the server. */
@Endpoint(method = ApiMethod.GET)
public class AdminMailOutApi extends AbstractAdminEndpoint<AdminMailOutApiInput, AdminMailOut> {
  public AdminMailOutApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AdminMailOut processAdmin(AdminMailOutApiInput input) throws Exception {
    var settings = serviceProvider.getSettingsService();
    var admin = settings.getUser(input.getUsername());
    String email = admin == null || admin.getEmail() == null ? "" : admin.getEmail();
    return AdminMailOut.of(settings.readSavedConfig().getMailOut(), email);
  }
}

/**
 * Defaults filled in.
 *
 * @param host        "": no mail is sent.
 * @param username    "": no authentication.
 * @param passwordSet a password is saved, unused without a user name.
 * @param yourEmail   the admin's own address, "" if none: where a test mail would go.
 */
@TypeScriptType
record AdminMailOut(@Required String host, @Required int port, @Required MailOutConfiguration.Security security, @Required String username,
                    @Required boolean passwordSet, @Required String from, @Required String yourEmail) {
  static AdminMailOut of(MailOutConfiguration mail, String yourEmail) {
    if (mail == null) mail = new MailOutConfiguration();
    MailOutConfiguration.Security security = mail.getSecurity() == null ? MailOutConfiguration.Security.STARTTLS : mail.getSecurity();
    return new AdminMailOut(blankIfNull(mail.getHost()), mail.getPort() == 0 ? security.defaultPort() : mail.getPort(), security,
        blankIfNull(mail.getUsername()), mail.getPassword() != null && !mail.getPassword().isEmpty(), blankIfNull(mail.getFrom()), yourEmail);
  }

  private static String blankIfNull(String value) {
    return value == null ? "" : value.trim();
  }
}

@TypeScriptType
class AdminMailOutApiInput extends AuthenticatedApiInput {
}
