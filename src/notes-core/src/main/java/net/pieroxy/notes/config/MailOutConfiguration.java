package net.pieroxy.notes.config;

/** config.json's "mailOut": the SMTP server Notes sends mail through. Read when sending: a change applies at once. */
public class MailOutConfiguration {
  public enum Security {
    /** TLS from the first byte, usually port 465. */
    TLS,
    /** Plain, then upgraded to TLS before anything else is sent, usually port 587. */
    STARTTLS,
    /** Plain throughout: only for a relay on the same machine or network. */
    NONE;

    public int defaultPort() {
      return switch (this) {
        case TLS -> 465;
        case STARTTLS -> 587;
        case NONE -> 25;
      };
    }
  }

  /** Blank: no mail is sent. */
  private String host;
  /** 0: the security's default port. */
  private int port;
  private Security security = Security.STARTTLS;
  /** Blank: no authentication. */
  private String username;
  private String password;
  private String from;

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public int getPort() {
    return port;
  }

  /** The port to connect to: {@link #port}, or the security's default if unset. */
  public int effectivePort() {
    return port == 0 && security != null ? security.defaultPort() : port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public Security getSecurity() {
    return security;
  }

  public void setSecurity(Security security) {
    this.security = security;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getFrom() {
    return from;
  }

  public void setFrom(String from) {
    this.from = from;
  }
}
