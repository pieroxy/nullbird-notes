package net.pieroxy.notes.mail;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** An SMTP server for one connection, answering as told; records the commands and the message. */
public class FakeSmtpServer implements AutoCloseable {
  /** Encrypt from the first byte. */
  public boolean implicitTls;
  public boolean offerStartTls;
  public String authMechanisms = "PLAIN LOGIN";
  /** Replies by command verb (EHLO, MAIL, RCPT…), instead of the usual ones. */
  public final Map<String, String> replies = new ConcurrentHashMap<>();
  public final List<String> commands = new CopyOnWriteArrayList<>();
  public volatile String message;
  public volatile Throwable failure;

  private final ServerSocket server;
  private final SSLContext tls;
  private final Thread thread;

  /** @param tls for STARTTLS or implicit TLS; null if neither is used. */
  public FakeSmtpServer(SSLContext tls) throws IOException {
    this.tls = tls;
    this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    this.thread = new Thread(this::serve, "fake-smtp");
  }

  public int port() {
    return server.getLocalPort();
  }

  public FakeSmtpServer start() {
    thread.start();
    return this;
  }

  private void serve() {
    try (Socket accepted = server.accept()) {
      Socket socket = implicitTls ? upgrade(accepted) : accepted;
      boolean encrypted = implicitTls;
      BufferedReader in = reader(socket);
      OutputStream out = socket.getOutputStream();
      send(out, "220 fake ready");
      String line;
      // AUTH LOGIN's two answers, which aren't commands: 1 for the user name, 2 for the password.
      int loginStep = 0;
      while ((line = in.readLine()) != null) {
        commands.add(line);
        if (loginStep > 0) {
          send(out, loginStep == 1 ? "334 UGFzc3dvcmQ6" : replies.getOrDefault("AUTH", "235 ok"));
          loginStep = loginStep == 1 ? 2 : 0;
          continue;
        }
        String verb = line.split("[ :]", 2)[0].toUpperCase();
        String reply = verb.equals("AUTH") && line.startsWith("AUTH LOGIN") ? null : replies.get(verb);
        if (reply != null) {
          send(out, reply);
          if (reply.startsWith("4") || reply.startsWith("5")) continue;
          if (!verb.equals("DATA")) continue;
        }
        switch (verb) {
          case "EHLO" -> send(out, "250-fake\r\n" + (offerStartTls && !encrypted ? "250-STARTTLS\r\n" : "")
              + (authMechanisms.isEmpty() ? "" : "250-AUTH " + authMechanisms + "\r\n") + "250 8BITMIME");
          case "STARTTLS" -> {
            send(out, "220 go ahead");
            socket = upgrade(socket);
            encrypted = true;
            in = reader(socket);
            out = socket.getOutputStream();
          }
          case "AUTH" -> {
            send(out, line.startsWith("AUTH LOGIN") ? "334 VXNlcm5hbWU6" : "235 ok");
            if (line.startsWith("AUTH LOGIN")) loginStep = 1;
          }
          case "DATA" -> {
            if (reply == null) send(out, "354 go");
            StringBuilder data = new StringBuilder();
            String dataLine;
            while ((dataLine = in.readLine()) != null && !dataLine.equals(".")) data.append(dataLine).append("\r\n");
            message = data.toString();
            send(out, "250 queued");
          }
          case "QUIT" -> {
            send(out, "221 bye");
            return;
          }
          default -> send(out, "250 ok");
        }
      }
    } catch (Throwable t) {
      failure = t;
    }
  }

  private Socket upgrade(Socket plain) throws IOException {
    SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket(plain, null, plain.getPort(), true);
    socket.setUseClientMode(false);
    socket.startHandshake();
    return socket;
  }

  private static BufferedReader reader(Socket socket) throws IOException {
    return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
  }

  private static void send(OutputStream out, String reply) throws IOException {
    out.write((reply + "\r\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  @Override
  public void close() throws Exception {
    server.close();
    thread.join(5000);
  }
}
