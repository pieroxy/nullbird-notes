package net.pieroxy.notes.config;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PasswordGeneratorTest {
  @Test
  public void sixteenCharactersFromTheAlphabetNeverTwiceTheSame() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      String password = PasswordGenerator.generate();
      assertEquals(PasswordGenerator.LENGTH, password.length());
      for (char c : password.toCharArray()) assertTrue(password, PasswordGenerator.ALPHABET.indexOf(c) >= 0);
      assertTrue(seen.add(password));
    }
  }

  @Test
  public void noLookAlikes() {
    for (char c : "0Oo1lI".toCharArray()) assertEquals(-1, PasswordGenerator.ALPHABET.indexOf(c));
  }
}
