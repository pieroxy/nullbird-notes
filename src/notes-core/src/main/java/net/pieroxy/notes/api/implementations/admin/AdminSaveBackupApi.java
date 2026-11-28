package net.pieroxy.notes.api.implementations.admin;

import com.google.gson.JsonObject;
import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.BackupConfiguration;
import net.pieroxy.notes.services.BackupService;
import net.pieroxy.notes.services.IServiceProvider;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Activates the backups, or changes them: the folder checked first (see BackupService#check), then
 * saved to config.json's "backup", in effect at once. A backup is made right away, to know it works.
 */
@Endpoint(method = ApiMethod.POST)
public class AdminSaveBackupApi extends AbstractAdminEndpoint<AdminSaveBackupApiInput, AdminResult> {
  private final static Logger LOGGER = Logger.getLogger(AdminSaveBackupApi.class.getName());

  public AdminSaveBackupApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AdminResult processAdmin(AdminSaveBackupApiInput input) throws Exception {
    String who = "Admin \"" + input.getUsername() + "\" from " + input.getClientAddress();
    BackupService backups = serviceProvider.getBackupService();
    BackupConfiguration wanted = new BackupConfiguration(input.getFolder() == null ? "" : input.getFolder().trim(), input.getEvery(), input.getKeep());
    try {
      if (wanted.getEvery() == null) throw new IllegalArgumentException("Choose how often.");
      if (wanted.getKeep() < 1 || wanted.getKeep() > BackupConfiguration.MAX_KEEP) {
        throw new IllegalArgumentException("The number of backups to keep must be between 1 and " + BackupConfiguration.MAX_KEEP + ".");
      }
      wanted.setFolder(backups.check(wanted.getFolder()).toString());
    } catch (IllegalArgumentException e) {
      LOGGER.info(who + " was refused changing the backups: " + AbstractUserChangeEndpoint.printable(e.getMessage()));
      return AdminResult.refused(e.getMessage());
    }

    BackupConfiguration before = backups.status().config();
    if (before != null && before.getFolder().equals(wanted.getFolder()) && before.getEvery() == wanted.getEvery() && before.getKeep() == wanted.getKeep()) {
      return AdminResult.done();
    }
    JsonObject backup = new JsonObject();
    backup.addProperty("folder", wanted.getFolder());
    backup.addProperty("every", wanted.getEvery().name());
    backup.addProperty("keep", wanted.getKeep());
    serviceProvider.getSettingsService().updateSavedConfig(json -> json.add("backup", backup));
    backups.configure(wanted);
    String settings = wanted.getFolder() + ", " + wanted.getEvery().name().toLowerCase() + ", " + wanted.getKeep() + " kept";
    if (before == null) {
      LOGGER.info(who + " activated the backups: " + AbstractUserChangeEndpoint.printable(settings));
    } else {
      LOGGER.info(who + " changed the backups: " + AbstractUserChangeEndpoint.printable(settings)
          + (Objects.equals(before.getFolder(), wanted.getFolder()) ? "" : " (were in " + AbstractUserChangeEndpoint.printable(before.getFolder()) + ")"));
    }
    backups.backUpNow();
    return AdminResult.done();
  }
}

@TypeScriptType
class AdminSaveBackupApiInput extends AuthenticatedApiInput {
  /** A full path to a folder that exists. */
  @Required
  private String folder;
  @Required
  private BackupConfiguration.Frequency every;
  @Required
  private int keep;

  public String getFolder() {
    return folder;
  }

  public void setFolder(String folder) {
    this.folder = folder;
  }

  public BackupConfiguration.Frequency getEvery() {
    return every;
  }

  public void setEvery(BackupConfiguration.Frequency every) {
    this.every = every;
  }

  public int getKeep() {
    return keep;
  }

  public void setKeep(int keep) {
    this.keep = keep;
  }
}
