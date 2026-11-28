package net.pieroxy.notes.config;

import java.time.Duration;

/**
 * config.json's "backup": absent, no backups. A zip of the notes, users and settings, made in
 * {@link #getFolder()} every {@link #getEvery()} when something changed, the last {@link #getKeep()}
 * kept (see BackupService). Applies at once when an admin changes it.
 */
public class BackupConfiguration {
  public enum Frequency {
    HOURLY(Duration.ofHours(1)),
    DAILY(Duration.ofDays(1));

    private final Duration interval;

    Frequency(Duration interval) {
      this.interval = interval;
    }

    public Duration interval() {
      return interval;
    }
  }

  public static final int MAX_KEEP = 1000;

  /** An absolute path: a folder that exists, empty or with backups only. */
  private String folder;
  private Frequency every = Frequency.DAILY;
  private int keep = 14;

  public BackupConfiguration() {}

  public BackupConfiguration(String folder, Frequency every, int keep) {
    this.folder = folder;
    this.every = every;
    this.keep = keep;
  }

  public String getFolder() {
    return folder;
  }

  public void setFolder(String folder) {
    this.folder = folder;
  }

  public Frequency getEvery() {
    return every;
  }

  public void setEvery(Frequency every) {
    this.every = every;
  }

  public int getKeep() {
    return keep;
  }

  public void setKeep(int keep) {
    this.keep = keep;
  }
}
