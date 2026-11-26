package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.ConfigValidation;
import net.pieroxy.notes.config.MailOutConfiguration;
import net.pieroxy.notes.mail.Mail;
import net.pieroxy.notes.mail.SmtpClient;
import net.pieroxy.notes.services.IServiceProvider;

import java.io.IOException;
import java.util.logging.Logger;

/** Sends a test mail with the saved outgoing mail settings: what went wrong, if anything, is the admin's answer. */
@Endpoint(method = ApiMethod.POST)
public class AdminTestMailOutApi extends AbstractAdminEndpoint<AdminTestMailOutApiInput, AdminResult> {
  private final static Logger LOGGER = Logger.getLogger(AdminTestMailOutApi.class.getName());

  public AdminTestMailOutApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AdminResult processAdmin(AdminTestMailOutApiInput input) throws Exception {
    String who = "Admin \"" + input.getUsername() + "\" from " + input.getClientAddress();
    MailOutConfiguration config = serviceProvider.getSettingsService().readSavedConfig().getMailOut();
    if (config == null || config.getHost() == null || config.getHost().isBlank()) return AdminResult.refused("No SMTP server is saved.");
    Mail mail;
    try {
      ConfigValidation.validate(config);
      mail = new Mail(input.getTo() == null ? "" : input.getTo().trim(), "Test mail from Notes",
          "This mail was sent from Notes, as a test of its outgoing mail settings.\n\nNothing else to do: they work.\n");
    } catch (IllegalArgumentException e) {
      return AdminResult.refused(e.getMessage());
    }
    try {
      new SmtpClient().send(config, mail);
    } catch (IOException e) {
      String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      LOGGER.info(who + " sent a test mail to " + mail.to() + " through " + config.getHost() + ": failed, " + AbstractUserChangeEndpoint.printable(error));
      return AdminResult.refused(error);
    }
    LOGGER.info(who + " sent a test mail to " + mail.to() + " through " + config.getHost());
    return AdminResult.done();
  }
}

@TypeScriptType
class AdminTestMailOutApiInput extends AuthenticatedApiInput {
  @Required
  private String to;

  public String getTo() {
    return to;
  }

  public void setTo(String to) {
    this.to = to;
  }
}
