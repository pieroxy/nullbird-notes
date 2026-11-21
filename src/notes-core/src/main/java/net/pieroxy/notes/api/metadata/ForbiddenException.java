package net.pieroxy.notes.api.metadata;

/** Turned into an HTTP 403 by {@link BaseApiEndpoint}: logged in, but not allowed to do that. */
public class ForbiddenException extends RuntimeException {
  public ForbiddenException(String message) {
    super(message);
  }
}
