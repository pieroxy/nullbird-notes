package net.pieroxy.notes.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.pieroxy.notes.api.metadata.ServletApiContext;
import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.config.CredentialsFile;
import net.pieroxy.notes.config.PasswordHasher;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.ClientGuardService;
import net.pieroxy.notes.services.ImportService;
import net.pieroxy.notes.services.NoteService;
import net.pieroxy.notes.services.NoteStoreService;
import net.pieroxy.notes.services.ServiceProvider;
import net.pieroxy.notes.services.SessionService;
import net.pieroxy.notes.services.SettingsService;
import net.pieroxy.notes.services.WebServerService;
import net.pieroxy.notes.utils.BuildInfo;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** The whole server on a free local port, for tests going through HTTP. */
class TestServer implements AutoCloseable {
  static {
    // catalina.home is JVM-wide: unset, the first Tomcat would set it to its own base dir, deleted
    // with its test while servers of tests running in parallel still use it.
    try {
      Path home = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "notes-tests-catalina-home"));
      System.setProperty("catalina.home", home.toString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private final ServiceProvider services;
  private final int port;

  /** @param users username to plaintext password; ignored if {@code dir} already has a credentials.json. */
  TestServer(File dir, Map<String, String> users) throws IOException {
    this(dir, users, Set.of(), freePort());
  }

  /** @param admins those of {@code users} who are admins. */
  TestServer(File dir, Map<String, String> users, Set<String> admins) throws IOException {
    this(dir, users, admins, freePort());
  }

  TestServer(File dir, Map<String, String> users, int port) throws IOException {
    this(dir, users, Set.of(), port);
  }

  TestServer(File dir, Map<String, String> users, Set<String> admins, int port) throws IOException {
    this.port = port;
    File credentialsPath = new File(dir, "credentials.json");
    // Kept on a restart in the same folder, as a real one does: hashed again, every password would
    // have a new salt, which ends the sessions.
    if (!credentialsPath.exists()) {
      List<UserCredential> credentials = new ArrayList<>();
      users.forEach((username, password) -> {
        UserCredential user = new UserCredential();
        user.setUsername(username);
        user.setPassword(PasswordHasher.hash(password));
        user.setAdmin(admins.contains(username));
        credentials.add(user);
      });
      CredentialsFile credentialsFile = new CredentialsFile();
      credentialsFile.setUsers(credentials);
      Files.writeString(credentialsPath.toPath(), new Gson().toJson(credentialsFile));
    }

    Configuration config = new Configuration();
    config.setHttpPort(port);
    config.setAddress("127.0.0.1");
    config.setMaxNoteSizeMb(1);
    // Plain HTTP: the client's CookieManager wouldn't send a secure cookie back.
    config.setSecureCookie(false);
    services = new ServiceProvider(new SettingsService(config, credentialsPath, new File(dir, "data")), new SessionService(),
        new ClientGuardService(), new NoteStoreService(), new NoteService(), new ImportService(), new WebServerService());
    try {
      services.init();
    } catch (RuntimeException e) {
      services.destroy();
      throw e;
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket probe = new ServerSocket(0)) {
      return probe.getLocalPort();
    }
  }

  /** The running services, to set up what the API can't, e.g. a note over the size limit. */
  ServiceProvider services() {
    return services;
  }

  /** A device: its own cookies, so its own session. */
  Client client() {
    return new Client(new CookieManager());
  }

  /** The same device (same cookies) talking to this server, e.g. after a restart. */
  Client client(Client device) {
    return new Client(device.cookies);
  }

  @Override
  public void close() {
    services.destroy();
  }

  record Response(int status, String body) {
    JsonObject json() {
      return JsonParser.parseString(body).getAsJsonObject();
    }

    /** The {@code result} of a successful call. */
    JsonObject result() {
      if (status != 200) throw new AssertionError("HTTP " + status + ": " + body);
      return json().getAsJsonObject("result");
    }
  }

  class Client {
    private final CookieManager cookies;
    private final HttpClient http;
    /** Null sends no build header. */
    private String build = BuildInfo.ID;

    private Client(CookieManager cookies) {
      this.cookies = cookies;
      http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    Client login(String username, String password) throws Exception {
      JsonObject input = new JsonObject();
      input.addProperty("login", username);
      input.addProperty("password", password);
      if (!post("Login", input.toString()).result().get("ok").getAsBoolean()) throw new AssertionError("Login refused");
      return this;
    }

    /** Calls as a web app of another build would; null for none at all. */
    Client asBuild(String build) {
      this.build = build;
      return this;
    }

    /** @return the session cookie's value, null if none. */
    String sessionCookie() {
      return cookies.getCookieStore().getCookies().stream()
          .filter(c -> c.getName().equals(ServletApiContext.SESSION_COOKIE)).map(HttpCookie::getValue).findFirst().orElse(null);
    }

    /** Sends {@code value} as the session cookie from now on, as a client ignoring the server's cookies would. */
    void setSessionCookie(String value) {
      HttpCookie cookie = new HttpCookie(ServletApiContext.SESSION_COOKIE, value);
      cookie.setPath("/");
      cookie.setVersion(0);
      cookies.getCookieStore().add(URI.create(base()), cookie);
    }

    Response get(String endpoint, String input) throws Exception {
      return getAsync(endpoint, input).get();
    }

    CompletableFuture<Response> getAsync(String endpoint, String input) {
      URI uri = URI.create(base() + endpoint + "?input=" + URLEncoder.encode(input, StandardCharsets.UTF_8));
      return http.sendAsync(withBuild(HttpRequest.newBuilder(uri)).build(), HttpResponse.BodyHandlers.ofString())
          .thenApply(r -> new Response(r.statusCode(), r.body()));
    }

    Response post(String endpoint, String input) throws Exception {
      HttpRequest request = withBuild(HttpRequest.newBuilder(URI.create(base() + endpoint)))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(input))
          .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body());
    }

    /** Any path, e.g. a static file: the raw response, headers included. */
    HttpResponse<String> raw(String path) throws Exception {
      return http.send(withBuild(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))).build(),
          HttpResponse.BodyHandlers.ofString());
    }

    /** {@link #raw}, as relayed by a reverse proxy for {@code forwardedFor}. */
    HttpResponse<String> rawForwardedFor(String path, String forwardedFor) throws Exception {
      return http.send(withBuild(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))).header("X-Forwarded-For", forwardedFor)
          .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** POSTs {@code body} as is to {@code path}, e.g. a file to /import. */
    Response upload(String path, byte[] body) throws Exception {
      HttpRequest request = withBuild(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)))
          .header("Content-Type", "application/octet-stream")
          .POST(HttpRequest.BodyPublishers.ofByteArray(body))
          .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body());
    }

    /** {@link #raw}, for a binary response: a download. */
    HttpResponse<byte[]> rawBytes(String path) throws Exception {
      return http.send(withBuild(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))).build(),
          HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest.Builder withBuild(HttpRequest.Builder builder) {
      return build == null ? builder : builder.header(ApiServlet.BUILD_HEADER, build);
    }

    private String base() {
      return "http://127.0.0.1:" + port + "/api/";
    }
  }
}
