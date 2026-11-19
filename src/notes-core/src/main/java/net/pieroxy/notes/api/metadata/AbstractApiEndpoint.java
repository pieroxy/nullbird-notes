package net.pieroxy.notes.api.metadata;

import java.util.concurrent.CompletableFuture;

/** Base for an endpoint that answers on the request's own thread. */
public abstract class AbstractApiEndpoint<I, O> extends BaseApiEndpoint<I, O> {
  public abstract O process(I input, ApiContext context) throws Exception;

  @Override
  public final CompletableFuture<O> processAsync(I input, ApiContext context) throws Exception {
    return CompletableFuture.completedFuture(process(input, context));
  }
}
