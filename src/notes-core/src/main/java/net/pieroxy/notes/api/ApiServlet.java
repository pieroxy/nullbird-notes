package net.pieroxy.notes.api;

import com.google.gson.Gson;
import net.pieroxy.notes.api.metadata.ApiEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.model.ApiResponse;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.utils.BuildInfo;
import net.pieroxy.notes.utils.reflection.GetAccessibleClasses;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Discovers {@link Endpoint}-annotated classes under net.pieroxy.notes and routes /api/&lt;Name&gt; to them.
 * <p>
 * Every call must carry the {@link #BUILD_HEADER} of the web app's build, which must be this
 * server's: a page left open across an upgrade is refused (HTTP 409) until it's reloaded, so that
 * it can't apply the previous version's rules.
 */
public class ApiServlet extends HttpServlet {
  private final static Logger LOGGER = Logger.getLogger(ApiServlet.class.getName());
  private final static String API_PATH_PREFIX = "/api/";
  private final static String ENDPOINT_CLASS_SUFFIX = "Api";
  public final static String BUILD_HEADER = "X-Notes-Build";

  private final IServiceProvider serviceProvider;
  private Map<String, ApiEndpoint> endpoints;

  public ApiServlet(IServiceProvider serviceProvider) {
    this.serviceProvider = serviceProvider;
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
    dispatch(req, resp, ApiMethod.GET);
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
    dispatch(req, resp, ApiMethod.POST);
  }

  private void dispatch(HttpServletRequest req, HttpServletResponse resp, ApiMethod methodCalled) {
    String uri = req.getRequestURI();
    String className = uri.substring(API_PATH_PREFIX.length()) + ENDPOINT_CLASS_SUFFIX;
    ApiEndpoint endpoint = getOrDiscoverEndpoints().get(className);
    if (endpoint == null) {
      LOGGER.warning("No API endpoint found for " + uri);
      resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    Endpoint metadata = endpoint.getClass().getAnnotation(Endpoint.class);
    if (metadata.method() != methodCalled) {
      LOGGER.warning("Endpoint " + uri + " is not accessible through " + methodCalled);
      resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    if (!BuildInfo.ID.equals(req.getHeader(BUILD_HEADER))) {
      LOGGER.fine("Call to " + uri + " from build " + req.getHeader(BUILD_HEADER) + " refused: this is " + BuildInfo.ID);
      resp.setStatus(HttpServletResponse.SC_CONFLICT);
      resp.setContentType("application/json");
      resp.setCharacterEncoding("UTF-8");
      try {
        resp.getWriter().write(new Gson().toJson(ApiResponse.buildErrResult("This page is out of date: reload it to get the new version.")));
      } catch (IOException e) {
        LOGGER.warning("Could not answer " + uri + ": " + e.getMessage());
      }
      return;
    }
    endpoint.process(new SizeLimitedRequest(req, serviceProvider.getSettingsService().getMaxRequestBytes()), resp);
  }

  private synchronized Map<String, ApiEndpoint> getOrDiscoverEndpoints() {
    if (endpoints == null) {
      endpoints = discoverEndpoints();
    }
    return endpoints;
  }

  private Map<String, ApiEndpoint> discoverEndpoints() {
    Map<String, ApiEndpoint> discovered = new HashMap<>();
    try {
      List<Class<?>> classes = GetAccessibleClasses.getClasses("net.pieroxy.notes");
      for (Class<?> c : classes) {
        if (ApiEndpoint.class.isAssignableFrom(c) && c.isAnnotationPresent(Endpoint.class)) {
          discovered.put(c.getSimpleName(), instantiate(c));
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("Could not discover API endpoints", e);
    }
    LOGGER.info("API endpoints found: " + discovered.keySet());
    return discovered;
  }

  private ApiEndpoint instantiate(Class<?> c) throws Exception {
    for (Constructor<?> constructor : c.getConstructors()) {
      if (constructor.getParameterCount() == 0) {
        return (ApiEndpoint) constructor.newInstance();
      }
      if (constructor.getParameterCount() == 1 && constructor.getParameterTypes()[0] == IServiceProvider.class) {
        return (ApiEndpoint) constructor.newInstance(serviceProvider);
      }
    }
    throw new IllegalStateException("Could not instantiate API endpoint " + c.getName()
        + ": no no-arg or (IServiceProvider) constructor.");
  }
}
