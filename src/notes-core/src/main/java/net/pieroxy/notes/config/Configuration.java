package net.pieroxy.notes.config;

import java.io.File;
import java.util.List;

/** config.json. Every field is optional; a missing config.json means all defaults. */
public class Configuration {
  private int httpPort = 8080;
  /** Blank: all interfaces. */
  private String address;
  /** Relative paths are resolved against the config directory. */
  private String dataFolder = "data";
  /** See {@code TrustedProxies}. */
  private List<String> trustedProxies;
  /** Set when the server is reached over HTTPS (typically through a reverse proxy). */
  private boolean secureCookie;

  public File resolveDataFolder(File configDir) {
    File folder = new File(dataFolder);
    return folder.isAbsolute() ? folder : new File(configDir, dataFolder);
  }

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

  public String getDataFolder() {
    return dataFolder;
  }

  public void setDataFolder(String dataFolder) {
    this.dataFolder = dataFolder;
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
}
