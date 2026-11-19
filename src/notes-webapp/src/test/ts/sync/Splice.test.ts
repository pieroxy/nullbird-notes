import { applySplice, computeSplice, fullSplice } from "../../../ts/sync/Splice";

const HIGH = 0xd83d;
const grinning = String.fromCharCode(HIGH, 0xde00); // U+1F600
const beaming = String.fromCharCode(HIGH, 0xde01); // U+1F601, same high surrogate
const sameLow = String.fromCharCode(0xd83e, 0xde00); // U+1FA00, same low surrogate as U+1F600

function expectSplice(from: string, to: string, prefixLength: number, suffixLength: number, middle: string) {
  const splice = computeSplice(from, to);
  expect(splice).toEqual({ prefixLength, suffixLength, middle });
  expect(applySplice(splice, from)).toBe(to);
}

// The same cases as SpliceTest.java: both sides must compute identical splices.
describe("computeSplice", () => {
  test("identical texts give an empty splice", () => {
    expectSplice("same", "same", 4, 0, "");
    expectSplice("", "", 0, 0, "");
  });

  test("insertions", () => {
    expectSplice("world", "hello world", 0, 5, "hello ");
    expectSplice("held", "hello world", 3, 1, "lo worl");
    expectSplice("hello", "hello world", 5, 0, " world");
    expectSplice("", "new", 0, 0, "new");
  });

  test("deletions and replacements", () => {
    expectSplice("hello world", "hello", 5, 0, "");
    expectSplice("hello world", "", 0, 0, "");
    expectSplice("the cat sat", "the dog sat", 4, 4, "dog");
  });

  test("repeated characters don't overlap prefix and suffix", () => {
    expectSplice("aaa", "aaaa", 3, 0, "a");
    expectSplice("aaaa", "aaa", 3, 0, "");
    expectSplice("abab", "ab", 2, 0, "");
  });

  test("never splits a surrogate pair", () => {
    expectSplice("a" + grinning + "b", "a" + beaming + "b", 1, 1, beaming);
    expectSplice("x" + grinning, "x" + sameLow, 1, 0, sameLow);
    expectSplice(grinning, grinning + grinning, 2, 0, grinning);
  });

  test("random edits round-trip without splitting pairs", () => {
    const alphabet = ["a", "b", " ", "\n", "é", grinning, beaming, sameLow];
    let seed = 42;
    const random = (n: number) => {
      seed = (seed * 1103515245 + 12345) % 2147483648;
      return seed % n;
    };
    const randomText = () => Array.from({ length: random(12) }, () => alphabet[random(alphabet.length)]).join("");
    const splitsAPair = (s: string, index: number) =>
      index > 0 && index < s.length && s.charCodeAt(index - 1) >= 0xd800 && s.charCodeAt(index - 1) <= 0xdbff
      && s.charCodeAt(index) >= 0xdc00 && s.charCodeAt(index) <= 0xdfff;
    for (let i = 0; i < 20_000; i++) {
      const from = randomText();
      const to = randomText();
      const splice = computeSplice(from, to);
      expect(applySplice(splice, from)).toBe(to);
      expect(splitsAPair(from, splice.prefixLength)).toBe(false);
      expect(splitsAPair(from, from.length - splice.suffixLength)).toBe(false);
    }
  });
});

describe("applySplice", () => {
  test("refuses a splice that doesn't fit", () => {
    expect(() => applySplice({ prefixLength: 3, suffixLength: 3, middle: "" }, "short")).toThrow();
    expect(() => applySplice({ prefixLength: -1, suffixLength: 0, middle: "" }, "short")).toThrow();
  });

  test("a full splice replaces any base", () => {
    expect(applySplice(fullSplice("new text"), "anything at all")).toBe("new text");
    expect(applySplice(fullSplice("new text"), "")).toBe("new text");
  });
});
