package net.pieroxy.notes.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashes {
  private Hashes() {}

  /** Lowercase hex: what clients compute to verify a note's content. */
  public static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
    }
  }

  /** Of the text's UTF-8 bytes. */
  public static String sha256(String text) {
    return sha256(text.getBytes(StandardCharsets.UTF_8));
  }
}
