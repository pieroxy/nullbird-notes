package net.pieroxy.notes.config;

public class UserCredential {
  /** Also the name of the user's data folder: see {@code SettingsService#USERNAME_PATTERN}. */
  private String username;
  private PasswordHash password;
  /** Sees the Administration pages. Absent: false. */
  private boolean admin;

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

  public boolean isAdmin() {
    return admin;
  }

  public void setAdmin(boolean admin) {
    this.admin = admin;
  }
}
