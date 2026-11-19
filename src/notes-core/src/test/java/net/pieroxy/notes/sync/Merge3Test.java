package net.pieroxy.notes.sync;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Merge3Test {
  private static void assertClean(String base, String ours, String theirs, String expected) {
    Merge3.Result result = Merge3.merge(base, ours, theirs);
    assertTrue("Expected a clean merge, got " + result, result.isClean());
    assertEquals(expected, result.preferringOurs());
    assertEquals(expected, result.preferringTheirs());
  }

  private static void assertConflict(String base, String ours, String theirs, int conflicts, String preferringOurs, String preferringTheirs) {
    Merge3.Result result = Merge3.merge(base, ours, theirs);
    assertEquals(new Merge3.Result(preferringOurs, preferringTheirs, conflicts), result);
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
  public void theSameChangeOnBothSidesIsNotAConflict() {
    assertClean("a\nb\nc\n", "a\nB\nc\nd\n", "a\nB\nc\n", "a\nB\nc\nd\n");
    assertClean("one two", "one 2", "one 2", "one 2");
  }

  @Test
  public void differentChangesToTheSameWordConflict() {
    assertConflict("I like cats.", "I like dogs.", "I like birds.", 1,
        "I like dogs.", "I like birds.");
  }

  @Test
  public void cleanChangesAreAppliedToBothSidesOfAConflict() {
    assertConflict("Title\n\nI like cats.\n\nThe end.\n",
        "Title!\n\nI like dogs.\n\nThe end.\n",
        "Title\n\nI like birds.\n\nThe end, really.\n", 1,
        "Title!\n\nI like dogs.\n\nThe end, really.\n",
        "Title!\n\nI like birds.\n\nThe end, really.\n");
  }

  @Test
  public void countsEveryConflict() {
    Merge3.Result result = Merge3.merge("a b c d e", "A b C d e", "1 b 3 d e");
    assertEquals(2, result.conflicts());
    assertEquals("A b C d e", result.preferringOurs());
    assertEquals("1 b 3 d e", result.preferringTheirs());
  }

  @Test
  public void insertionsAtTheSamePlaceConflictUnlessIdentical() {
    assertConflict("start end", "start mine end", "start yours end", 1, "start mine end", "start yours end");
    assertClean("start end", "start same end", "start same end", "start same end");
    assertConflict("", "mine", "yours", 1, "mine", "yours");
  }

  @Test
  public void insertionsAtDifferentPlacesMerge() {
    assertClean("a\nb\nc\n", "x\na\nb\nc\n", "a\nb\nc\ny\n", "x\na\nb\nc\ny\n");
    assertClean("one two three", "zero one two three", "one two three four", "zero one two three four");
  }

  @Test
  public void anInsertionRightWhereTheOtherSideChangesConflicts() {
    assertConflict("keep this here", "keep NEW this here", "keep that here", 1, "keep NEW this here", "keep that here");
  }

  @Test
  public void deletionsMergeWithChangesElsewhere() {
    assertClean("a\nb\nc\nd\n", "a\nc\nd\n", "a\nb\nc\nD\n", "a\nc\nD\n");
    assertClean("a\nb\nc\n", "a\nc\n", "a\nc\n", "a\nc\n");
  }

  @Test
  public void deletingWhatTheOtherSideEditedConflicts() {
    assertConflict("a\nb\nc\n", "a\nc\n", "a\nB\nc\n", 1, "a\nc\n", "a\nB\nc\n");
  }

  @Test
  public void handlesTextWithoutTrailingNewline() {
    assertClean("a\nb", "A\nb", "a\nB", "A\nB");
    assertClean("a\nb", "a\nb\n", "A\nb", "A\nb\n");
  }

  @Test
  public void keepsSurrogatePairsAndAccentsWhole() {
    assertClean("café 😀 noir", "café 😁 noir", "café 😀 crème", "café 😁 crème");
    assertConflict("x 😀 y", "x 😁 y", "x 🙂 y", 1, "x 😁 y", "x 🙂 y");
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
  public void unrelatedHugeTextsAreAConflictAsAWholeWithoutStalling() {
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
    assertEquals(1, result.conflicts());
    assertEquals(ours.toString(), result.preferringOurs());
    assertEquals(theirs.toString(), result.preferringTheirs());
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

  /** Whatever the texts: swapping sides swaps the results, and a side equal to the base yields the other. */
  @Test
  public void randomTextsKeepTheMergeInvariants() {
    Random random = new Random(11);
    for (int round = 0; round < 3_000; round++) {
      String base = randomParagraph(random);
      String ours = random.nextBoolean() ? edit(random, base) : randomParagraph(random);
      String theirs = random.nextBoolean() ? edit(random, base) : randomParagraph(random);

      Merge3.Result result = Merge3.merge(base, ours, theirs);
      Merge3.Result swapped = Merge3.merge(base, theirs, ours);
      assertEquals(result.preferringOurs(), swapped.preferringTheirs());
      assertEquals(result.preferringTheirs(), swapped.preferringOurs());
      assertEquals(result.conflicts(), swapped.conflicts());
      assertEquals(result.isClean(), result.preferringOurs().equals(result.preferringTheirs()));
      assertClean(base, base, theirs, theirs);
      assertClean(base, ours, base, ours);
    }
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
