package net.pieroxy.notes.sync;

import com.github.difflib.DiffUtils;
import com.github.difflib.algorithm.DiffAlgorithmListener;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Three-way merge of texts: the changes from {@code base} to {@code ours} and from {@code base} to
 * {@code theirs}, combined. Never fails and never drops text: these are notes, a wrong guess costs
 * a glance where lost text can't be undone.
 * <p>
 * Done line by line first, then word by word inside the lines both sides changed, so that two
 * edits to different words of a paragraph (which is one line) merge cleanly. Two changes clash
 * when they overlap, or when one inserts exactly where the other changes or inserts, unless both
 * made the very same change. A clash keeps both sides' versions, ours first; a side that deleted
 * the passage gives way to the other's edit. Each clash gets {@link #MARKER} on a line of its own
 * above the line it starts in. The web app recognizes it by {@link #TAG} at the start of a line,
 * whatever follows: the sentence can change, or be translated.
 * <p>
 * A diff costs about its size times its number of edits: one going past {@link #MAX_DIFF_WORK} is
 * abandoned, and what was left to merge once the common lines at the start and end were set aside
 * is then a clash as a whole. A few edits anywhere in a huge note stay cheap; two unrelated huge
 * texts can't stall the server.
 */
public final class Merge3 {
  static final long MAX_DIFF_WORK = 100_000_000L;
  /** Passed on to the web app (see GenerateTsStubs). */
  public static final String TAG = "[!merge]";
  public static final String MARKER = TAG + " Auto-merged below: watch out for duplicated text";

  private Merge3() {}

  /** @param clashes the number of markers added. */
  public record Result(String text, int clashes) {}

  public static Result merge(String base, String ours, String theirs) {
    if (ours.equals(theirs) || base.equals(theirs)) return new Result(ours, 0);
    if (base.equals(ours)) return new Result(theirs, 0);

    StringBuilder merged = new StringBuilder();
    List<Integer> clashes = new ArrayList<>();
    for (Chunk chunk : diff3(lines(base), lines(ours), lines(theirs), false)) {
      if (chunk.isClash()) {
        mergeTokens(String.join("", chunk.base()), String.join("", chunk.ours()), String.join("", chunk.theirs()), merged, clashes);
      } else {
        merged.append(String.join("", chunk.merged()));
      }
    }
    // From the end, so that earlier positions stay valid. Two clashes in one line share a marker.
    TreeSet<Integer> lineStarts = new TreeSet<>();
    for (int position : clashes) lineStarts.add(merged.lastIndexOf("\n", position - 1) + 1);
    for (int lineStart : lineStarts.descendingSet()) merged.insert(lineStart, MARKER + "\n");
    return new Result(merged.toString(), lineStarts.size());
  }

  /**
   * Appends the merge of lines to {@code merged}, and where each clash starts in it to {@code clashes}.
   * Too costly word by word, the lines clash as a whole: never cut in two by a common last word.
   */
  private static void mergeTokens(String base, String ours, String theirs, StringBuilder merged, List<Integer> clashes) {
    List<Chunk> chunks;
    try {
      chunks = diff3(tokens(base), tokens(ours), tokens(theirs), true);
    } catch (TooExpensiveException e) {
      chunks = List.of(Chunk.clash(List.of(base), List.of(ours), List.of(theirs)));
    }
    for (Chunk chunk : chunks) {
      if (chunk.isClash()) {
        clashes.add(merged.length());
        merged.append(both(String.join("", chunk.ours()), String.join("", chunk.theirs())));
      } else {
        merged.append(String.join("", chunk.merged()));
      }
    }
  }

  /** Kept apart by a space, or a line break between passages of several lines, unless one side's edge already does. */
  static String both(String ours, String theirs) {
    if (ours.isEmpty()) return theirs;
    if (theirs.isEmpty()) return ours;
    if (Character.isWhitespace(ours.charAt(ours.length() - 1)) || Character.isWhitespace(theirs.charAt(0))) return ours + theirs;
    return ours + (ours.contains("\n") || theirs.contains("\n") ? "\n" : " ") + theirs;
  }

  /** Either merged (null base/ours/theirs) or a clash (null merged). */
  record Chunk(List<String> merged, List<String> base, List<String> ours, List<String> theirs) {
    static Chunk merged(List<String> tokens) {
      return new Chunk(tokens, null, null, null);
    }

    static Chunk clash(List<String> base, List<String> ours, List<String> theirs) {
      return new Chunk(null, base, ours, theirs);
    }

    boolean isClash() {
      return merged == null;
    }
  }

  private enum Side { OURS, THEIRS }

  /** Replaces {@code base[start, end)} with {@code replacement}. An empty range is an insertion. */
  private record Hunk(Side side, int start, int end, List<String> replacement) {}

  /** Thrown by {@link #diff} past {@link #MAX_DIFF_WORK}. */
  private static class TooExpensiveException extends RuntimeException {
    TooExpensiveException() {
      super(null, null, false, false);
    }
  }

  /** @param abandonAll past {@link #MAX_DIFF_WORK}, throws instead of making the middle a clash. */
  static List<Chunk> diff3(List<String> base, List<String> ours, List<String> theirs, boolean abandonAll) {
    int shortest = Math.min(base.size(), Math.min(ours.size(), theirs.size()));
    int prefix = 0;
    while (prefix < shortest && base.get(prefix).equals(ours.get(prefix)) && base.get(prefix).equals(theirs.get(prefix))) prefix++;
    int suffix = 0;
    while (suffix < shortest - prefix
        && base.get(base.size() - 1 - suffix).equals(ours.get(ours.size() - 1 - suffix))
        && base.get(base.size() - 1 - suffix).equals(theirs.get(theirs.size() - 1 - suffix))) suffix++;

    List<Chunk> chunks = new ArrayList<>();
    if (prefix > 0) chunks.add(Chunk.merged(base.subList(0, prefix)));
    List<String> b = base.subList(prefix, base.size() - suffix);
    List<String> o = ours.subList(prefix, ours.size() - suffix);
    List<String> t = theirs.subList(prefix, theirs.size() - suffix);
    try {
      chunks.addAll(merge(b, o, t));
    } catch (TooExpensiveException e) {
      if (abandonAll) throw e;
      chunks.add(Chunk.clash(b, o, t));
    }
    if (suffix > 0) chunks.add(Chunk.merged(base.subList(base.size() - suffix, base.size())));
    return chunks;
  }

  private static List<Chunk> merge(List<String> base, List<String> ours, List<String> theirs) {
    List<Hunk> hunks = new ArrayList<>();
    addHunks(hunks, Side.OURS, base, ours);
    addHunks(hunks, Side.THEIRS, base, theirs);
    hunks.sort(Comparator.comparingInt(Hunk::start).thenComparingInt(Hunk::end));

    List<Chunk> chunks = new ArrayList<>();
    int position = 0;
    int i = 0;
    while (i < hunks.size()) {
      List<Hunk> group = new ArrayList<>();
      group.add(hunks.get(i++));
      int start = group.get(0).start();
      int end = group.get(0).end();
      while (i < hunks.size() && touches(hunks.get(i), start, end)) {
        end = Math.max(end, hunks.get(i).end());
        group.add(hunks.get(i++));
      }

      if (start > position) chunks.add(Chunk.merged(base.subList(position, start)));
      List<String> oursVersion = apply(group, Side.OURS, base, start, end);
      List<String> theirsVersion = apply(group, Side.THEIRS, base, start, end);
      boolean oursChanged = group.stream().anyMatch(h -> h.side() == Side.OURS);
      boolean theirsChanged = group.stream().anyMatch(h -> h.side() == Side.THEIRS);
      if (!theirsChanged || oursVersion.equals(theirsVersion)) {
        chunks.add(Chunk.merged(oursVersion));
      } else if (!oursChanged) {
        chunks.add(Chunk.merged(theirsVersion));
      } else {
        chunks.add(Chunk.clash(base.subList(start, end), oursVersion, theirsVersion));
      }
      position = end;
    }
    if (position < base.size()) chunks.add(Chunk.merged(base.subList(position, base.size())));
    return chunks;
  }

  /**
   * Whether {@code hunk}, starting at or after {@code start}, belongs with the group spanning
   * {@code [start, end)}: overlapping it, or meeting it at its end where either is an insertion.
   * Hunks from one side never touch each other: a diff separates its changes with unchanged text.
   */
  private static boolean touches(Hunk hunk, int start, int end) {
    return hunk.start() < end || (hunk.start() == end && (hunk.start() == hunk.end() || start == end));
  }

  private static void addHunks(List<Hunk> hunks, Side side, List<String> base, List<String> revised) {
    for (AbstractDelta<String> delta : diff(base, revised).getDeltas()) {
      int start = delta.getSource().getPosition();
      hunks.add(new Hunk(side, start, start + delta.getSource().size(), delta.getTarget().getLines()));
    }
  }

  /** @throws TooExpensiveException past {@link #MAX_DIFF_WORK}: each of Myers' steps costs up to the inputs' size. */
  private static Patch<String> diff(List<String> base, List<String> revised) {
    long size = base.size() + revised.size();
    return DiffUtils.diff(base, revised, new DiffAlgorithmListener() {
      @Override
      public void diffStart() {
      }

      @Override
      public void diffStep(int value, int max) {
        if (value * size > MAX_DIFF_WORK) throw new TooExpensiveException();
      }

      @Override
      public void diffEnd() {
      }
    });
  }

  /** {@code base[start, end)} with {@code side}'s hunks of the group applied. */
  private static List<String> apply(List<Hunk> group, Side side, List<String> base, int start, int end) {
    List<String> result = new ArrayList<>();
    int position = start;
    for (Hunk hunk : group) {
      if (hunk.side() != side) continue;
      result.addAll(base.subList(position, hunk.start()));
      result.addAll(hunk.replacement());
      position = hunk.end();
    }
    result.addAll(base.subList(position, end));
    return result;
  }

  /** Lines with their terminating {@code \n}, so that joining them gives the text back. */
  static List<String> lines(String text) {
    List<String> lines = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        lines.add(text.substring(start, i + 1));
        start = i + 1;
      }
    }
    if (start < text.length()) lines.add(text.substring(start));
    return lines;
  }

  /**
   * Words (letters, digits and combining marks), runs of horizontal whitespace, and every other
   * code point on its own, {@code \n} included. Joining them gives the text back; surrogate
   * pairs are never split.
   */
  static List<String> tokens(String text) {
    List<String> tokens = new ArrayList<>();
    int i = 0;
    while (i < text.length()) {
      int start = i;
      int codePoint = text.codePointAt(i);
      i += Character.charCount(codePoint);
      if (isWordPart(codePoint)) {
        while (i < text.length() && isWordPart(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i));
      } else if (isHorizontalSpace(codePoint)) {
        while (i < text.length() && isHorizontalSpace(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i));
      }
      tokens.add(text.substring(start, i));
    }
    return tokens;
  }

  private static boolean isWordPart(int codePoint) {
    int type = Character.getType(codePoint);
    return Character.isLetterOrDigit(codePoint) || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
        || type == Character.ENCLOSING_MARK;
  }

  private static boolean isHorizontalSpace(int codePoint) {
    return codePoint != '\n' && Character.isWhitespace(codePoint);
  }
}
