package net.pieroxy.notes.fonts;

import net.pieroxy.notes.api.metadata.InvalidInputException;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class FontInspectorTest {
  @Test
  public void aTrueTypeFontSaysItsWeightStyleAndName() {
    FontInspector.Inspection font = FontInspector.inspect(TestFonts.ttf(700, true, "Some Sans", null, null));
    assertEquals(FontInspector.Format.TRUETYPE, font.format());
    assertFalse(font.variable());
    assertNull(font.weightMin());
    assertEquals(Integer.valueOf(700), font.weight());
    assertEquals(Boolean.TRUE, font.italic());
    assertEquals("Some Sans", font.familyName());
    assertEquals(Boolean.FALSE, font.monospace());
    assertEquals(Boolean.TRUE, FontInspector.inspect(TestFonts.otf(400, false, "Some Mono")).monospace());
    assertEquals(FontStyle.BOLD_ITALIC, FontStyle.of(font.weight(), font.italic()));
  }

  @Test
  public void aVariableFontSaysOverWhichWeights() {
    FontInspector.Inspection font = FontInspector.inspect(TestFonts.ttf(400, false, "Flex", 100, 900));
    assertTrue(font.variable());
    assertEquals(Integer.valueOf(100), font.weightMin());
    assertEquals(Integer.valueOf(900), font.weightMax());
    assertEquals(FontStyle.REGULAR, FontStyle.of(font.weight(), font.italic()));
  }

  @Test
  public void openTypeToo() {
    FontInspector.Inspection font = FontInspector.inspect(TestFonts.otf(400, false, "Some Serif"));
    assertEquals(FontInspector.Format.OPENTYPE, font.format());
    assertEquals("Some Serif", font.familyName());
  }

  @Test
  public void aWoffIsReadUnpacked() {
    FontInspector.Inspection font = FontInspector.inspect(TestFonts.woff(300, true, "Light Web", 200, 700));
    assertEquals(FontInspector.Format.WOFF, font.format());
    assertTrue(font.variable());
    assertEquals(Integer.valueOf(200), font.weightMin());
    assertEquals(Integer.valueOf(700), font.weightMax());
    assertEquals(Integer.valueOf(300), font.weight());
    assertEquals(Boolean.TRUE, font.italic());
    assertEquals("Light Web", font.familyName());
  }

  @Test
  public void aWoff2SaysOnlyWhetherItsVariable() {
    FontInspector.Inspection variable = FontInspector.inspect(TestFonts.woff2(true));
    assertEquals(FontInspector.Format.WOFF2, variable.format());
    assertTrue(variable.variable());
    assertNull(variable.weightMin());
    assertNull(variable.weight());
    assertNull(variable.familyName());
    assertNull(variable.monospace());
    assertFalse(FontInspector.inspect(TestFonts.woff2(false)).variable());
  }

  @Test
  public void aCollectionIsRefusedForWhatItIs() {
    byte[] ttc = ByteBuffer.allocate(64).put("ttcf".getBytes(StandardCharsets.ISO_8859_1)).array();
    assertTrue(assertThrows(InvalidInputException.class, () -> FontInspector.inspect(ttc)).getMessage().contains("collection"));
  }

  @Test
  public void anythingElseIsNotAFont() {
    assertTrue(assertThrows(InvalidInputException.class, () -> FontInspector.inspect("<html>hello there</html>".getBytes(StandardCharsets.UTF_8)))
        .getMessage().contains("isn't a font file"));
    assertThrows(InvalidInputException.class, () -> FontInspector.inspect(new byte[3]));
  }

  @Test
  public void aDamagedFontIsSaidSo() {
    byte[] font = TestFonts.ttf(400, false, "Cut", 100, 900);
    byte[] cut = Arrays.copyOf(font, 40);
    assertTrue(assertThrows(InvalidInputException.class, () -> FontInspector.inspect(cut)).getMessage().contains("damaged"));
    byte[] woff = TestFonts.woff(400, false, "Cut", null, null);
    byte[] cutWoff = Arrays.copyOf(woff, woff.length - 10);
    assertThrows(InvalidInputException.class, () -> FontInspector.inspect(cutWoff));
  }
}
