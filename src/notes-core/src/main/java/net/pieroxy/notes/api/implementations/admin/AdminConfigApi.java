package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.UserCredential;
import net.pieroxy.notes.services.ClientGuardService;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.NoteStoreService;
import net.pieroxy.notes.services.SettingsService;
import net.pieroxy.notes.webserver.TrustedProxies;

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
    List<AdminForwarder> forwarders = serviceProvider.getClientGuardService().forwarders().stream()
        .map(f -> new AdminForwarder(f.address(), f.requests(), f.forwarded(), f.clients(), f.clients() >= ClientGuardService.MAX_FORWARDED_CLIENTS,
            f.lastSeenAt(), TrustedProxies.isPrivate(f.address()), f.address().equals(input.getClientAddress())))
        .toList();
    return new AdminConfigApiOutput(AdminConfig.of(settings.getConfig()), AdminConfig.of(settings.readSavedConfig()),
        settings.getConfigFile().getAbsoluteFile().toPath().normalize().toString(), settings.getDataFolder().getAbsolutePath(), largeNotes,
        forwarders);
  }
}

/** A note over 1 MB: lowering the size limit below it would make it uneditable. */
@TypeScriptType
record AdminLargeNote(@Required String username, @Required long size) {
}

/**
 * An untrusted address seen forwarding requests since the start: likely a reverse proxy missing from
 * trustedProxies, unless a client faked the header.
 *
 * @param requests    since its first forwarded one.
 * @param forwarded   requests carrying X-Forwarded-For: about all of them for a proxy.
 * @param clients     distinct addresses forwarded for.
 * @param moreClients {@code clients} reached its cap: there may be more.
 * @param yours       the admin's own request came from it.
 */
@TypeScriptType
record AdminForwarder(@Required String address, @Required long requests, @Required long forwarded, @Required int clients,
                      @Required boolean moreClients, @Required long lastSeenAt, @Required boolean privateAddress, @Required boolean yours) {
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
  /** The most forwarding first. */
  @Required
  private List<AdminForwarder> forwarders;

  public AdminConfigApiOutput() {
  }

  public AdminConfigApiOutput(AdminConfig running, AdminConfig saved, String configFile, String dataFolderPath, List<AdminLargeNote> largeNotes,
                              List<AdminForwarder> forwarders) {
    this.running = running;
    this.saved = saved;
    this.configFile = configFile;
    this.dataFolderPath = dataFolderPath;
    this.largeNotes = largeNotes;
    this.forwarders = forwarders;
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

  public List<AdminForwarder> getForwarders() {
    return forwarders;
  }

  public void setForwarders(List<AdminForwarder> forwarders) {
    this.forwarders = forwarders;
  }
}
