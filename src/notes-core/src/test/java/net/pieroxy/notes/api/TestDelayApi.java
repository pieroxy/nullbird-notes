package net.pieroxy.notes.api;

import net.pieroxy.notes.api.metadata.ApiContext;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.BaseApiEndpoint;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.InvalidInputException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Test only: answers {@code value} after {@code delayMillis}, or fails with a 400 if {@code fail}. */
@Endpoint(method = ApiMethod.GET)
public class TestDelayApi extends BaseApiEndpoint<TestDelayApi.Input, TestDelayApi.Output> {
  public static class Input {
    long delayMillis;
    String value;
    boolean fail;
  }

  public static class Output {
    String value;
    String thread;
  }

  @Override
  public CompletableFuture<Output> processAsync(Input input, ApiContext context) {
    Executor later = CompletableFuture.delayedExecutor(input.delayMillis, TimeUnit.MILLISECONDS);
    return CompletableFuture.supplyAsync(() -> {
      if (input.fail) throw new InvalidInputException("Failed on purpose.");
      Output output = new Output();
      output.value = input.value;
      output.thread = Thread.currentThread().getName();
      return output;
    }, later);
  }
}
