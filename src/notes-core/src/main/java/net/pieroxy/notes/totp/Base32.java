package net.pieroxy.notes.totp;

import java.io.ByteArrayOutputStream;

/** RFC 4648 base32, as authenticator apps take keys: written unpadded. */
public final class Base32 {
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  private Base32() {
  }

  public static String encode(byte[] bytes) {
    StringBuilder sb = new StringBuilder((bytes.length * 8 + 4) / 5);
    int buffer = 0;
    int bits = 0;
    for (byte b : bytes) {
      buffer = (buffer << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
        bits -= 5;
      }
    }
    if (bits > 0) sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
    return sb.toString();
  }

  /**
   * Case, spaces, dashes and padding ignored: a key as typed or copied.
   *
   * @throws IllegalArgumentException on any other character outside the alphabet.
   */
  public static byte[] decode(String text) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int buffer = 0;
    int bits = 0;
    for (char c : text.toCharArray()) {
      if (c == ' ' || c == '-' || c == '=') continue;
      int value = ALPHABET.indexOf(Character.toUpperCase(c));
      if (value < 0) throw new IllegalArgumentException("Not base32: " + c);
      buffer = (buffer << 5) | value;
      bits += 5;
      if (bits >= 8) {
        out.write((buffer >> (bits - 8)) & 0xff);
        bits -= 8;
      }
    }
    return out.toByteArray();
  }
}
