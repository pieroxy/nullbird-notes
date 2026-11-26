package net.pieroxy.notes.mail;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/** A plain-text mail to one recipient. */
public record Mail(String to, String subject, String body) {
  /** As MAIL FROM and RCPT TO carry them: no display name, nothing to quote. */
  public static final Pattern ADDRESS = Pattern.compile("[^\\s@<>()\",;:\\\\]+@[A-Za-z0-9.-]+\\.[A-Za-z0-9-]+");

  /** @throws IllegalArgumentException if a header would break: a bad address, a line break in the subject. */
  public Mail {
    if (to == null || to.length() > 254 || !ADDRESS.matcher(to).matches()) throw new IllegalArgumentException("Not an email address: " + to);
    if (subject == null || subject.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("The subject can't contain line breaks.");
    if (body == null) throw new IllegalArgumentException("No body.");
  }

  /**
   * The message as sent after DATA, before dot-stuffing: CRLF line ends, the subject encoded if not
   * ASCII, the body in base64, which no server alters.
   */
  String format(String from, ZonedDateTime date) {
    String domain = from.substring(from.indexOf('@') + 1);
    String body = Base64.getMimeEncoder().encodeToString(this.body.replace("\r\n", "\n").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
    return "Date: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(date) + "\r\n"
        + "From: " + from + "\r\n"
        + "To: " + to + "\r\n"
        + "Subject: " + encodedSubject() + "\r\n"
        + "Message-ID: <" + UUID.randomUUID() + "@" + domain + ">\r\n"
        + "MIME-Version: 1.0\r\n"
        + "Content-Type: text/plain; charset=UTF-8\r\n"
        + "Content-Transfer-Encoding: base64\r\n"
        + "\r\n"
        + body + "\r\n";
  }

  /** RFC 2047 when needed, in words short enough for any line limit. */
  private String encodedSubject() {
    if (subject.chars().allMatch(c -> c >= 0x20 && c < 0x7f)) return subject;
    StringBuilder out = new StringBuilder();
    int start = 0;
    while (start < subject.length()) {
      // 45 bytes encode to 60 characters: with the markers, under the 75 of an encoded word.
      int end = start;
      int bytes = 0;
      while (end < subject.length()) {
        int next = subject.offsetByCodePoints(end, 1);
        int size = subject.substring(end, next).getBytes(StandardCharsets.UTF_8).length;
        if (bytes + size > 45) break;
        bytes += size;
        end = next;
      }
      if (out.length() > 0) out.append("\r\n ");
      out.append("=?UTF-8?B?").append(Base64.getEncoder().encodeToString(subject.substring(start, end).getBytes(StandardCharsets.UTF_8))).append("?=");
      start = end;
    }
    return out.toString();
  }
}
