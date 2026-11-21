package net.pieroxy.notes.api.implementations.admin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.ConfigValidation;
import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.services.IServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Writes the admin's values to config.json, for the next startup: nothing changes before. Every
 * value is checked first; only those that differ from the file's are written. The data folder isn't
 * one of them: moving it means moving the data, server stopped. A change is logged at WARNING: it
 * can make the service unreachable.
 */
@Endpoint(method = ApiMethod.POST)
public class AdminSaveConfigApi extends AbstractAdminEndpoint<AdminSaveConfigApiInput, AdminResult> {
  private final static Logger LOGGER = Logger.getLogger(AdminSaveConfigApi.class.getName());

  public AdminSaveConfigApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AdminResult processAdmin(AdminSaveConfigApiInput input) throws Exception {
    String who = "Admin \"" + input.getUsername() + "\" from " + input.getClientAddress();
    Configuration saved = serviceProvider.getSettingsService().readSavedConfig();
    Configuration wanted = new Configuration();
    wanted.setHttpPort(input.getHttpPort());
    wanted.setAddress(input.getAddress() == null ? "" : input.getAddress().trim());
    wanted.setDataFolder(saved.getDataFolder());
    List<String> proxies = new ArrayList<>();
    if (input.getTrustedProxies() != null) {
      for (String proxy : input.getTrustedProxies()) if (proxy != null && !proxy.isBlank()) proxies.add(proxy.trim());
    }
    wanted.setTrustedProxies(proxies);
    wanted.setSecureCookie(input.isSecureCookie());
    wanted.setKeepLogFiles(input.getKeepLogFiles());
    wanted.setMaxNoteSizeMb(input.getMaxNoteSizeMb());
    try {
      ConfigValidation.validate(wanted);
    } catch (IllegalArgumentException e) {
      LOGGER.info(who + " was refused changing config.json: " + AbstractUserChangeEndpoint.printable(e.getMessage()));
      return AdminResult.refused(e.getMessage());
    }

    AdminConfig before = AdminConfig.of(saved);
    AdminConfig after = AdminConfig.of(wanted);
    List<String> changes = new ArrayList<>();
    List<Consumer<JsonObject>> edits = new ArrayList<>();
    if (before.httpPort() != after.httpPort()) {
      changes.add("httpPort " + before.httpPort() + " → " + after.httpPort());
      edits.add(json -> json.addProperty("httpPort", after.httpPort()));
    }
    if (!before.address().equals(after.address())) {
      changes.add("address \"" + before.address() + "\" → \"" + after.address() + "\"");
      edits.add(json -> json.addProperty("address", after.address()));
    }
    if (!before.trustedProxies().equals(after.trustedProxies())) {
      changes.add("trustedProxies " + before.trustedProxies() + " → " + after.trustedProxies());
      edits.add(json -> {
        JsonArray array = new JsonArray();
        after.trustedProxies().forEach(array::add);
        json.add("trustedProxies", array);
      });
    }
    if (before.secureCookie() != after.secureCookie()) {
      changes.add("secureCookie " + before.secureCookie() + " → " + after.secureCookie());
      edits.add(json -> json.addProperty("secureCookie", after.secureCookie()));
    }
    if (before.keepLogFiles() != after.keepLogFiles()) {
      changes.add("keepLogFiles " + before.keepLogFiles() + " → " + after.keepLogFiles());
      edits.add(json -> json.addProperty("keepLogFiles", after.keepLogFiles()));
    }
    if (before.maxNoteSizeMb() != after.maxNoteSizeMb()) {
      changes.add("maxNoteSizeMb " + before.maxNoteSizeMb() + " → " + after.maxNoteSizeMb());
      edits.add(json -> json.addProperty("maxNoteSizeMb", after.maxNoteSizeMb()));
    }
    if (changes.isEmpty()) return AdminResult.done();

    serviceProvider.getSettingsService().updateSavedConfig(json -> edits.forEach(edit -> edit.accept(json)));
    LOGGER.warning(who + " changed " + serviceProvider.getSettingsService().getConfigFile().getAbsoluteFile().toPath().normalize() + ", from the next restart: "
        + AbstractUserChangeEndpoint.printable(String.join(", ", changes)));
    return AdminResult.done();
  }
}

@TypeScriptType
class AdminSaveConfigApiInput extends AuthenticatedApiInput {
  @Required
  private int httpPort;
  /** Blank: all interfaces. */
  private String address;
  @Required
  private List<String> trustedProxies;
  @Required
  private boolean secureCookie;
  /** Days; 0 keeps them forever. */
  @Required
  private int keepLogFiles;
  @Required
  private int maxNoteSizeMb;

  public int getHttpPort() {
    return httpPort;
  }

  public void setHttpPort(int httpPort) {
    this.httpPort = httpPort;
  }

  public String getAddress() {
    return address;
  }

  public void setAddress(String address) {
    this.address = address;
  }

  public List<String> getTrustedProxies() {
    return trustedProxies;
  }

  public void setTrustedProxies(List<String> trustedProxies) {
    this.trustedProxies = trustedProxies;
  }

  public boolean isSecureCookie() {
    return secureCookie;
  }

  public void setSecureCookie(boolean secureCookie) {
    this.secureCookie = secureCookie;
  }

  public int getKeepLogFiles() {
    return keepLogFiles;
  }

  public void setKeepLogFiles(int keepLogFiles) {
    this.keepLogFiles = keepLogFiles;
  }

  public int getMaxNoteSizeMb() {
    return maxNoteSizeMb;
  }

  public void setMaxNoteSizeMb(int maxNoteSizeMb) {
    this.maxNoteSizeMb = maxNoteSizeMb;
  }
}
