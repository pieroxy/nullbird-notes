package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.BackupConfiguration;
import net.pieroxy.notes.services.BackupService;
import net.pieroxy.notes.services.IServiceProvider;

/** The backups: their settings, as in effect (saved to config.json), and how they went. */
@Endpoint(method = ApiMethod.GET)
public class AdminBackupApi extends AbstractAdminEndpoint<AdminBackupApiInput, AdminBackup> {
  public AdminBackupApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AdminBackup processAdmin(AdminBackupApiInput input) throws Exception {
    return AdminBackup.of(serviceProvider.getBackupService().status());
  }
}

/**
 * Times in milliseconds since the epoch.
 *
 * @param active         off: the other settings are defaults, to start from.
 * @param running        one is being made.
 * @param lastBackupFile the last one made, in the folder it went to.
 * @param lastError      why the last one failed, if it did after the last that worked.
 * @param nextAt         when the next one is due, made only if something changed; absent if off.
 */
@TypeScriptType
record AdminBackup(@Required boolean active, @Required String folder, @Required BackupConfiguration.Frequency every, @Required int keep,
                   @Required int maxKeep, @Required boolean running, Long lastBackupAt, String lastBackupFile, Long lastBackupSize,
                   Long lastErrorAt, String lastError, Long nextAt) {
  static AdminBackup of(BackupService.Status status) {
    BackupConfiguration config = status.config() == null ? new BackupConfiguration() : status.config();
    return new AdminBackup(status.config() != null, config.getFolder() == null ? "" : config.getFolder(), config.getEvery(), config.getKeep(),
        BackupConfiguration.MAX_KEEP, status.running(), status.lastBackupAt(), status.lastBackupFile(), status.lastBackupSize(),
        status.lastErrorAt(), status.lastError(), status.nextAt());
  }
}

@TypeScriptType
class AdminBackupApiInput extends AuthenticatedApiInput {
}
