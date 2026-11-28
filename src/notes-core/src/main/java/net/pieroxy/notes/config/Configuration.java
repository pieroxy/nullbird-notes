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
  /** The session cookie travels over HTTPS only: false to serve over plain HTTP. */
  private boolean secureCookie = true;
  /** See {@code LogRetention#days}. */
  private Integer keepLogFiles;
  private int maxNoteSizeMb = 10;
  /** Absent: no mail is sent. */
  private MailOutConfiguration mailOut;
  /** Absent: no backups. */
  private BackupConfiguration backup;

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

  public Integer getKeepLogFiles() {
    return keepLogFiles;
  }

  public void setKeepLogFiles(Integer keepLogFiles) {
    this.keepLogFiles = keepLogFiles;
  }

  public int getMaxNoteSizeMb() {
    return maxNoteSizeMb;
  }

  public void setMaxNoteSizeMb(int maxNoteSizeMb) {
    this.maxNoteSizeMb = maxNoteSizeMb;
  }

  public MailOutConfiguration getMailOut() {
    return mailOut;
  }

  public void setMailOut(MailOutConfiguration mailOut) {
    this.mailOut = mailOut;
  }

  public boolean isSecureCookie() {
    return secureCookie;
  }

  public void setSecureCookie(boolean secureCookie) {
    this.secureCookie = secureCookie;
  }

  public BackupConfiguration getBackup() {
    return backup;
  }

  public void setBackup(BackupConfiguration backup) {
    this.backup = backup;
  }
}
