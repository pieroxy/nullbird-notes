package net.pieroxy.notes.mail;

import net.pieroxy.notes.config.MailOutConfiguration;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Sends a mail through the configured SMTP server: one connection per mail, closed after. TLS
 * certificates are checked against the host name, as a browser would. Supports AUTH PLAIN and
 * LOGIN, which the connection's encryption protects.
 */
public class SmtpClient {
  static final int CONNECT_TIMEOUT_MILLIS = 10_000;
  /** Per reply: servers may delay theirs to slow down spammers. */
  static final int READ_TIMEOUT_MILLIS = 30_000;

  private final SSLSocketFactory tls;

  public SmtpClient() {
    this((SSLSocketFactory) SSLSocketFactory.getDefault());
  }

  /** @param tls trusting what the tests need. */
  SmtpClient(SSLSocketFactory tls) {
    this.tls = tls;
  }

  /**
   * @throws SmtpException if the server refused a step: its reply says why.
   * @throws IOException   if it couldn't be reached, or the connection failed.
   */
  public void send(MailOutConfiguration config, Mail mail) throws IOException {
    // An IPv6 address may come bracketed, as in a URL.
    String host = config.getHost().trim().replaceAll("^\\[(.*)]$", "$1");
    int port = config.effectivePort();
    Socket socket = new Socket();
    try {
      socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
      socket.setSoTimeout(READ_TIMEOUT_MILLIS);
      if (config.getSecurity() == MailOutConfiguration.Security.TLS) socket = encrypt(socket, host, port);
      Session session = new Session(socket);
      session.expect(null, 220);
      List<String> capabilities = session.ehlo(helloName(socket));
      if (config.getSecurity() == MailOutConfiguration.Security.STARTTLS) {
        if (!capabilities.contains("STARTTLS")) throw new SmtpException("The server doesn't offer STARTTLS: choose TLS or another port.");
        session.expect("STARTTLS", 220);
        socket = encrypt(socket, host, port);
        session = new Session(socket);
        capabilities = session.ehlo(helloName(socket));
      }
      String username = config.getUsername() == null ? "" : config.getUsername().trim();
      if (!username.isEmpty()) authenticate(session, capabilities, username, config.getPassword());
      String from = config.getFrom().trim();
      session.expect("MAIL FROM:<" + from + ">", 250);
      session.expect("RCPT TO:<" + mail.to() + ">", 250, 251);
      session.expect("DATA", 354);
      session.data(mail.format(from, ZonedDateTime.now()));
      session.quit();
    } finally {
      socket.close();
    }
  }

  private Socket encrypt(Socket plain, String host, int port) throws IOException {
    SSLSocket socket = (SSLSocket) tls.createSocket(plain, host, port, true);
    SSLParameters parameters = socket.getSSLParameters();
    parameters.setEndpointIdentificationAlgorithm("HTTPS");
    socket.setSSLParameters(parameters);
    try {
      socket.startHandshake();
    } catch (IOException e) {
      throw new SmtpException("The TLS connection to " + host + " failed: " + e.getMessage(), e);
    }
    return socket;
  }

  /** The connection's own address: no reverse DNS to wait for, and valid for any server. */
  private static String helloName(Socket socket) {
    var address = socket.getLocalAddress();
    return address instanceof Inet6Address ? "[IPv6:" + address.getHostAddress().replaceAll("%.*", "") + "]" : "[" + address.getHostAddress() + "]";
  }

  private static void authenticate(Session session, List<String> capabilities, String username, String password) throws IOException {
    List<String> mechanisms = capabilities.stream().filter(c -> c.startsWith("AUTH ") || c.startsWith("AUTH="))
        .flatMap(c -> List.of(c.substring(5).split(" ")).stream()).toList();
    Base64.Encoder base64 = Base64.getEncoder();
    if (mechanisms.contains("PLAIN")) {
      String credentials = "\0" + username + "\0" + password;
      session.expect("AUTH PLAIN " + base64.encodeToString(credentials.getBytes(StandardCharsets.UTF_8)), "AUTH PLAIN ***", 235);
    } else if (mechanisms.contains("LOGIN")) {
      session.expect("AUTH LOGIN", 334);
      session.expect(base64.encodeToString(username.getBytes(StandardCharsets.UTF_8)), "(user name)", 334);
      session.expect(base64.encodeToString(password.getBytes(StandardCharsets.UTF_8)), "(password)", 235);
    } else {
      throw new SmtpException(mechanisms.isEmpty()
          ? "The server doesn't offer authentication here: it may want TLS or STARTTLS first, or no user name."
          : "The server offers no supported authentication (PLAIN or LOGIN), only " + String.join(", ", mechanisms) + ".");
    }
  }

  /** One SMTP dialog over one socket: replenished after STARTTLS. */
  private static class Session {
    private final BufferedReader in;
    private final OutputStream out;

    Session(Socket socket) throws IOException {
      this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      this.out = socket.getOutputStream();
    }

    /** @return the capabilities, upper case, the greeting line aside. */
    List<String> ehlo(String name) throws IOException {
      List<String> lines = expect("EHLO " + name, 250);
      List<String> capabilities = new ArrayList<>();
      for (int i = 1; i < lines.size(); i++) capabilities.add(lines.get(i).toUpperCase(Locale.ROOT).trim());
      return capabilities;
    }

    List<String> expect(String command, int... codes) throws IOException {
      return expect(command, command, codes);
    }

    /**
     * Sends {@code command} (none: just reads), then reads the reply.
     *
     * @param shown {@code command} as errors may show it: never a secret.
     * @return the reply's lines, codes stripped.
     */
    List<String> expect(String command, String shown, int... codes) throws IOException {
      if (command != null) write(command + "\r\n");
      List<String> lines = new ArrayList<>();
      int code;
      while (true) {
        String line = in.readLine();
        if (line == null) throw new SmtpException("The server closed the connection" + (shown == null ? "" : " after " + shown) + ".");
        if (line.length() < 3 || !line.substring(0, 3).chars().allMatch(Character::isDigit)) throw new SmtpException("Not an SMTP reply: " + line);
        code = Integer.parseInt(line.substring(0, 3));
        lines.add(line.length() > 4 ? line.substring(4) : "");
        if (line.length() == 3 || line.charAt(3) != '-') break;
      }
      for (int expected : codes) if (code == expected) return lines;
      throw new SmtpException((shown == null ? "The server's greeting" : shown) + " was refused: " + code + " " + String.join(" ", lines));
    }

    /** The message, dot-stuffed, then its end. */
    void data(String message) throws IOException {
      StringBuilder stuffed = new StringBuilder();
      for (String line : message.split("\r\n", -1)) stuffed.append(line.startsWith(".") ? "." : "").append(line).append("\r\n");
      stuffed.setLength(stuffed.length() - 2);
      write(stuffed + ".\r\n");
      expect(null, "the message", 250);
    }

    /** The mail is accepted already: a failure here doesn't matter. */
    void quit() {
      try {
        write("QUIT\r\n");
        in.readLine();
      } catch (IOException e) {
        // Accepted already.
      }
    }

    private void write(String text) throws IOException {
      out.write(text.getBytes(StandardCharsets.UTF_8));
      out.flush();
    }
  }
}
