package net.pieroxy.notes.api.implementations.account;

/** Keys of the codes in {@link net.pieroxy.notes.services.SecondFactorService}. */
final class MailFactorKeys {
  private MailFactorKeys() {
  }

  /** The code proving a new mail factor's address. */
  static String enroll(String username) {
    return "enroll:" + username;
  }
}
