package net.pieroxy.notes.api.metadata;

/** Turned into an HTTP 413 by {@link BaseApiEndpoint}. Unchecked, so it isn't wrapped on its way out of a read. */
public class RequestTooLargeException extends RuntimeException {
  public RequestTooLargeException(long maxBytes) {
    super("Request too large: the limit is " + (maxBytes / 1024) + " KB.");
  }
}
