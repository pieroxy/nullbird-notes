package net.pieroxy.notes.api;

import net.pieroxy.notes.api.metadata.ApiContext;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.BaseApiEndpoint;
import net.pieroxy.notes.api.metadata.Endpoint;

import java.util.concurrent.CompletableFuture;

/** Test only: never answers, to hit the async timeout. */
@Endpoint(method = ApiMethod.GET)
public class TestHangApi extends BaseApiEndpoint<TestHangApi.Input, Object> {
  public static class Input {
  }

  @Override
  public CompletableFuture<Object> processAsync(Input input, ApiContext context) {
    return new CompletableFuture<>();
  }

  @Override
  protected long asyncTimeoutMillis() {
    return 300;
  }
}
