package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.BackupService;
import net.pieroxy.notes.services.IServiceProvider;

import java.util.logging.Logger;

/** Makes a backup now, in the background, changed or not (see AdminBackup for how it went). */
@Endpoint(method = ApiMethod.POST)
public class AdminBackupNowApi extends AbstractAdminEndpoint<AdminBackupNowApiInput, AdminResult> {
  private final static Logger LOGGER = Logger.getLogger(AdminBackupNowApi.class.getName());

  public AdminBackupNowApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AdminResult processAdmin(AdminBackupNowApiInput input) throws Exception {
    BackupService backups = serviceProvider.getBackupService();
    if (backups.status().config() == null) return AdminResult.refused("The backups aren't active.");
    LOGGER.info("Admin \"" + input.getUsername() + "\" from " + input.getClientAddress() + " asked for a backup");
    backups.backUpNow();
    return AdminResult.done();
  }
}

@TypeScriptType
class AdminBackupNowApiInput extends AuthenticatedApiInput {
}
