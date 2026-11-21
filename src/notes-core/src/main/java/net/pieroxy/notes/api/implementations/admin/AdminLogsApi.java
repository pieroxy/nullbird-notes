package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.utils.logging.LogReader;
import net.pieroxy.notes.utils.logging.LoggingBootstrap;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The application log, one file at a time (the current one or an archive), newest entries first, a
 * page at a time: those of the levels asked for that contain the search text. The file is read
 * through on each call, holding one page in memory at most.
 */
@Endpoint(method = ApiMethod.GET)
public class AdminLogsApi extends AbstractAdminEndpoint<AdminLogsApiInput, AdminLogsApiOutput> {
  static final int PAGE = 200;

  public AdminLogsApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public AdminLogsApiOutput processAdmin(AdminLogsApiInput input) throws Exception {
    File logFile = LoggingBootstrap.logFileIn(serviceProvider.getSettingsService().getDataFolder());
    List<AdminLogFile> files = LogReader.files(logFile).stream().map(f -> new AdminLogFile(f.index(), f.size(), f.firstAt())).toList();
    AdminLogsApiOutput output = new AdminLogsApiOutput();
    output.setFiles(files);
    // Only a file of the list: never a path from the request.
    if (files.stream().noneMatch(f -> f.index() == input.getFile())) return output;

    String query = input.getQuery() == null ? "" : input.getQuery().trim().toLowerCase(Locale.ROOT);
    Set<String> levels = input.getLevels() == null ? Set.of() : new HashSet<>(input.getLevels());
    long before = input.getBefore() == null ? Long.MAX_VALUE : input.getBefore();
    Map<String, Integer> counts = new TreeMap<>();
    Deque<AdminLogEntry> page = new ArrayDeque<>();
    int[] index = {0};
    int[] older = {0};
    LogReader.read(logFile, input.getFile(), entry -> {
      int i = index[0]++;
      if (!query.isEmpty() && !matches(entry, query)) return;
      counts.merge(entry.level(), 1, Integer::sum);
      if (i >= before || (!levels.isEmpty() && !levels.contains(entry.level()))) return;
      page.addLast(new AdminLogEntry(i, entry.time(), entry.run(), entry.level(), entry.source(), entry.message(), entry.details()));
      if (page.size() > PAGE) {
        page.removeFirst();
        older[0]++;
      }
    });
    List<AdminLogEntry> newestFirst = new ArrayList<>(page);
    Collections.reverse(newestFirst);
    output.setEntries(newestFirst);
    output.setCounts(counts);
    output.setMore(older[0] > 0);
    return output;
  }

  private static boolean matches(LogReader.Entry entry, String query) {
    return entry.message().toLowerCase(Locale.ROOT).contains(query) || entry.source().toLowerCase(Locale.ROOT).contains(query)
        || entry.details().toLowerCase(Locale.ROOT).contains(query) || entry.time().contains(query) || entry.run().toLowerCase(Locale.ROOT).contains(query);
  }
}

/**
 * @param index   0 for the current file, N for archive N.
 * @param size    bytes on disk, compressed for an archive.
 * @param firstAt its first entry's time, "" if none.
 */
@TypeScriptType
record AdminLogFile(@Required int index, @Required long size, @Required String firstAt) {
}

/**
 * @param index   its position in the file: pass the last one shown as {@code before} for older ones.
 * @param time    the server's local time, as written.
 * @param run     the ID of the process that wrote it: it changes at each restart.
 * @param details the lines after the first (a stack trace), "" if none.
 */
@TypeScriptType
record AdminLogEntry(@Required int index, @Required String time, @Required String run, @Required String level, @Required String source,
                     @Required String message, @Required String details) {
}

@TypeScriptType
class AdminLogsApiInput extends AuthenticatedApiInput {
  /** 0 for the current file, N for archive N. */
  @Required
  private int file;
  /** Level names, e.g. SEVERE, WARNING; empty for all. */
  private List<String> levels;
  /** Case-insensitive; blank for all. */
  private String query;
  /** Only entries before this index: the next page. Absent: from the newest. */
  private Integer before;

  public int getFile() {
    return file;
  }

  public void setFile(int file) {
    this.file = file;
  }

  public List<String> getLevels() {
    return levels;
  }

  public void setLevels(List<String> levels) {
    this.levels = levels;
  }

  public String getQuery() {
    return query;
  }

  public void setQuery(String query) {
    this.query = query;
  }

  public Integer getBefore() {
    return before;
  }

  public void setBefore(Integer before) {
    this.before = before;
  }
}

@TypeScriptType
class AdminLogsApiOutput {
  /** The current file then the archives, newest first. */
  @Required
  private List<AdminLogFile> files = List.of();
  /** Newest first, at most a page. */
  @Required
  private List<AdminLogEntry> entries = List.of();
  /** Entries of the whole file that match the search, by level, whatever the levels asked for. */
  @Required
  private Map<String, Integer> counts = Map.of();
  /** Whether older entries match too. */
  @Required
  private boolean more;

  public List<AdminLogFile> getFiles() {
    return files;
  }

  public void setFiles(List<AdminLogFile> files) {
    this.files = files;
  }

  public List<AdminLogEntry> getEntries() {
    return entries;
  }

  public void setEntries(List<AdminLogEntry> entries) {
    this.entries = entries;
  }

  public Map<String, Integer> getCounts() {
    return counts;
  }

  public void setCounts(Map<String, Integer> counts) {
    this.counts = counts;
  }

  public boolean isMore() {
    return more;
  }

  public void setMore(boolean more) {
    this.more = more;
  }
}
