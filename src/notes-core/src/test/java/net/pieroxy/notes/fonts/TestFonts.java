package net.pieroxy.notes.fonts;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Deflater;

/** Font files made up for tests: only the tables FontInspector reads, enough for it and for a browser to tell the format. */
public final class TestFonts {
  private TestFonts() {}

  /** A TrueType font: its weight, italic flag, family name; variable over weights when {@code weightMin} isn't null. */
  public static byte[] ttf(int weight, boolean italic, String family, Integer weightMin, Integer weightMax) {
    return sfnt(0x00010000, tables(weight, italic, family, weightMin, weightMax));
  }

  public static byte[] otf(int weight, boolean italic, String family) {
    return sfnt(tag("OTTO"), tables(weight, italic, family, null, null));
  }

  /** As {@link #ttf}, wrapped as WOFF, its tables compressed. */
  public static byte[] woff(int weight, boolean italic, String family, Integer weightMin, Integer weightMax) {
    Map<String, byte[]> tables = tables(weight, italic, family, weightMin, weightMax);
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    ByteBuffer directory = ByteBuffer.allocate(20 * tables.size());
    int offset = 44 + 20 * tables.size();
    for (Map.Entry<String, byte[]> table : tables.entrySet()) {
      byte[] packed = deflate(table.getValue());
      directory.putInt(tag(table.getKey())).putInt(offset).putInt(packed.length).putInt(table.getValue().length).putInt(0);
      data.writeBytes(packed);
      offset += packed.length;
    }
    ByteBuffer header = ByteBuffer.allocate(44);
    header.putInt(tag("wOFF")).putInt(0x00010000).putInt(offset).putShort((short) tables.size());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(header.array());
    out.writeBytes(directory.array());
    out.writeBytes(data.toByteArray());
    return out.toByteArray();
  }

  /**
   * A WOFF2 file's header and table list, without the tables (Brotli-compressed, FontInspector
   * doesn't read them): glyf and loca transformed as usual, one table with a tag of its own, and
   * an fvar when variable.
   */
  public static byte[] woff2(boolean variable) {
    ByteArrayOutputStream list = new ByteArrayOutputStream();
    int count = 0;
    list.write(10); list.writeBytes(base128(30000)); list.writeBytes(base128(12000)); count++; // glyf, transformed: two lengths
    list.write(11); list.writeBytes(base128(400)); list.writeBytes(base128(0)); count++;       // loca, transformed
    list.write(6); list.writeBytes(base128(96)); count++;                                     // OS/2
    list.write(63); list.writeBytes("ZZZZ".getBytes(StandardCharsets.ISO_8859_1)); list.writeBytes(base128(200)); count++;
    if (variable) {
      list.write(47); list.writeBytes(base128(64)); count++;                                  // fvar
    }
    list.write(0); list.writeBytes(base128(300)); count++;                                    // cmap
    ByteBuffer header = ByteBuffer.allocate(48);
    header.putInt(tag("wOF2")).putInt(0x00010000).putInt(48 + list.size()).putShort((short) count);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(header.array());
    out.writeBytes(list.toByteArray());
    out.writeBytes(new byte[16]);
    return out.toByteArray();
  }

  private static Map<String, byte[]> tables(int weight, boolean italic, String family, Integer weightMin, Integer weightMax) {
    Map<String, byte[]> tables = new LinkedHashMap<>();
    ByteBuffer os2 = ByteBuffer.allocate(96);
    os2.putShort(0, (short) 4).putShort(4, (short) weight).putShort(62, (short) (italic ? 1 : 0x40));
    tables.put("OS/2", os2.array());
    byte[] name = family.getBytes(StandardCharsets.UTF_16BE);
    ByteBuffer names = ByteBuffer.allocate(6 + 12 + name.length);
    names.putShort((short) 0).putShort((short) 1).putShort((short) 18)
        .putShort((short) 3).putShort((short) 1).putShort((short) 0x409).putShort((short) 1).putShort((short) name.length).putShort((short) 0)
        .put(name);
    tables.put("name", names.array());
    ByteBuffer post = ByteBuffer.allocate(32);
    post.putInt(0, 0x00030000).putInt(12, family.contains("Mono") ? 1 : 0);
    tables.put("post", post.array());
    if (weightMin != null) {
      ByteBuffer fvar = ByteBuffer.allocate(16 + 2 * 20);
      fvar.putShort((short) 1).putShort((short) 0).putShort((short) 16).putShort((short) 2).putShort((short) 2).putShort((short) 20)
          .putShort((short) 0).putShort((short) 0);
      fvar.putInt(tag("wdth")).putInt(75 << 16).putInt(100 << 16).putInt(100 << 16).putShort((short) 0).putShort((short) 256);
      fvar.putInt(tag("wght")).putInt(weightMin << 16).putInt(400 << 16).putInt(weightMax << 16).putShort((short) 0).putShort((short) 257);
      tables.put("fvar", fvar.array());
    }
    return tables;
  }

  private static byte[] sfnt(int signature, Map<String, byte[]> tables) {
    ByteBuffer header = ByteBuffer.allocate(12 + 16 * tables.size());
    header.putInt(signature).putShort((short) tables.size()).putShort((short) 0).putShort((short) 0).putShort((short) 0);
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    int offset = 12 + 16 * tables.size();
    for (Map.Entry<String, byte[]> table : tables.entrySet()) {
      header.putInt(tag(table.getKey())).putInt(0).putInt(offset).putInt(table.getValue().length);
      data.writeBytes(table.getValue());
      offset += table.getValue().length;
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(header.array());
    out.writeBytes(data.toByteArray());
    return out.toByteArray();
  }

  private static byte[] base128(int value) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int shift = 28;
    while (shift > 0 && (value >>> shift) == 0) shift -= 7;
    for (; shift > 0; shift -= 7) out.write(0x80 | ((value >>> shift) & 0x7F));
    out.write(value & 0x7F);
    return out.toByteArray();
  }

  private static byte[] deflate(byte[] data) {
    Deflater deflater = new Deflater();
    deflater.setInput(data);
    deflater.finish();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
    deflater.end();
    return out.toByteArray();
  }

  private static int tag(String tag) {
    return ByteBuffer.wrap(tag.getBytes(StandardCharsets.ISO_8859_1)).getInt();
  }
}
