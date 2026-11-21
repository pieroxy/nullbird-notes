package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.NoteStoreService;
import net.pieroxy.notes.services.SettingsService;

import java.util.ArrayList;
import java.util.List;

/**
 * config.json: as in effect (read at startup) and as saved (what the next startup will use), with
 * what the admin needs to tell what a change would break.
 */
@Endpoint(method = ApiMethod.GET)
public class AdminConfigApi extends AbstractAdminEndpoint<AdminConfigApiInput, AdminConfigApiOutput> {
  /** No limit can be set below it: smaller notes never matter. */
  private static final long MIN_LIMIT_BYTES = 1024L * 1024L;

  public AdminConfigApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AdminConfigApiOutput processAdmin(AdminConfigApiInput input) throws Exception {
    SettingsService settings = serviceProvider.getSettingsService();
    NoteStoreService stores = serviceProvider.getNoteStoreService();
    List<AdminLargeNote> largeNotes = new ArrayList<>();
    for (UserCredential user : settings.listUsers()) {
      for (long size : stores.sizesAbove(user.getUsername(), MIN_LIMIT_BYTES)) largeNotes.add(new AdminLargeNote(user.getUsername(), size));
    }
    return new AdminConfigApiOutput(AdminConfig.of(settings.getConfig()), AdminConfig.of(settings.readSavedConfig()),
        settings.getConfigFile().getAbsoluteFile().toPath().normalize().toString(), settings.getDataFolder().getAbsolutePath(), largeNotes);
  }
}

/** A note over 1 MB: lowering the size limit below it would make it uneditable. */
@TypeScriptType
record AdminLargeNote(@Required String username, @Required long size) {
}

@TypeScriptType
class AdminConfigApiInput extends AuthenticatedApiInput {
}

@TypeScriptType
class AdminConfigApiOutput {
  /** In effect: read at startup. */
  @Required
  private AdminConfig running;
  /** config.json now: what the next startup will use. */
  @Required
  private AdminConfig saved;
  /** config.json's absolute path. */
  @Required
  private String configFile;
  /** The data folder in use, absolute. */
  @Required
  private String dataFolderPath;
  /** Every note over 1 MB, deleted ones aside. */
  @Required
  private List<AdminLargeNote> largeNotes;

  public AdminConfigApiOutput() {
  }

  public AdminConfigApiOutput(AdminConfig running, AdminConfig saved, String configFile, String dataFolderPath, List<AdminLargeNote> largeNotes) {
    this.running = running;
    this.saved = saved;
    this.configFile = configFile;
    this.dataFolderPath = dataFolderPath;
    this.largeNotes = largeNotes;
  }

  public AdminConfig getRunning() {
    return running;
  }

  public void setRunning(AdminConfig running) {
    this.running = running;
  }

  public AdminConfig getSaved() {
    return saved;
  }

  public void setSaved(AdminConfig saved) {
    this.saved = saved;
  }

  public String getConfigFile() {
    return configFile;
  }

  public void setConfigFile(String configFile) {
    this.configFile = configFile;
  }

  public String getDataFolderPath() {
    return dataFolderPath;
  }

  public void setDataFolderPath(String dataFolderPath) {
    this.dataFolderPath = dataFolderPath;
  }

  public List<AdminLargeNote> getLargeNotes() {
    return largeNotes;
  }

  public void setLargeNotes(List<AdminLargeNote> largeNotes) {
    this.largeNotes = largeNotes;
  }
}
