package net.pieroxy.notes.api.implementations.account;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

/**
 * What a change to the user's own account came to.
 *
 * @param error why it was refused, for the user: absent if ok.
 */
@TypeScriptType
public record AccountResult(@Required boolean ok, String error) {
  static AccountResult done() {
    return new AccountResult(true, null);
  }

  static AccountResult refused(String error) {
    return new AccountResult(false, error);
  }
}
