package net.pieroxy.notes.config;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/** PBKDF2-HMAC-SHA256, built into the JDK, at OWASP's current minimum iteration count for it. */
public final class PasswordHasher {
  private static final String ALGO = "PBKDF2WithHmacSHA256";
  private static final int ITERATIONS = 210_000;
  private static final int SALT_BYTES = 16;
  private static final int KEY_LENGTH_BITS = 256;

  private PasswordHasher() {}

  public static PasswordHash hash(String password) {
    byte[] salt = new byte[SALT_BYTES];
    new SecureRandom().nextBytes(salt);
    byte[] derived = pbkdf2(ALGO, password, salt, ITERATIONS);

    PasswordHash result = new PasswordHash();
    result.setAlgo(ALGO);
    result.setIterations(ITERATIONS);
    result.setSalt(Base64.getEncoder().encodeToString(salt));
    result.setHash(Base64.getEncoder().encodeToString(derived));
    return result;
  }

  /** @return true if password, re-hashed with expected's own algo/iterations/salt, matches its hash. */
  public static boolean verify(String password, PasswordHash expected) {
    if (password == null || expected == null || expected.getHash() == null || expected.getSalt() == null) return false;
    byte[] salt = Base64.getDecoder().decode(expected.getSalt());
    byte[] actual = pbkdf2(expected.getAlgo(), password, salt, expected.getIterations());
    byte[] wanted = Base64.getDecoder().decode(expected.getHash());
    // Constant-time: a mismatch's position can't leak through how long the comparison took.
    return MessageDigest.isEqual(wanted, actual);
  }

  private static byte[] pbkdf2(String algo, String password, byte[] salt, int iterations) {
    try {
      PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
      SecretKeyFactory factory = SecretKeyFactory.getInstance(algo);
      return factory.generateSecret(spec).getEncoded();
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException(algo + " is a mandatory JDK algorithm", e);
    }
  }
}
