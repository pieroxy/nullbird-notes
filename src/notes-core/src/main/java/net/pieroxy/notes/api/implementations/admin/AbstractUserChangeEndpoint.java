package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.services.IServiceProvider;

import java.util.logging.Logger;

/**
 * An admin's change to users. Every outcome is logged at INFO, with who and from where: what was
 * done by the endpoint (see {@link #log}), a refusal here, with its reason.
 */
abstract class AbstractUserChangeEndpoint<I extends AuthenticatedApiInput> extends AbstractAdminEndpoint<I, AdminResult> {
  private final Logger logger = Logger.getLogger(getClass().getName());

  protected AbstractUserChangeEndpoint(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public final AdminResult processAdmin(I input) throws Exception {
    AdminResult result = change(input);
    if (!result.ok()) log(input, "was refused " + describe(input) + ": " + printable(result.error()));
    return result;
  }

  /** What the admin asked, for the log, e.g. {@code resetting the password of "bob"}. */
  protected abstract String describe(I input);

  protected abstract AdminResult change(I input) throws Exception;

  /** At INFO: "Admin "alice" from 192.168.1.2 " + {@code what}. */
  protected void log(I input, String what) {
    logger.info("Admin \"" + input.getUsername() + "\" from " + input.getClientAddress() + " " + what);
  }

  /** As LoginApi does: control characters escaped, so a submitted name can't forge a log line. */
  static String printable(String value) {
    if (value == null) return "";
    StringBuilder sb = new StringBuilder();
    for (char c : value.toCharArray()) {
      if (c < 0x20 || c == 0x7f) sb.append(String.format("\\x%02x", (int) c));
      else sb.append(c);
    }
    return sb.toString();
  }
}
