package net.pieroxy.notes.fonts;

import net.pieroxy.notes.api.metadata.InvalidInputException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads what a font file is: its format, from its first bytes; and, where its tables can be read,
 * whether it's variable and over which weights, its weight and style, its family's name. TrueType
 * and OpenType files, and WOFF (zlib), have readable tables; WOFF2's are Brotli-compressed, which
 * the JDK can't read: only their list is, which tells a variable font (an fvar table) from another.
 * <p>
 * Only what the browser loads is accepted: a collection (.ttc) isn't, browsers use its first font only.
 */
public final class FontInspector {
  private FontInspector() {}

  /** As in a CSS {@code format()}: also the file's extension, see {@link #extension}. */
  public enum Format { WOFF2, WOFF, TRUETYPE, OPENTYPE }

  /**
   * @param variable     an fvar table: its axes vary the glyphs.
   * @param weightMin    its wght axis's range, if variable over weights and readable; else null.
   * @param weight       usWeightClass (400 regular, 700 bold), if readable.
   * @param italic       from fsSelection, if readable.
   * @param familyName   the typographic family's name, or the family's, if readable.
   * @param monospace    the post table's isFixedPitch, if readable.
   */
  public record Inspection(Format format, boolean variable, Integer weightMin, Integer weightMax, Integer weight,
                           Boolean italic, String familyName, Boolean monospace) {}

  /** 4-byte table tags, as an int. */
  private static final int FVAR = tag("fvar");
  private static final int OS2 = tag("OS/2");
  private static final int NAME = tag("name");
  private static final int POST = tag("post");
  private static final int WGHT = tag("wght");
  private static final java.util.Set<Integer> READ = java.util.Set.of(FVAR, OS2, NAME, POST);
  /** WOFF2's table tags by index (its spec, 5.1): a table's flags name one of these, or 63 for a tag that follows. */
  private static final List<String> WOFF2_KNOWN_TAGS = List.of("cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post",
      "cvt ", "fpgm", "glyf", "loca", "prep", "CFF ", "VORG", "EBDT", "EBLC", "gasp", "hdmx", "kern", "LTSH", "PCLT", "VDMX",
      "vhea", "vmtx", "BASE", "GDEF", "GPOS", "GSUB", "EBSC", "JSTF", "MATH", "CBDT", "CBLC", "COLR", "CPAL", "SVG ", "sbix",
      "acnt", "avar", "bdat", "bloc", "bsln", "cvar", "fdsc", "feat", "fmtx", "fvar", "gvar", "hsty", "just", "lcar", "mort",
      "morx", "opbd", "prop", "trak", "Zapf", "Silf", "Glat", "Gloc", "Feat", "Sill");
  /** No more than this unpacked, per table read from a WOFF: those read are small. */
  private static final int MAX_TABLE_BYTES = 1024 * 1024;

  public static String extension(Format format) {
    return switch (format) {
      case WOFF2 -> "woff2";
      case WOFF -> "woff";
      case TRUETYPE -> "ttf";
      case OPENTYPE -> "otf";
    };
  }

  public static String contentType(Format format) {
    return "font/" + extension(format);
  }

  /** @throws InvalidInputException if it's not a font a browser loads. */
  public static Inspection inspect(byte[] data) {
    if (data.length < 12) throw notAFont();
    int signature = ByteBuffer.wrap(data).getInt(0);
    try {
      if (signature == tag("wOF2")) return woff2(data);
      if (signature == tag("wOFF")) return tables(Format.WOFF, woffTables(data));
      if (signature == 0x00010000 || signature == tag("true")) return tables(Format.TRUETYPE, sfntTables(data));
      if (signature == tag("OTTO")) return tables(Format.OPENTYPE, sfntTables(data));
    } catch (RuntimeException e) {
      if (e instanceof InvalidInputException) throw e;
      throw new InvalidInputException("This font file is damaged: it can't be read.");
    }
    if (signature == tag("ttcf")) throw new InvalidInputException("This is a font collection (.ttc): browsers can't use one. Upload each font of it on its own.");
    throw notAFont();
  }

  private static InvalidInputException notAFont() {
    return new InvalidInputException("This isn't a font file a browser can use: upload a WOFF2, WOFF, TTF or OTF file.");
  }

  private static Inspection tables(Format format, Map<Integer, ByteBuffer> tables) {
    Integer weightMin = null, weightMax = null, weight = null;
    Boolean italic = null;
    ByteBuffer fvar = tables.get(FVAR);
    if (fvar != null) {
      int axesOffset = u16(fvar, 4);
      int axisCount = u16(fvar, 8);
      int axisSize = u16(fvar, 10);
      for (int i = 0; i < axisCount; i++) {
        int at = axesOffset + i * axisSize;
        if (fvar.getInt(at) == WGHT) {
          weightMin = (int) Math.round(fvar.getInt(at + 4) / 65536.0);
          weightMax = (int) Math.round(fvar.getInt(at + 12) / 65536.0);
        }
      }
    }
    ByteBuffer os2 = tables.get(OS2);
    if (os2 != null && os2.limit() >= 64) {
      weight = u16(os2, 4);
      italic = (u16(os2, 62) & 1) != 0;
    }
    ByteBuffer name = tables.get(NAME);
    ByteBuffer post = tables.get(POST);
    Boolean monospace = post != null && post.limit() >= 16 ? post.getInt(12) != 0 : null;
    return new Inspection(format, fvar != null, weightMin, weightMax, weight, italic, name == null ? null : familyName(name), monospace);
  }

