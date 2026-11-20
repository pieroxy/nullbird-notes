package net.pieroxy.notes.sync;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Merge3Test {
  private static final String M = Merge3.MARKER + "\n";

  private static void assertClean(String base, String ours, String theirs, String expected) {
    assertEquals(new Merge3.Result(expected, 0), Merge3.merge(base, ours, theirs));
  }

  private static void assertClash(String base, String ours, String theirs, int markers, String expected) {
    assertEquals(new Merge3.Result(expected, markers), Merge3.merge(base, ours, theirs));
  }

  @Test
  public void trivialCases() {
    assertClean("a", "a", "a", "a");
    assertClean("a", "b", "a", "b");
    assertClean("a", "a", "b", "b");
    assertClean("a", "b", "b", "b");
    assertClean("", "", "", "");
  }

  @Test
  public void changesToDifferentLinesMerge() {
    assertClean("one\ntwo\nthree\nfour\nfive\n",
        "ONE\ntwo\nthree\nfour\nfive\n",
        "one\ntwo\nthree\nfour\nFIVE\n",
        "ONE\ntwo\nthree\nfour\nFIVE\n");
  }

  @Test
  public void changesToDifferentWordsOfOneLineMerge() {
    assertClean("The quick brown fox jumps over the lazy dog.",
        "The slow brown fox jumps over the lazy dog.",
        "The quick brown fox jumps over the sleepy dog.",
        "The slow brown fox jumps over the sleepy dog.");
  }

  @Test
  public void changesToAdjacentWordsMerge() {
    assertClean("alpha beta gamma", "ALPHA beta gamma", "alpha BETA gamma", "ALPHA BETA gamma");
  }

  @Test
  public void theSameChangeOnBothSidesIsMadeOnce() {
    assertClean("a\nb\nc\n", "a\nB\nc\nd\n", "a\nB\nc\n", "a\nB\nc\nd\n");
    assertClean("one two", "one 2", "one 2", "one 2");
  }

  @Test
  public void differentChangesToTheSameWordKeepBothOursFirst() {
    assertClash("I like cats.", "I like dogs.", "I like birds.", 1, M + "I like dogs birds.");
  }

  @Test
  public void theMarkerGoesAboveTheLineAndCleanChangesApplyAround() {
    assertClash("Title\n\nI like cats.\n\nThe end.\n",
        "Title!\n\nI like dogs.\n\nThe end.\n",
        "Title\n\nI like birds.\n\nThe end, really.\n", 1,
        "Title!\n\n" + M + "I like dogs birds.\n\nThe end, really.\n");
  }

  @Test
  public void clashesInOneLineShareAMarker() {
    assertClash("a b c d e\nf g\n", "A b C d e\nF g\n", "1 b 3 d e\n6 g\n", 2, M + "A 1 b C 3 d e\n" + M + "F 6 g\n");
  }

  @Test
  public void insertionsAtTheSamePlaceAreBothKept() {
    assertClash("start end", "start mine end", "start yours end", 1, M + "start mine yours end");
    assertClean("start end", "start same end", "start same end", "start same end");
    assertClash("", "mine", "yours", 1, M + "mine yours");
    assertClash("ABC DEF", "ABC 1 DEF", "ABC 2 DEF", 1, M + "ABC 1 2 DEF");
  }

  @Test
  public void insertionsAtDifferentPlacesMerge() {
    assertClean("a\nb\nc\n", "x\na\nb\nc\n", "a\nb\nc\ny\n", "x\na\nb\nc\ny\n");
    assertClean("one two three", "zero one two three", "one two three four", "zero one two three four");
  }

  @Test
  public void anInsertionRightWhereTheOtherSideChangesKeepsBoth() {
    assertClash("keep this here", "keep NEW this here", "keep that here", 1, M + "keep NEW this that here");
  }

  @Test
  public void deletionsMergeWithChangesElsewhere() {
    assertClean("a\nb\nc\nd\n", "a\nc\nd\n", "a\nb\nc\nD\n", "a\nc\nD\n");
    assertClean("a\nb\nc\n", "a\nc\n", "a\nc\n", "a\nc\n");
  }

  @Test
  public void anEditBeatsTheDeletionOfWhatItEdited() {
    assertClash("a\nb\nc\n", "a\nc\n", "a\nB\nc\n", 1, "a\n" + M + "B\nc\n");
    assertClash("a\nb\nc\n", "a\nB\nc\n", "a\nc\n", 1, "a\n" + M + "B\nc\n");
  }

  @Test
  public void passagesOfSeveralLinesAreKeptApartByALineBreak() {
    assertEquals("a\nb\nc", Merge3.both("a\nb", "c"));
    assertEquals("a b", Merge3.both("a", "b"));
    assertEquals("a  b", Merge3.both("a ", " b"));
    assertEquals("b", Merge3.both("", "b"));
  }

  @Test
  public void handlesTextWithoutTrailingNewline() {
    assertClean("a\nb", "A\nb", "a\nB", "A\nB");
    assertClean("a\nb", "a\nb\n", "A\nb", "A\nb\n");
  }

  @Test
  public void keepsSurrogatePairsAndAccentsWhole() {
    assertClean("café 😀 noir", "café 😁 noir", "café 😀 crème", "café 😁 crème");
    assertClash("x 😀 y", "x 😁 y", "x 🙂 y", 1, M + "x 😁 🙂 y");
  }

  @Test
  public void tokensJoinBackIntoTheText() {
    String text = "Héllo,  wörld!\n\ttabs nbsp 😀👍🏽 é 42ab\n";
    assertEquals(text, String.join("", Merge3.tokens(text)));
    assertEquals(List.of("Héllo", ",", "  ", "wörld", "!", "\n", "\t", "tabs", " ", "nbsp", " ", "😀", "👍", "🏽", " ",
        "é", " ", "42ab", "\n"), Merge3.tokens(text));
  }

  @Test
  public void linesKeepTheirTerminator() {
    assertEquals(List.of("a\n", "\n", "b"), Merge3.lines("a\n\nb"));
    assertEquals(List.of("a\n"), Merge3.lines("a\n"));
    assertEquals(List.of(), Merge3.lines(""));
  }

  @Test
  public void unrelatedHugeTextsAreKeptWholeWithoutStalling() {
    StringBuilder base = new StringBuilder("same start\n");
    StringBuilder ours = new StringBuilder("same start\n");
    StringBuilder theirs = new StringBuilder("same start\n");
    for (int i = 0; i < 100_000; i++) {
      base.append("base ").append(i).append('\n');
      ours.append("ours ").append(i).append('\n');
      theirs.append("theirs ").append(i).append('\n');
    }
    long start = System.nanoTime();
    Merge3.Result result = Merge3.merge(base.toString(), ours.toString(), theirs.toString());
    assertTrue("Took " + (System.nanoTime() - start) / 1_000_000 + " ms", System.nanoTime() - start < 10_000_000_000L);
    String start2 = "same start\n";
    assertEquals(new Merge3.Result(start2 + M + ours.substring(start2.length()) + theirs.substring(start2.length()), 1), result);
  }

  @Test
  public void mergesDistantEditsInALargeNote() {
    StringBuilder base = new StringBuilder();
    for (int i = 0; i < 100_000; i++) base.append("Line number ").append(i).append(" of a very large note.\n");
    String b = base.toString();
    String ours = b.replace("Line number 10 of", "Line number TEN of");
    String theirs = b.replace("Line number 99990 of", "Line number 99,990 of");
    String expected = ours.replace("Line number 99990 of", "Line number 99,990 of");
    assertClean(b, ours, theirs, expected);
  }

  /** Edits confined to different paragraphs, with an untouched paragraph between them, always merge to both edits. */
  @Test
  public void randomEditsToSeparateParagraphsAlwaysMerge() {
    Random random = new Random(7);
    for (int round = 0; round < 2_000; round++) {
      String first = randomParagraph(random);
      String middle = randomParagraph(random);
      String last = randomParagraph(random);
      String editedFirst = edit(random, first);
      String editedLast = edit(random, last);
      String base = first + "\n\n" + middle + "\n\n" + last;
      assertClean(base, editedFirst + "\n\n" + middle + "\n\n" + last, first + "\n\n" + middle + "\n\n" + editedLast,
          editedFirst + "\n\n" + middle + "\n\n" + editedLast);
    }
  }

  /**
   * Whatever the texts: a side equal to the base yields the other, a merge without clashes doesn't
   * depend on the side, and no word either side added is lost.
   */
  @Test
  public void randomTextsKeepTheMergeInvariants() {
    Random random = new Random(11);
    for (int round = 0; round < 3_000; round++) {
      String base = randomParagraph(random);
      String ours = random.nextBoolean() ? edit(random, base) : randomParagraph(random);
      String theirs = random.nextBoolean() ? edit(random, base) : randomParagraph(random);

      Merge3.Result result = Merge3.merge(base, ours, theirs);
      if (result.clashes() == 0) assertEquals(result, Merge3.merge(base, theirs, ours));
      for (String side : List.of(ours, theirs)) {
        Map<String, Integer> added = words(side);
        words(base).forEach((word, count) -> added.merge(word, -count, Integer::sum));
        // Merged next to other text, an added word may no longer stand alone: it must still be there.
        added.forEach((word, count) -> assertTrue(word + " lost merging " + base + " / " + ours + " / " + theirs,
            count <= 0 || result.text().contains(word)));
      }
      assertClean(base, base, theirs, theirs);
      assertClean(base, ours, base, ours);
    }
  }

  private static Map<String, Integer> words(String text) {
    Map<String, Integer> words = new HashMap<>();
    for (String token : Merge3.tokens(text)) if (!token.isBlank()) words.merge(token, 1, Integer::sum);
    return words;
  }

  private static final String[] WORDS = {"the", "cat", "sat", "on", "a", "mat", "and", "😀", "café", "dog", "ran", ",", "."};

  private static String randomParagraph(Random random) {
    List<String> lines = new ArrayList<>();
    int lineCount = 1 + random.nextInt(4);
    for (int l = 0; l < lineCount; l++) {
      StringBuilder line = new StringBuilder();
      int words = 1 + random.nextInt(8);
      for (int w = 0; w < words; w++) {
        if (w > 0) line.append(' ');
        line.append(WORDS[random.nextInt(WORDS.length)]);
      }
      lines.add(line.toString());
    }
    return String.join("\n", lines);
  }

  /** Inserts, deletes or replaces one random word. */
  private static String edit(Random random, String text) {
    List<String> tokens = new ArrayList<>(Merge3.tokens(text));
    int index = random.nextInt(tokens.size() + 1);
    String word = "edit" + random.nextInt(100);
    switch (random.nextInt(3)) {
      case 0 -> tokens.add(index, word + " ");
      case 1 -> {
        if (index < tokens.size()) tokens.remove(index);
        else tokens.add(word);
      }
      default -> {
        if (index < tokens.size()) tokens.set(index, word);
        else tokens.add(word);
      }
    }
    return String.join("", tokens);
  }
}
