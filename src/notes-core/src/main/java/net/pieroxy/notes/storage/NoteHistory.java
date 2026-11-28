package net.pieroxy.notes.storage;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A note's history as the user reads it: its revisions grouped into editing sessions. A device
 * saves a second after typing stops, so one session leaves dozens of revisions; an entry stands for
 * the text at the end of its session.
 * <p>
 * A session is the saves of one device less than {@link #SESSION_GAP} apart. It ends before a big
 * removal, so that what was there before is an entry of its own: consecutive saves removing more
 * than they add (or more than {@link #BIG_REMOVAL} characters at once) and more than
 * {@link #BIG_REMOVAL} characters in all. Moving the note to the trash and bringing it back are
 * entries of their own too.
 * <p>
 * Only for display: what's kept is up to {@link ThinningPolicy}. Past its first week, a note keeps
 * one revision an hour, then one a day: few sessions are left to group or cut there.
 */
public final class NoteHistory {
  static final Duration SESSION_GAP = Duration.ofMinutes(10);
  static final int BIG_REMOVAL = 20;
  /** Past this many changed characters, a save's change is counted without a diff: all removed, all added. */
  static final int MAX_DIFFED = 20_000;

  public enum HistoryKind {
    /** The session that created the note. */
    CREATED,
    EDITED,
    /** Moved to the trash. */
    DELETED,
    /** Brought back from the trash. */
    RESTORED
  }

  /**
   * One revision, with what it changed from the one before it that's kept.
   *
   * @param removed characters removed; 0 for the first one.
   * @param added   characters added; 0 for the first one.
   */
  public record Step(int revision, long at, String deviceId, boolean deleted, int removed, int added) {}

  /**
   * One session.
   *
   * @param revision the last of its revisions: the text it left.
   * @param from     when its first save was made, in epoch milliseconds.
   * @param to       when its last one was.
   * @param saves    how many revisions are left of it.
   */
  public record Entry(int revision, long from, long to, int saves, HistoryKind kind) {}

  private NoteHistory() {}

  /**
   * @param steps the revisions that are kept, oldest first.
   * @return the sessions, newest first.
   */
  public static List<Entry> sessions(List<Step> steps) {
    int n = steps.size();
    boolean[] cut = new boolean[n + 1];
    for (int i = 1; i < n; i++) {
      Step previous = steps.get(i - 1);
      Step step = steps.get(i);
      if (!Objects.equals(previous.deviceId(), step.deviceId()) || step.at() - previous.at() > SESSION_GAP.toMillis()) cut[i] = true;
      // Into or out of the trash: alone.
      if (previous.deleted() != step.deleted()) {
        cut[i] = true;
        cut[i + 1] = true;
      }
    }
    for (int i = 1; i < n; ) {
      if (!removes(steps.get(i))) {
        i++;
        continue;
      }
      int start = i;
      int removed = 0;
      while (i < n && removes(steps.get(i)) && (i == start || !cut[i])) removed += steps.get(i++).removed();
      if (removed > BIG_REMOVAL) cut[start] = true;
    }

    List<Entry> entries = new ArrayList<>();
    int start = 0;
    for (int i = 1; i <= n; i++) {
      if (i < n && !cut[i]) continue;
      Step first = steps.get(start);
      Step last = steps.get(i - 1);
      Step before = start > 0 ? steps.get(start - 1) : null;
      HistoryKind kind;
      if (start == 0 && first.revision() == 1 && !first.deleted()) kind = HistoryKind.CREATED;
      else if (first.deleted() && (before == null || !before.deleted())) kind = HistoryKind.DELETED;
      else if (before != null && before.deleted() && !first.deleted()) kind = HistoryKind.RESTORED;
      else kind = HistoryKind.EDITED;
      entries.add(new Entry(last.revision(), first.at(), last.at(), i - start, kind));
      start = i;
    }
    Collections.reverse(entries);
    return entries;
  }

  private static boolean removes(Step step) {
    return step.removed() > 0 && (step.removed() > step.added() || step.removed() > BIG_REMOVAL);
  }

  /** @return the characters (UTF-16 code units) removed from {@code from} and added to make {@code to}. */
  public static int[] changed(String from, String to) {
    int max = Math.min(from.length(), to.length());
    int prefix = 0;
    while (prefix < max && from.charAt(prefix) == to.charAt(prefix)) prefix++;
    int suffix = 0;
    while (suffix < max - prefix && from.charAt(from.length() - 1 - suffix) == to.charAt(to.length() - 1 - suffix)) suffix++;
    String a = from.substring(prefix, from.length() - suffix);
    String b = to.substring(prefix, to.length() - suffix);
    if (a.isEmpty() || b.isEmpty() || a.length() + b.length() > MAX_DIFFED) return new int[]{a.length(), b.length()};
    Patch<Character> patch = DiffUtils.diff(chars(a), chars(b));
    int removed = 0;
    int added = 0;
    for (AbstractDelta<Character> delta : patch.getDeltas()) {
      removed += delta.getSource().size();
      added += delta.getTarget().size();
    }
    return new int[]{removed, added};
  }

  private static List<Character> chars(String s) {
    List<Character> chars = new ArrayList<>(s.length());
    for (int i = 0; i < s.length(); i++) chars.add(s.charAt(i));
    return chars;
  }
}
