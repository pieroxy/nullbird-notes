package net.pieroxy.notes.sync;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class SpliceTest {
  private static void assertSplice(String from, String to, int prefix, int suffix, String middle) {
    Splice splice = Splice.compute(from, to);
    assertEquals(new Splice(prefix, suffix, middle), splice);
    assertEquals(to, splice.apply(from));
  }

  @Test
  public void identicalTextsGiveAnEmptySplice() {
    assertSplice("same", "same", 4, 0, "");
    assertSplice("", "", 0, 0, "");
  }

  @Test
  public void insertions() {
    assertSplice("world", "hello world", 0, 5, "hello ");
    assertSplice("held", "hello world", 3, 1, "lo worl");
    assertSplice("hello", "hello world", 5, 0, " world");
    assertSplice("", "new", 0, 0, "new");
  }

  @Test
  public void deletionsAndReplacements() {
    assertSplice("hello world", "hello", 5, 0, "");
    assertSplice("hello world", "", 0, 0, "");
    assertSplice("the cat sat", "the dog sat", 4, 4, "dog");
  }

  @Test
  public void repeatedCharactersDoNotOverlapPrefixAndSuffix() {
    assertSplice("aaa", "aaaa", 3, 0, "a");
    assertSplice("aaaa", "aaa", 3, 0, "");
    assertSplice("abab", "ab", 2, 0, "");
  }

  @Test
  public void neverSplitsASurrogatePair() {
    // U+1F600 and U+1F601 share their high surrogate: the common prefix must not keep it alone.
    assertSplice("a😀b", "a😁b", 1, 1, "😁");
    // U+1F600 and U+1FA00 share their low surrogate: the common suffix must not keep it alone.
    assertSplice("x😀", "x🨀", 1, 0, "🨀");
    assertSplice("😀", "😀😀", 2, 0, "😀");
  }

  @Test
  public void refusesASpliceThatDoesNotFit() {
    assertThrows(IllegalArgumentException.class, () -> new Splice(3, 3, "").apply("short"));
    assertThrows(IllegalArgumentException.class, () -> new Splice(-1, 0, "").apply("short"));
    assertThrows(IllegalArgumentException.class, () -> new Splice(0, 0, null).apply("short"));
  }

  @Test
  public void randomEditsRoundTripWithoutSplittingPairs() {
    String[] alphabet = {"a", "b", " ", "\n", "é", "😀", "😁", "🀀"};
    Random random = new Random(42);
    for (int i = 0; i < 20_000; i++) {
      String from = randomText(random, alphabet);
      String to = randomText(random, alphabet);
      Splice splice = Splice.compute(from, to);
      assertEquals(to, splice.apply(from));
      assertFalse(splitsAPair(from, splice.prefixLength()));
      assertFalse(splitsAPair(from, from.length() - splice.suffixLength()));
    }
  }

  private static String randomText(Random random, String[] alphabet) {
    StringBuilder sb = new StringBuilder();
    int length = random.nextInt(12);
    for (int i = 0; i < length; i++) sb.append(alphabet[random.nextInt(alphabet.length)]);
    return sb.toString();
  }

  private static boolean splitsAPair(String s, int index) {
    return index > 0 && index < s.length() && Character.isHighSurrogate(s.charAt(index - 1)) && Character.isLowSurrogate(s.charAt(index));
  }
}
