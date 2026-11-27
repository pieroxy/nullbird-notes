package net.pieroxy.notes.api.implementations.account;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.SecondFactor;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SecondFactorService;

/** The logged-in user's second factor, and what they can set up. */
@Endpoint(method = ApiMethod.GET)
public class SecondFactorApi extends AbstractAuthenticatedEndpoint<SecondFactorApiInput, SecondFactorStatus> {
  public SecondFactorApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public SecondFactorStatus processAuthenticated(SecondFactorApiInput input) throws Exception {
    UserCredential user = serviceProvider.getSettingsService().getUser(input.getUsername());
    SecondFactor.MailFactor mail = user == null ? null : user.mailFactor();
    String email = user == null || user.getEmail() == null ? "" : user.getEmail();
    var factors = serviceProvider.getSecondFactorService();
    SecondFactor.TotpFactor totp = user == null ? null : user.totpFactor();
    String preferred = user == null || user.getSecondFactor() == null ? null : user.getSecondFactor().getPreferred();
    return new SecondFactorStatus(factors.mailOut() != null, mail == null ? null : new MailFactorStatus(mail.getAddress(), mail.getConfirmedAt()),
        factors.pendingAddress(SecondFactorService.enrollKey(input.getUsername())), email, totp == null ? null : new TotpFactorStatus(totp.getConfirmedAt()),
        mail != null && totp != null ? preferred : null);
  }
}

/**
 * @param mailAvailable  the server can send mail.
 * @param mail           absent: no mail factor.
 * @param pendingAddress where a code to set one up was sent and is still valid, if any.
 * @param contactEmail   the user's contact address, "" if none: a first suggestion.
 * @param totp           absent: no TOTP factor.
 * @param preferred      "mail" or "totp", asked first at login: only with both.
 */
@TypeScriptType
record SecondFactorStatus(@Required boolean mailAvailable, MailFactorStatus mail, String pendingAddress, @Required String contactEmail,
                          TotpFactorStatus totp, String preferred) {
}

/** @param confirmedAt ISO 8601. */
@TypeScriptType
record TotpFactorStatus(String confirmedAt) {
}

/** @param confirmedAt ISO 8601. */
@TypeScriptType
record MailFactorStatus(@Required String address, String confirmedAt) {
}

@TypeScriptType
class SecondFactorApiInput extends AuthenticatedApiInput {
}
