package net.pieroxy.notes.config;

/** A user's "secondFactor" in credentials.json: what's asked after the password. Absent: nothing. */
public class SecondFactor {
  public static final String MAIL = "mail";
  public static final String TOTP = "totp";

  /** {@link #MAIL} or {@link #TOTP}: the one asked first when there are both. */
  private String preferred;
  private MailFactor mail;
  private TotpFactor totp;

  public String getPreferred() {
    return preferred;
  }

  public void setPreferred(String preferred) {
    this.preferred = preferred;
  }

  public MailFactor getMail() {
    return mail;
  }

  public void setMail(MailFactor mail) {
    this.mail = mail;
  }

  public TotpFactor getTotp() {
    return totp;
  }

  public void setTotp(TotpFactor totp) {
    this.totp = totp;
  }

  /** Codes from an authenticator app, which the user proved set up with one. */
  public static class TotpFactor {
    /** Base32. */
    private String secret;
    /** ISO 8601. */
    private String confirmedAt;
    /** The step of the last code accepted: no code of it or before is accepted again. */
    private long lastUsedStep;

    public String getSecret() {
      return secret;
    }

    public void setSecret(String secret) {
      this.secret = secret;
    }

    public String getConfirmedAt() {
      return confirmedAt;
    }

    public void setConfirmedAt(String confirmedAt) {
      this.confirmedAt = confirmedAt;
    }

    public long getLastUsedStep() {
      return lastUsedStep;
    }

    public void setLastUsedStep(long lastUsedStep) {
      this.lastUsedStep = lastUsedStep;
    }
  }

  /** Codes sent to {@link #address}, which the user proved theirs with one. */
  public static class MailFactor {
    private String address;
    /** ISO 8601. */
    private String confirmedAt;

    public String getAddress() {
      return address;
    }

    public void setAddress(String address) {
      this.address = address;
    }

    public String getConfirmedAt() {
      return confirmedAt;
    }

    public void setConfirmedAt(String confirmedAt) {
      this.confirmedAt = confirmedAt;
    }
  }
}
