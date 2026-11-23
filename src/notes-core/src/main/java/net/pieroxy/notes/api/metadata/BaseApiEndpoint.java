package net.pieroxy.notes.api.metadata;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import net.pieroxy.notes.api.model.ApiResponse;
import net.pieroxy.notes.utils.reflection.GenericTypeArgumentResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Reader;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Root of every endpoint: reads the input, runs {@link #processAsync}, writes the response. A
 * future already done is answered right away; otherwise the request goes asynchronous and holds no
 * thread until the future completes. The response is then written on a container thread, never on
 * whichever thread completed the future. Past {@link #asyncTimeoutMillis()} the request is
 * answered with a 503, whatever the future does later.
 */
public abstract class BaseApiEndpoint<I, O> implements ApiEndpoint {
  private final static Gson GSON = new Gson();
  private final static long ASYNC_TIMEOUT_MILLIS = 60_000;

  private final Logger logger = Logger.getLogger(getClass().getName());
  private final Class<I> inputType;

  public BaseApiEndpoint() {
    inputType = resolveInputType();
  }

  public abstract CompletableFuture<O> processAsync(I input, ApiContext context) throws Exception;

  /** A safety net: an endpoint that waits on purpose should complete its future well before. */
  protected long asyncTimeoutMillis() {
    return ASYNC_TIMEOUT_MILLIS;
  }

  @Override
  public final void process(HttpServletRequest req, HttpServletResponse res) {
    res.setContentType("application/json;charset=UTF-8");
    // A cross-site page can't send this content type without a CORS preflight, which is never
    // granted: together with the SameSite session cookie, this blocks CSRF.
    if ("POST".equals(req.getMethod()) && !isJson(req.getContentType())) {
      res.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
      writeResponse(res, ApiResponse.buildErrResult("Content-Type must be application/json."));
      return;
    }
    CompletableFuture<O> future;
    try {
      future = processAsync(readInput(req), new ServletApiContext(req, res));
    } catch (Exception e) {
      future = CompletableFuture.failedFuture(e);
    }
    if (future.isDone()) {
      respond(res, future);
      return;
    }

    AsyncContext async = req.startAsync();
    async.setTimeout(asyncTimeoutMillis());
    AtomicBoolean responded = new AtomicBoolean();
    async.addListener(new AsyncListener() {
      @Override
      public void onTimeout(AsyncEvent event) {
        if (!responded.compareAndSet(false, true)) return;
        logger.warning("Endpoint " + getClass().getSimpleName() + " timed out after " + asyncTimeoutMillis() + " ms");
        res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        writeResponse(res, ApiResponse.buildErrResult("The server took too long to answer."));
        async.complete();
      }

      @Override
      public void onError(AsyncEvent event) {
        responded.set(true);
      }

      @Override
      public void onComplete(AsyncEvent event) {
      }

      @Override
      public void onStartAsync(AsyncEvent event) {
      }
    });
    CompletableFuture<O> pending = future;
    future.whenCompleteAsync((result, error) -> {
      if (!responded.compareAndSet(false, true)) return;
      try {
        respond(res, pending);
      } finally {
        async.complete();
      }
    }, async::start);
  }

  private void respond(HttpServletResponse res, CompletableFuture<O> future) {
    ApiResponse<O> response;
    try {
      response = ApiResponse.buildOkResult(future.join());
    } catch (CompletionException e) {
      response = errorResponse(res, e.getCause() != null ? e.getCause() : e);
    } catch (RuntimeException e) {
      response = errorResponse(res, e);
    }
    writeResponse(res, response);
  }

  private ApiResponse<O> errorResponse(HttpServletResponse res, Throwable error) {
    if (error instanceof NotAuthenticatedException) {
      // Routine (a restart, or just an old tab): no SEVERE stack trace for it.
      res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    } else if (error instanceof ForbiddenException) {
      res.setStatus(HttpServletResponse.SC_FORBIDDEN);
    } else if (error instanceof InvalidInputException) {
      res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    } else if (error instanceof NotFoundException) {
      res.setStatus(HttpServletResponse.SC_NOT_FOUND);
    } else if (error instanceof RequestTooLargeException) {
      res.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    } else if (error instanceof TooManyRequestsException tooMany) {
      res.setStatus(429);
      res.setHeader("Retry-After", String.valueOf(tooMany.getRetryAfterSeconds()));
    } else {
      logger.log(Level.SEVERE, "Endpoint " + getClass().getSimpleName() + " failed", error);
      res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
    return ApiResponse.buildErrResult(error.getMessage());
  }

  private static boolean isJson(String contentType) {
    return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/json");
  }

  /**
   * A missing or empty body reads as {@code {}}, so an endpoint never gets a null input. The body
   * is read in full before parsing so that a {@link RequestTooLargeException} isn't wrapped by Gson.
   *
   * @throws InvalidInputException if it isn't JSON, or lacks a {@link Required} field.
   */
  private I readInput(HttpServletRequest req) throws IOException {
    String raw = "GET".equals(req.getMethod()) ? req.getParameter("input") : readBody(req);
    JsonElement json;
    try {
      json = raw == null || raw.isBlank() ? new JsonObject() : JsonParser.parseString(raw);
    } catch (JsonParseException e) {
      throw new InvalidInputException("Input is not valid JSON.");
    }
    RequiredFields.check(json, inputType);
    try {
      return GSON.fromJson(json, inputType);
    } catch (JsonParseException e) {
      throw new InvalidInputException("Input doesn't match " + inputType.getSimpleName() + ": " + e.getMessage());
    }
  }

  private static String readBody(HttpServletRequest req) throws IOException {
    StringBuilder body = new StringBuilder();
    char[] buffer = new char[8192];
    Reader reader = req.getReader();
    for (int n; (n = reader.read(buffer)) >= 0; ) body.append(buffer, 0, n);
    return body.toString();
  }

  private void writeResponse(HttpServletResponse res, ApiResponse<O> response) {
    try {
      res.getWriter().write(GSON.toJson(response));
    } catch (IOException e) {
      logger.log(Level.SEVERE, "Could not write response for " + getClass().getSimpleName(), e);
    }
  }

  /** See {@link GenericTypeArgumentResolver} for why a subclass a few levels down needs more than reading the leaf class's own type arguments. */
  @SuppressWarnings("unchecked")
  private Class<I> resolveInputType() {
    return (Class<I>) GenericTypeArgumentResolver.resolve(getClass(), BaseApiEndpoint.class, 0);
  }
}
