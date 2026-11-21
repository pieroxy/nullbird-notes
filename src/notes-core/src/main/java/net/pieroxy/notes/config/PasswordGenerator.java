package net.pieroxy.notes.config;

import java.security.SecureRandom;

/** Passwords the server picks for a user: given to an admin once, never stored readable. */
public final class PasswordGenerator {
  /** Letters and digits, without those easily mistaken for one another: 0 O o, 1 l I. */
  static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  /** About 92 bits. */
  public static final int LENGTH = 16;
  private static final SecureRandom RANDOM = new SecureRandom();

  private PasswordGenerator() {}

  public static String generate() {
    StringBuilder password = new StringBuilder(LENGTH);
    for (int i = 0; i < LENGTH; i++) password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    return password.toString();
  }
}
