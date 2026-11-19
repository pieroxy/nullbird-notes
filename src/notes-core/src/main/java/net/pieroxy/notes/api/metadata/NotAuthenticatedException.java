package net.pieroxy.notes.api.metadata;

/**
 * Thrown by {@link AbstractAuthenticatedEndpoint} when the caller's session ID isn't valid (never
 * presented one, or the server has restarted since — see SessionService). Special-cased by
 * {@link BaseApiEndpoint} into an HTTP 401, so the frontend can tell "you're not logged in" apart
 * from an ordinary business-logic error.
 */
public class NotAuthenticatedException extends RuntimeException {
  public NotAuthenticatedException(String message) {
    super(message);
  }
}
