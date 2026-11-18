package net.pieroxy.notes.api.metadata;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.pieroxy.notes.api.model.ApiResponse;
import net.pieroxy.notes.utils.reflection.GenericTypeArgumentResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Reader;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class AbstractApiEndpoint<I, O> implements ApiEndpoint {
  private final static Gson GSON = new Gson();

  private final Logger logger = Logger.getLogger(getClass().getName());
  private final Class<I> inputType;

  public AbstractApiEndpoint() {
    inputType = resolveInputType();
  }

  public abstract O process(I input, ApiContext context) throws Exception;

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
    ApiResponse<O> response;
    try {
      I input = readInput(req);
      response = ApiResponse.buildOkResult(process(input, new ServletApiContext(req, res)));
    } catch (NotAuthenticatedException e) {
      // Routine (a restart, or just an old tab): no SEVERE stack trace for it.
      res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response = ApiResponse.buildErrResult(e.getMessage());
    } catch (InvalidInputException e) {
      res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      response = ApiResponse.buildErrResult(e.getMessage());
    } catch (RequestTooLargeException e) {
      res.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      response = ApiResponse.buildErrResult(e.getMessage());
    } catch (TooManyRequestsException e) {
      res.setStatus(429);
      res.setHeader("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
      response = ApiResponse.buildErrResult(e.getMessage());
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Endpoint " + getClass().getSimpleName() + " failed", e);
      res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      response = ApiResponse.buildErrResult(e.getMessage());
    }
    writeResponse(res, response);
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

  /** See {@link GenericTypeArgumentResolver} for why a subclass one level down (e.g. {@link AbstractAuthenticatedEndpoint}) needs more than reading the leaf class's own type arguments. */
  @SuppressWarnings("unchecked")
  private Class<I> resolveInputType() {
    return (Class<I>) GenericTypeArgumentResolver.resolve(getClass(), AbstractApiEndpoint.class, 0);
  }
}
