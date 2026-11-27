package net.pieroxy.notes.config;

import java.time.Instant;

public class UserCredential {
  /** Also the name of the user's data folder: see {@code SettingsService#USERNAME_PATTERN}. */
  private String username;
  private PasswordHash password;
  /** Sees the Administration pages. Absent: false. */
  private boolean admin;
  /** Optional: the user's contact address. The 2FA one is in {@link #secondFactor}. */
  private String email;
  /** ISO 8601, e.g. 2026-11-21T06:30:00Z. Absent for users written by hand. */
  private String createdAt;
  /** Absent: no second factor. */
  private SecondFactor secondFactor;

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

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(String createdAt) {
    this.createdAt = createdAt;
  }

  public SecondFactor getSecondFactor() {
    return secondFactor;
  }

  public void setSecondFactor(SecondFactor secondFactor) {
    this.secondFactor = secondFactor;
  }

  /** @return the confirmed TOTP factor, null if none. */
  public SecondFactor.TotpFactor totpFactor() {
    return secondFactor == null ? null : secondFactor.getTotp();
  }

  /** @return the confirmed mail factor, null if none. */
  public SecondFactor.MailFactor mailFactor() {
    return secondFactor == null ? null : secondFactor.getMail();
  }

  /**
   * @return {@link #getCreatedAt()} parsed, null if absent.
   * @throws java.time.format.DateTimeParseException if it isn't ISO 8601.
   */
  public Instant createdAtInstant() {
    return createdAt == null ? null : Instant.parse(createdAt);
  }
}
