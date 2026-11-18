package net.pieroxy.notes.api.metadata;

/** Turned into an HTTP 400 by {@link AbstractApiEndpoint}: the client sent something malformed. */
public class InvalidInputException extends RuntimeException {
  public InvalidInputException(String message) {
    super(message);
  }
}
