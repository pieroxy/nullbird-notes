package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.SessionService;

import java.time.Instant;
import java.util.List;

/** Every user, by username. */
@Endpoint(method = ApiMethod.GET)
public class AdminUsersApi extends AbstractAdminEndpoint<AdminUsersApiInput, AdminUsersApiOutput> {
  public AdminUsersApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AdminUsersApiOutput processAdmin(AdminUsersApiInput input) {
    SessionService sessions = serviceProvider.getSessionService();
    List<AdminUser> users = serviceProvider.getSettingsService().listUsers().stream()
        .map(user -> new AdminUser(user.getUsername(), user.getEmail(), user.isAdmin(), millis(user.createdAtInstant()),
            millis(sessions.lastSeen(user.getUsername()))))
        .toList();
    return new AdminUsersApiOutput(users);
  }

  private static Long millis(Instant instant) {
    return instant == null ? null : instant.toEpochMilli();
  }
}

@TypeScriptType
class AdminUsersApiInput extends AuthenticatedApiInput {
}

@TypeScriptType
class AdminUsersApiOutput {
  @Required
  private List<AdminUser> users;

  public AdminUsersApiOutput() {
  }

  public AdminUsersApiOutput(List<AdminUser> users) {
    this.users = users;
  }

  public List<AdminUser> getUsers() {
    return users;
  }

  public void setUsers(List<AdminUser> users) {
    this.users = users;
  }
}
