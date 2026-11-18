package net.pieroxy.notes.config;

public class UserCredential {
  /** Also the name of the user's data folder: see {@code SettingsService#USERNAME_PATTERN}. */
  private String username;
  private PasswordHash password;

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public PasswordHash getPassword() {
    return password;
  }

  public void setPassword(PasswordHash password) {
    this.password = password;
  }
}
