package net.pieroxy.notes.config;

/**
 * A user's "secondFactor" in credentials.json: what's asked after the password. Absent: nothing.
 * A "totp" sibling of "mail" is kept as is, for the TOTP to come.
 */
public class SecondFactor {
  public static final String MAIL = "mail";
  public static final String TOTP = "totp";

  /** {@link #MAIL} or {@link #TOTP}: the one asked first when there are both. */
  private String preferred;
  private MailFactor mail;

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
