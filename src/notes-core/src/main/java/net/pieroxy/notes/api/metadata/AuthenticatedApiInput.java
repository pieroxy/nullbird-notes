package net.pieroxy.notes.api.metadata;

/**
 * Base for every authenticated endpoint's input. Filled by {@link AbstractAuthenticatedEndpoint}
 * once the session cookie is validated; {@code transient} so that Gson never reads them from the
 * request, and the TypeScript generator leaves them out.
 */
public class AuthenticatedApiInput {
  private transient String sessionId;
  private transient String username;

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }
}
