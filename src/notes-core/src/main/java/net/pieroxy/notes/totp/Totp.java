package net.pieroxy.notes.totp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * RFC 6238 time-based codes with the parameters every authenticator app takes: HMAC-SHA1,
 * {@link #DIGITS} digits, {@link #PERIOD_SECONDS} s steps.
 */
public final class Totp {
  public static final int DIGITS = 6;
  public static final int PERIOD_SECONDS = 30;
  /** Steps accepted either side of the current one: clocks a little apart still agree. */
  public static final int DRIFT_STEPS = 1;
  /** 160 bits, as RFC 4226 recommends. */
  static final int SECRET_BYTES = 20;
  private static final SecureRandom RANDOM = new SecureRandom();

  private Totp() {
  }

  /** @return a new secret, in base32. */
  public static String newSecret() {
    byte[] secret = new byte[SECRET_BYTES];
    RANDOM.nextBytes(secret);
    return Base32.encode(secret);
  }

  /** The step {@code time} falls in. */
  public static long step(Instant time) {
    return Math.floorDiv(time.getEpochSecond(), PERIOD_SECONDS);
  }

  /** @return the code for {@code step}, as the apps show it: {@link #DIGITS} digits. */
  public static String code(byte[] key, long step) {
    return code(key, step, DIGITS);
  }

  /** RFC 4226's HOTP, the step as counter. */
  static String code(byte[] key, long counter, int digits) {
    byte[] hash;
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(key, "HmacSHA1"));
      hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA1 is part of every JDK", e);
    }
    int offset = hash[hash.length - 1] & 0xf;
    int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
    String code = Long.toString(binary % (long) Math.pow(10, digits));
    return "0".repeat(digits - code.length()) + code;
  }

  /**
   * Checks {@code code} against the steps around {@code now}, the ones not after {@code lastUsedStep}
   * excluded: a code seen once can't be used again. Spaces in it are ignored.
   *
   * @param secret       base32.
   * @param lastUsedStep the step last logged in with; {@link Long#MIN_VALUE} for none.
   * @return the step it matched, to save as the last used; null if it matches none.
   * @throws IllegalArgumentException if {@code secret} isn't base32.
   */
  public static Long verify(String secret, String code, Instant now, long lastUsedStep) {
    String typed = code == null ? "" : code.replaceAll("\\s", "");
    if (typed.length() != DIGITS) return null;
    byte[] key = Base32.decode(secret);
    byte[] typedBytes = typed.getBytes(StandardCharsets.US_ASCII);
    long current = step(now);
    Long matched = null;
    // Every step computed and compared, matched or not: the time taken says nothing.
    for (long step = current - DRIFT_STEPS; step <= current + DRIFT_STEPS; step++) {
      boolean equal = MessageDigest.isEqual(code(key, step).getBytes(StandardCharsets.US_ASCII), typedBytes);
      if (equal && step > lastUsedStep && matched == null) matched = step;
    }
    return matched;
  }
}
