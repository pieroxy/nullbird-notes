package net.pieroxy.notes.sync;

import com.github.difflib.DiffUtils;
import com.github.difflib.algorithm.DiffAlgorithmListener;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Three-way merge of texts: the changes from {@code base} to {@code ours} and from {@code base} to
 * {@code theirs} are combined where they don't touch the same text.
 * <p>
 * Done line by line first, then word by word inside the lines both sides changed, so that two
 * edits to different words of a paragraph (which is one line) merge cleanly. Two changes conflict
 * when they overlap, or when one inserts exactly where the other changes or inserts (the order
 * would be a guess), unless both made the very same change.
 * <p>
 * A conflict is never resolved by guessing: the result carries both outcomes, every clean change
 * applied to each, one with our side of each conflict, the other with theirs.
 * <p>
 * A diff costs about its size times its number of edits: one going past {@link #MAX_DIFF_WORK} is
 * abandoned, and what was left to merge once the common start and end were set aside is then a
 * conflict as a whole. A few edits anywhere in a huge note stay cheap; two unrelated huge texts
 * can't stall the server.
 */
public final class Merge3 {
  static final long MAX_DIFF_WORK = 100_000_000L;

  private Merge3() {}

  /**
   * @param preferringOurs   every clean change applied, our side of each conflict.
   * @param preferringTheirs every clean change applied, their side of each conflict.
   *                         Equal to {@code preferringOurs} when clean.
   */
  public record Result(String preferringOurs, String preferringTheirs, int conflicts) {
    public boolean isClean() {
      return conflicts == 0;
    }
  }

  public static Result merge(String base, String ours, String theirs) {
    if (ours.equals(theirs) || base.equals(theirs)) return new Result(ours, ours, 0);
    if (base.equals(ours)) return new Result(theirs, theirs, 0);

    StringBuilder preferringOurs = new StringBuilder();
    StringBuilder preferringTheirs = new StringBuilder();
    int conflicts = 0;
    for (Chunk chunk : diff3(lines(base), lines(ours), lines(theirs))) {
      if (chunk.isConflict()) {
        Result refined = mergeTokens(String.join("", chunk.base()), String.join("", chunk.ours()), String.join("", chunk.theirs()));
        preferringOurs.append(refined.preferringOurs());
        preferringTheirs.append(refined.preferringTheirs());
        conflicts += refined.conflicts();
      } else {
        String text = String.join("", chunk.merged());
        preferringOurs.append(text);
        preferringTheirs.append(text);
      }
    }
    return new Result(preferringOurs.toString(), preferringTheirs.toString(), conflicts);
  }

  private static Result mergeTokens(String base, String ours, String theirs) {
    StringBuilder preferringOurs = new StringBuilder();
    StringBuilder preferringTheirs = new StringBuilder();
    int conflicts = 0;
    for (Chunk chunk : diff3(tokens(base), tokens(ours), tokens(theirs))) {
      if (chunk.isConflict()) {
        preferringOurs.append(String.join("", chunk.ours()));
        preferringTheirs.append(String.join("", chunk.theirs()));
        conflicts++;
      } else {
        String text = String.join("", chunk.merged());
        preferringOurs.append(text);
        preferringTheirs.append(text);
      }
    }
    return new Result(preferringOurs.toString(), preferringTheirs.toString(), conflicts);
  }

  /** Either merged (null base/ours/theirs) or a conflict (null merged). */
  record Chunk(List<String> merged, List<String> base, List<String> ours, List<String> theirs) {
    static Chunk merged(List<String> tokens) {
      return new Chunk(tokens, null, null, null);
    }

    static Chunk conflict(List<String> base, List<String> ours, List<String> theirs) {
      return new Chunk(null, base, ours, theirs);
    }

    boolean isConflict() {
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

  static List<Chunk> diff3(List<String> base, List<String> ours, List<String> theirs) {
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
      chunks.add(Chunk.conflict(b, o, t));
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
        chunks.add(Chunk.conflict(base.subList(start, end), oursVersion, theirsVersion));
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
