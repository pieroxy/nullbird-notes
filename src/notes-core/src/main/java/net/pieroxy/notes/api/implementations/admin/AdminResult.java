package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

/**
 * What an admin's change came to.
 *
 * @param error    why it was refused, for the admin: absent if ok.
 * @param password the password the server picked, if the change picked one: shown to the admin
 *                 this once, never stored readable.
 */
@TypeScriptType
public record AdminResult(@Required boolean ok, String error, String password) {
  static AdminResult done() {
    return new AdminResult(true, null, null);
  }

  static AdminResult withPassword(String password) {
    return new AdminResult(true, null, password);
  }

  static AdminResult refused(String error) {
    return new AdminResult(false, error, null);
  }
}
