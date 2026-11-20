package net.pieroxy.notes.storage;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which revisions of a note to keep: all of them for {@link #KEEP_ALL}, then the last of each hour
 * up to {@link #KEEP_HOURLY}, then the last of each day (UTC) for good. The head, the first
 * revision and those that delete or bring back the note are always kept.
 */
public final class ThinningPolicy {
  static final Duration KEEP_ALL = Duration.ofDays(7);
  static final Duration KEEP_HOURLY = Duration.ofDays(90);
  /** Past this age, a revision's submitted text is dropped: it only serves pushes in flight. */
  static final Duration KEEP_SUBMITTED = Duration.ofDays(1);

  /** What the policy needs to know of a revision; {@code at} in epoch milliseconds. */
  public record Entry(int revision, long at, boolean deleted) {}

  private ThinningPolicy() {}

  /** @param entries the revisions that exist, in revision order; the last one is the head. */
  public static Set<Integer> keep(List<Entry> entries, long now) {
    Set<Integer> keep = new HashSet<>();
    if (entries.isEmpty()) return keep;
    keep.add(entries.get(0).revision());
    keep.add(entries.get(entries.size() - 1).revision());
    // Bucket -> the last revision in it. Hourly and daily buckets can't collide: they're tagged.
    Map<String, Integer> lastInBucket = new HashMap<>();
    Entry previous = null;
    for (Entry entry : entries) {
      long age = now - entry.at();
      if (previous != null && previous.deleted() != entry.deleted()) keep.add(entry.revision());
      if (age < KEEP_ALL.toMillis()) {
        keep.add(entry.revision());
      } else if (age < KEEP_HOURLY.toMillis()) {
        lastInBucket.put("h" + entry.at() / Duration.ofHours(1).toMillis(), entry.revision());
      } else {
        lastInBucket.put("d" + entry.at() / Duration.ofDays(1).toMillis(), entry.revision());
      }
      previous = entry;
    }
    keep.addAll(lastInBucket.values());
    return keep;
  }

  static boolean dropsSubmitted(long at, long now) {
    return now - at >= KEEP_SUBMITTED.toMillis();
  }
}
