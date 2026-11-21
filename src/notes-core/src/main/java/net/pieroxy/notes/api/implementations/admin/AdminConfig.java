package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.utils.logging.LogRetention;

import java.util.List;

/**
 * config.json's values, defaults filled in.
 *
 * @param address        "" for all interfaces.
 * @param trustedProxies IP addresses or CIDR ranges.
 * @param keepLogFiles   days; 0 keeps them forever.
 */
@TypeScriptType
public record AdminConfig(@Required int httpPort, @Required String address, @Required String dataFolder,
                          @Required List<String> trustedProxies, @Required boolean secureCookie, @Required int keepLogFiles,
                          @Required int maxNoteSizeMb) {
  static AdminConfig of(Configuration config) {
    return new AdminConfig(config.getHttpPort(), config.getAddress() == null ? "" : config.getAddress().trim(), config.getDataFolder(),
        config.getTrustedProxies() == null ? List.of() : config.getTrustedProxies().stream().map(String::trim).toList(),
        config.isSecureCookie(), LogRetention.days(config.getKeepLogFiles()), config.getMaxNoteSizeMb());
  }
}
