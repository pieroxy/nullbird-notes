package net.pieroxy.notes.totp;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TotpTest {
  /** The RFCs' test key. */
  private static final byte[] KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

  @Test
  public void hotpMatchesRfc4226AppendixD() {
    String[] expected = {"755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489"};
    for (int counter = 0; counter < expected.length; counter++) assertEquals("counter " + counter, expected[counter], Totp.code(KEY, counter));
  }

  @Test
  public void totpMatchesRfc6238AppendixBForSha1() {
    long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
    String[] expected = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
    for (int i = 0; i < times.length; i++) {
      assertEquals("time " + times[i], expected[i], Totp.code(KEY, Totp.step(Instant.ofEpochSecond(times[i])), 8));
    }
  }

  @Test
  public void base32MatchesRfc4648UnpaddedAndReadsAsTyped() {
    String[] plain = {"", "f", "fo", "foo", "foob", "fooba", "foobar"};
    String[] encoded = {"", "MY", "MZXQ", "MZXW6", "MZXW6YQ", "MZXW6YTB", "MZXW6YTBOI"};
    for (int i = 0; i < plain.length; i++) {
      assertEquals(encoded[i], Base32.encode(plain[i].getBytes(StandardCharsets.US_ASCII)));
      assertEquals(plain[i], new String(Base32.decode(encoded[i]), StandardCharsets.US_ASCII));
    }
    assertArrayEquals(KEY, Base32.decode(Base32.encode(KEY)));
    assertEquals("foobar", new String(Base32.decode("mzxw 6ytb-oi======"), StandardCharsets.US_ASCII));
    try {
      Base32.decode("MZXW1");
      fail();
    } catch (IllegalArgumentException e) {
      // 1 isn't in the alphabet.
    }
  }

  @Test
  public void aNewSecretIs160RandomBits() {
    String secret = Totp.newSecret();
    assertEquals(32, secret.length());
    assertEquals(Totp.SECRET_BYTES, Base32.decode(secret).length);
    assertTrue(!secret.equals(Totp.newSecret()));
  }

  @Test
  public void verifyAcceptsOneStepEitherSideOnly() {
    String secret = Base32.encode(KEY);
    Instant now = Instant.ofEpochSecond(1_800_000_000L);
    long step = Totp.step(now);

    assertEquals(Long.valueOf(step), Totp.verify(secret, Totp.code(KEY, step), now, Long.MIN_VALUE));
    assertEquals(Long.valueOf(step - 1), Totp.verify(secret, Totp.code(KEY, step - 1), now, Long.MIN_VALUE));
    assertEquals(Long.valueOf(step + 1), Totp.verify(secret, Totp.code(KEY, step + 1), now, Long.MIN_VALUE));
    assertNull(Totp.verify(secret, Totp.code(KEY, step - 2), now, Long.MIN_VALUE));
    assertNull(Totp.verify(secret, Totp.code(KEY, step + 2), now, Long.MIN_VALUE));
  }

  @Test
  public void aCodeWorksOnceAndNoOlderOneAfterIt() {
    String secret = Base32.encode(KEY);
    Instant now = Instant.ofEpochSecond(1_800_000_000L);
    long step = Totp.step(now);

    assertNull("already used", Totp.verify(secret, Totp.code(KEY, step), now, step));
    assertNull("older than the last used", Totp.verify(secret, Totp.code(KEY, step - 1), now, step));
    assertEquals(Long.valueOf(step + 1), Totp.verify(secret, Totp.code(KEY, step + 1), now, step));
  }

  @Test
  public void spacesAreIgnoredAndOtherShapesRefused() {
    String secret = Base32.encode(KEY);
    Instant now = Instant.ofEpochSecond(1_800_000_000L);
    String code = Totp.code(KEY, Totp.step(now));

    assertEquals(Long.valueOf(Totp.step(now)), Totp.verify(secret, code.substring(0, 3) + " " + code.substring(3), now, Long.MIN_VALUE));
    assertNull(Totp.verify(secret, code.substring(1), now, Long.MIN_VALUE));
    assertNull(Totp.verify(secret, code + "0", now, Long.MIN_VALUE));
    assertNull(Totp.verify(secret, null, now, Long.MIN_VALUE));
  }

  @Test
  public void stepsBeforeTheEpochRoundDown() {
    assertEquals(-1, Totp.step(Instant.ofEpochSecond(-1)));
  }

  @Test
  public void offsetStepsFindsTheNearestMatchWithinTheLimit() {
    String secret = Base32.encode(KEY);
    Instant now = Instant.ofEpochSecond(1_800_000_000L);
    long step = Totp.step(now);

    assertEquals(Long.valueOf(4), Totp.offsetSteps(secret, Totp.code(KEY, step + 4), now, 20));
    assertEquals(Long.valueOf(-20), Totp.offsetSteps(secret, Totp.code(KEY, step - 20), now, 20));
    assertNull(Totp.offsetSteps(secret, Totp.code(KEY, step + 21), now, 20));
  }
}
