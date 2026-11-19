package net.pieroxy.notes.api.metadata;

import net.pieroxy.notes.services.IServiceProvider;

import java.util.concurrent.CompletableFuture;

/** {@link AbstractAuthenticatedEndpoint}'s counterpart for an endpoint that may answer later (see {@link BaseApiEndpoint}). */
public abstract class AbstractAsyncAuthenticatedEndpoint<I extends AuthenticatedApiInput, O> extends BaseApiEndpoint<I, O> {
  protected final IServiceProvider serviceProvider;

  protected AbstractAsyncAuthenticatedEndpoint(IServiceProvider serviceProvider) {
    this.serviceProvider = serviceProvider;
  }

  @Override
  public final CompletableFuture<O> processAsync(I input, ApiContext context) throws Exception {
    AbstractAuthenticatedEndpoint.authenticate(serviceProvider, input, context);
    return processAuthenticatedAsync(input);
  }

  public abstract CompletableFuture<O> processAuthenticatedAsync(I input) throws Exception;
}
