package net.pieroxy.notes.config;

import net.pieroxy.notes.utils.logging.LogRetention;
import net.pieroxy.notes.webserver.TrustedProxies;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;

/**
 * Checks config.json's values before an admin's change is written: one the server couldn't start
 * with is refused, rather than found at the next restart.
 */
public final class ConfigValidation {
  private ConfigValidation() {}

  /** @throws IllegalArgumentException saying, for the admin, what's wrong with the first bad value. */
  public static void validate(Configuration config) {
    if (config.getHttpPort() < 1 || config.getHttpPort() > 65535) {
      throw new IllegalArgumentException("The HTTP port must be between 1 and 65535.");
    }
    validateAddress(config.getAddress());
    TrustedProxies.parse(config.getTrustedProxies());
    LogRetention.validate(config.getKeepLogFiles());
    if (config.getMaxNoteSizeMb() < 1) throw new IllegalArgumentException("The largest note must be at least 1 MB.");
  }

  /**
   * Nothing to check without a host: no mail is sent. Values are checked as an SMTP command line
   * would carry them: no line breaks.
   *
   * @throws IllegalArgumentException saying, for the admin, what's wrong with the first bad value.
   */
  public static void validate(MailOutConfiguration mail) {
    String host = mail.getHost() == null ? "" : mail.getHost();
    if (host.isBlank()) return;
    boolean name = host.matches("[A-Za-z0-9.-]{1,253}");
    boolean ipv6 = host.matches("\\[?[0-9A-Fa-f.]*:[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*]?");
    if (!name && !ipv6) {
      throw new IllegalArgumentException("The SMTP server must be a host name or an IP address, without a port or a protocol.");
    }
    if (mail.getSecurity() == null) throw new IllegalArgumentException("The connection security must be TLS, STARTTLS or none.");
    if (mail.effectivePort() < 1 || mail.effectivePort() > 65535) throw new IllegalArgumentException("The SMTP port must be between 1 and 65535.");
    String from = mail.getFrom() == null ? "" : mail.getFrom();
    if (from.length() > 254 || !from.matches("[^\\s@<>()\",;:\\\\]+@[A-Za-z0-9.-]+\\.[A-Za-z0-9-]+")) {
      throw new IllegalArgumentException("The sender must be an email address, like notes@example.com.");
    }
    String username = mail.getUsername() == null ? "" : mail.getUsername();
    if (username.isBlank()) return;
    if (username.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("The user name can't contain line breaks or control characters.");
    if (mail.getPassword() == null || mail.getPassword().isEmpty()) throw new IllegalArgumentException("A user name needs a password.");
    if (mail.getPassword().chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("The password can't contain line breaks or control characters.");
  }

  /** Blank (all interfaces), or an address of one of this machine's interfaces: the server couldn't listen on another. */
  private static void validateAddress(String address) {
    if (address == null || address.isBlank()) return;
    InetAddress resolved;
    try {
      resolved = InetAddress.getByName(address.trim());
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("The address \"" + address + "\" isn't an IP address or a known host name.");
    }
    try {
      if (resolved.isAnyLocalAddress() || NetworkInterface.getByInetAddress(resolved) != null) return;
    } catch (SocketException e) {
      throw new IllegalArgumentException("The network interfaces of this machine couldn't be listed: " + e.getMessage());
    }
    throw new IllegalArgumentException("The address " + address + " isn't one of this machine's: the server couldn't listen on it.");
  }
}
