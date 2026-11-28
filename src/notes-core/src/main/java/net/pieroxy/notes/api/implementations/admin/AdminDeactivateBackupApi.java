package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.BackupConfiguration;
import net.pieroxy.notes.services.BackupService;
import net.pieroxy.notes.services.IServiceProvider;

import java.util.logging.Logger;

/** Stops the backups: "backup" leaves config.json. The backups made stay where they are. */
@Endpoint(method = ApiMethod.POST)
public class AdminDeactivateBackupApi extends AbstractAdminEndpoint<AdminDeactivateBackupApiInput, AdminResult> {
  private final static Logger LOGGER = Logger.getLogger(AdminDeactivateBackupApi.class.getName());

  public AdminDeactivateBackupApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AdminResult processAdmin(AdminDeactivateBackupApiInput input) throws Exception {
    BackupService backups = serviceProvider.getBackupService();
    BackupConfiguration before = backups.status().config();
    if (before == null) return AdminResult.done();
    serviceProvider.getSettingsService().updateSavedConfig(json -> json.remove("backup"));
    backups.configure(null);
    LOGGER.info("Admin \"" + input.getUsername() + "\" from " + input.getClientAddress() + " deactivated the backups (were in "
        + AbstractUserChangeEndpoint.printable(before.getFolder()) + ")");
    return AdminResult.done();
  }
}

@TypeScriptType
class AdminDeactivateBackupApiInput extends AuthenticatedApiInput {
}
