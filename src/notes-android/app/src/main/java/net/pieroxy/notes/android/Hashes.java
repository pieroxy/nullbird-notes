package net.pieroxy.notes.android;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Hashes {
  private Hashes() {}

  static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** @return lowercase hex. */
  static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
    return out.toString();
  }

  static String sha256(String text) {
    return hex(sha256().digest(text.getBytes(StandardCharsets.UTF_8)));
  }
}
