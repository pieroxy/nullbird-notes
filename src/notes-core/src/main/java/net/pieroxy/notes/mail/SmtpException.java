package net.pieroxy.notes.mail;

import java.io.IOException;

/** The SMTP server refused a step, or broke the protocol. The message is for the admin. */
public class SmtpException extends IOException {
  public SmtpException(String message) {
    super(message);
  }

  public SmtpException(String message, Throwable cause) {
    super(message, cause);
  }
}
