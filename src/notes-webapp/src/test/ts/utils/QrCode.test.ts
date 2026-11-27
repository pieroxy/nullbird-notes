import {
  alignmentPositions, dataCodewords, dataCodewordsFor, eccFor, encodeQr, formatBits, functionModules, maskHit, QrCode, versionBits,
} from "../../../ts/utils/QrCode";

describe("QrCode against the standard's values", () => {
  test("error correction: the 1-M examples of HELLO WORLD and 01234567", () => {
    expect(eccFor([32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17], 10))
      .toEqual([196, 35, 39, 119, 235, 215, 231, 226, 93, 23]);
    expect(eccFor([0x10, 0x20, 0x0c, 0x56, 0x61, 0x80, 0xec, 0x11, 0xec, 0x11, 0xec, 0x11, 0xec, 0x11, 0xec, 0x11], 10))
      .toEqual([0xa5, 0x24, 0xd4, 0xc1, 0xed, 0x36, 0xc7, 0x87, 0x2c, 0x55]);
  });

  test("format bits for level M", () => {
    expect(formatBits(0)).toBe(0b101010000010010);
    expect(formatBits(1)).toBe(0b101000100100101);
    expect(formatBits(7)).toBe(0b100101010100000);
  });

  test("version bits", () => {
    expect(versionBits(7)).toBe(0x07c94);
    expect(versionBits(8)).toBe(0x085bc);
    expect(versionBits(40)).toBe(0x28c69);
  });

  test("data capacity at level M", () => {
    expect([1, 5, 7, 10, 40].map(dataCodewords)).toEqual([16, 86, 124, 216, 2334]);
  });

  test("alignment pattern positions", () => {
    expect(alignmentPositions(1)).toEqual([]);
    expect(alignmentPositions(2)).toEqual([6, 18]);
    expect(alignmentPositions(7)).toEqual([6, 22, 38]);
    expect(alignmentPositions(32)).toEqual([6, 34, 60, 86, 112, 138]);
  });
});

/** Reads a symbol back as a scanner would, for symbols whose blocks are all the same length. */
function read(qr: QrCode): { text: string; blocksValid: boolean } {
  const { size, modules, version } = qr;
  const bit = (x: number, y: number) => (modules[y][x] ? 1 : 0);
  let format = 0;
  for (let i = 14; i >= 9; i--) format = (format << 1) | bit(14 - i, 8);
  format = (format << 1) | bit(7, 8);
  format = (format << 1) | bit(8, 8);
  format = (format << 1) | bit(8, 7);
  for (let i = 5; i >= 0; i--) format = (format << 1) | bit(8, i);
  expect(format).toBe(formatBits(qr.mask));
  let second = 0;
  for (let i = 14; i >= 8; i--) second = (second << 1) | bit(8, size - 15 + i);
  for (let i = 7; i >= 0; i--) second = (second << 1) | bit(size - 1 - i, 8);
  expect(second).toBe(format);

  const isFunction = functionModules(version);
  const bits: number[] = [];
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let vert = 0; vert < size; vert++) {
      for (let j = 0; j < 2; j++) {
        const x = right - j;
        const y = ((right + 1) & 2) === 0 ? size - 1 - vert : vert;
        if (!isFunction[y][x]) bits.push(bit(x, y) ^ (maskHit(qr.mask, x, y) ? 1 : 0));
      }
    }
  }
  const codewords: number[] = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) codewords.push(bits.slice(i, i + 8).reduce((b, v) => (b << 1) | v, 0));

  // As the standard interleaves: data codewords block by block, the long blocks' last one after, then ECC alike.
  const blocks = BLOCKS[version];
  const total = codewords.length;
  const eccPerBlock = (total - dataCodewords(version)) / blocks;
  const shortBlocks = blocks - (total % blocks);
  const shortData = Math.floor(total / blocks) - eccPerBlock;
  const data: number[][] = Array.from({ length: blocks }, () => []);
  const ecc: number[][] = Array.from({ length: blocks }, () => []);
  let k = 0;
  for (let i = 0; i <= shortData; i++) {
    for (let b = 0; b < blocks; b++) if (i < shortData || b >= shortBlocks) data[b].push(codewords[k++]);
  }
  for (let i = 0; i < eccPerBlock; i++) for (let b = 0; b < blocks; b++) ecc[b].push(codewords[k++]);
  expect(k).toBe(total);
  const blocksValid = data.every((block, b) => JSON.stringify(eccFor(block, eccPerBlock)) === JSON.stringify(ecc[b]));

  const stream = data.flat();
  const streamBits = stream.flatMap((byte) => [7, 6, 5, 4, 3, 2, 1, 0].map((i) => (byte >>> i) & 1));
  let at = 0;
  const take = (n: number) => {
    let v = 0;
    for (let i = 0; i < n; i++) v = (v << 1) | streamBits[at++];
    return v;
  };
  expect(take(4)).toBe(0b0100);
  const length = take(version < 10 ? 8 : 16);
  const bytes = Array.from({ length }, () => take(8));
  return { text: new TextDecoder().decode(new Uint8Array(bytes)), blocksValid };
}

/** Blocks at level M, by version: the standard's table, up to what's read back here. */
const BLOCKS = [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5];

describe("QrCode, read back", () => {
  test.each([
    // At most 14 bytes in version 1, 62 in version 4 (2 blocks), 106 in 6 (4 blocks), 152 in 8 (4, unequal), 213 in 10.
    ["x".repeat(14), 1],
    ["x".repeat(15), 2],
    ["x".repeat(62), 4],
    ["x".repeat(63), 5],
    ["otpauth://totp/Notes:alice%40notes.example.com?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=Notes", 6],
    ["x".repeat(152), 8],
    ["x".repeat(153), 9],
    ["x".repeat(213), 10],
  ])("%s at version %i", (text, version) => {
    const qr = encodeQr(text);
    expect(qr.version).toBe(version);
    expect(qr.size).toBe(version * 4 + 17);
    const result = read(qr);
    expect(result.text).toBe(text);
    expect(result.blocksValid).toBe(true);
  });

  test("finder patterns in three corners, timing between them", () => {
    const qr = encodeQr("Notes");
    const finder = ["1111111", "1000001", "1011101", "1011101", "1011101", "1000001", "1111111"];
    const at = (x0: number, y0: number) => finder.every((row, dy) => [...row].every((c, dx) => qr.modules[y0 + dy][x0 + dx] === (c === "1")));
    expect(at(0, 0) && at(qr.size - 7, 0) && at(0, qr.size - 7)).toBe(true);
    for (let i = 8; i < qr.size - 8; i++) expect(qr.modules[6][i]).toBe(i % 2 === 0);
  });

  test("non-ASCII text is encoded as UTF-8", () => {
    expect(read(encodeQr("été ✓")).text).toBe("été ✓");
  });

  test("padding alternates EC and 11", () => {
    expect(dataCodewordsFor([0x41], 1).slice(3)).toEqual([0xec, 0x11, 0xec, 0x11, 0xec, 0x11, 0xec, 0x11, 0xec, 0x11, 0xec, 0x11, 0xec]);
  });
});
