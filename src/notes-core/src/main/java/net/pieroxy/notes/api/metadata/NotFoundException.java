package net.pieroxy.notes.api.metadata;

/** Turned into an HTTP 404 by {@link BaseApiEndpoint}. */
public class NotFoundException extends RuntimeException {
  public NotFoundException(String message) {
    super(message);
  }
}
