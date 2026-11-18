package net.pieroxy.notes.config;

/**
 * A salted, iterated password hash (see {@link PasswordHasher}). {@code algo}/{@code iterations}
 * are stored alongside the salt and hash so that changing either in code doesn't invalidate
 * existing credentials.json entries.
 */
public class PasswordHash {
  private String algo;
  private int iterations;
  /** Base64. */
  private String salt;
  /** Base64. */
  private String hash;

  public String getAlgo() {
    return algo;
  }

  public void setAlgo(String algo) {
    this.algo = algo;
  }

  public int getIterations() {
    return iterations;
  }

  public void setIterations(int iterations) {
    this.iterations = iterations;
  }

  public String getSalt() {
    return salt;
  }

  public void setSalt(String salt) {
    this.salt = salt;
  }

  public String getHash() {
    return hash;
  }

  public void setHash(String hash) {
    this.hash = hash;
  }
}