  /** Typographic family (ID 16) over family (ID 1); Windows' Unicode names over Mac's Roman ones. */
  private static String familyName(ByteBuffer name) {
    int count = u16(name, 2);
    int strings = u16(name, 4);
    String best = null;
    int bestScore = -1;
    for (int i = 0; i < count; i++) {
      int at = 6 + i * 12;
      int platform = u16(name, at), encoding = u16(name, at + 2), id = u16(name, at + 6);
      int length = u16(name, at + 8), offset = u16(name, at + 10);
      if (id != 1 && id != 16) continue;
      boolean unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10));
      boolean roman = platform == 1 && encoding == 0;
      if (!unicode && !roman) continue;
      int score = (id == 16 ? 2 : 0) + (unicode ? 1 : 0);
      if (score <= bestScore) continue;
      byte[] bytes = new byte[length];
      name.get(strings + offset, bytes);
      String value = new String(bytes, unicode ? StandardCharsets.UTF_16BE : StandardCharsets.ISO_8859_1).strip();
      if (value.isEmpty()) continue;
      best = value;
      bestScore = score;
    }
    return best;
  }

  /** A TrueType or OpenType file's tables of interest, as they are in the file. */
  private static Map<Integer, ByteBuffer> sfntTables(byte[] data) {
    ByteBuffer file = ByteBuffer.wrap(data);
    int count = u16(file, 4);
    Map<Integer, ByteBuffer> tables = new HashMap<>();
    for (int i = 0; i < count; i++) {
      int at = 12 + i * 16;
      int tag = file.getInt(at);
      if (!READ.contains(tag)) continue;
      int offset = file.getInt(at + 8), length = file.getInt(at + 12);
      tables.put(tag, slice(data, offset, length));
    }
    return tables;
  }

  /** A WOFF file's tables of interest, unpacked. */
  private static Map<Integer, ByteBuffer> woffTables(byte[] data) {
    ByteBuffer file = ByteBuffer.wrap(data);
    int count = u16(file, 12);
    Map<Integer, ByteBuffer> tables = new HashMap<>();
    for (int i = 0; i < count; i++) {
      int at = 44 + i * 20;
      int tag = file.getInt(at);
      if (!READ.contains(tag)) continue;
      int offset = file.getInt(at + 4), packed = file.getInt(at + 8), length = file.getInt(at + 12);
      if (length < 0 || length > MAX_TABLE_BYTES) throw new IllegalArgumentException("table too large");
      tables.put(tag, packed == length ? slice(data, offset, length) : ByteBuffer.wrap(inflate(data, offset, packed, length)));
    }
    return tables;
  }

  /** Only which tables there are: their content is Brotli-compressed. */
  private static Inspection woff2(byte[] data) {
    ByteBuffer file = ByteBuffer.wrap(data);
    int count = u16(file, 12);
    int at = 48;
    boolean variable = false;
    for (int i = 0; i < count; i++) {
      int flags = file.get(at++) & 0xFF;
      int index = flags & 0x3F;
      String tag;
      if (index == 63) {
        tag = new String(data, at, 4, StandardCharsets.ISO_8859_1);
        at += 4;
      } else {
        tag = WOFF2_KNOWN_TAGS.get(index);
      }
      if (tag.equals("fvar")) variable = true;
      at = skipBase128(data, at);
      int transform = flags >> 6;
      boolean glyfOrLoca = tag.equals("glyf") || tag.equals("loca");
      if (glyfOrLoca ? transform == 0 : transform != 0) at = skipBase128(data, at);
    }
    return new Inspection(Format.WOFF2, variable, null, null, null, null, null, null);
  }

  /** WOFF2's UIntBase128: up to 5 bytes, the last without its high bit. */
  private static int skipBase128(byte[] data, int at) {
    for (int i = 0; i < 5; i++) {
      if ((data[at++] & 0x80) == 0) return at;
    }
    throw new IllegalArgumentException("bad UIntBase128");
  }

  private static byte[] inflate(byte[] data, int offset, int packed, int length) {
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(data, offset, packed);
      ByteArrayOutputStream out = new ByteArrayOutputStream(length);
      byte[] buffer = new byte[8192];
      while (!inflater.finished()) {
        int n = inflater.inflate(buffer);
        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw new IllegalArgumentException("truncated table");
        out.write(buffer, 0, n);
        if (out.size() > length) throw new IllegalArgumentException("table larger than said");
      }
      return out.toByteArray();
    } catch (DataFormatException e) {
      throw new IllegalArgumentException(e);
    } finally {
      inflater.end();
    }
  }

  private static ByteBuffer slice(byte[] data, int offset, int length) {
    if (offset < 0 || length < 0 || (long) offset + length > data.length) throw new IllegalArgumentException("table outside the file");
    return ByteBuffer.wrap(data, offset, length).slice();
  }

  private static int u16(ByteBuffer buffer, int at) {
    return buffer.getShort(at) & 0xFFFF;
  }

  private static int tag(String tag) {
    return ByteBuffer.wrap(tag.getBytes(StandardCharsets.ISO_8859_1)).getInt();
  }
}
