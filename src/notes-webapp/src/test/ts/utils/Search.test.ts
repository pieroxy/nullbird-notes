import { cutAtMatches, findMatches, fold, matches, MAX_MATCHES, parseQuery, SearchIndex, snippet } from "../../../ts/utils/Search";

describe("Search", () => {
  test("folding ignores case and accents, and keeps every position", () => {
    expect(fold("Les Misérables, ÉTÉ, Noël, Œuvre")).toBe("les miserables, ete, noel, œuvre");
    for (const text of ["Ça déçoit", "é", "İstanbul", "😀 ß ﬁ"]) expect(fold(text)).toHaveLength(text.length);
  });

  test("a query is words and quoted phrases, folded, without repeats", () => {
    expect(parseQuery("  Hugo  misérables hugo ")).toEqual(["hugo", "miserables"]);
    expect(parseQuery('"Les Misérables" Hugo')).toEqual(["les miserables", "hugo"]);
    expect(parseQuery('"unclosed phrase')).toEqual(["unclosed phrase"]);
    expect(parseQuery('"" ')).toEqual([]);
  });

  test("a note matches when every term is in its text or a tag", () => {
    const text = fold("Les Misérables\nVictor Hugo");
    expect(matches(parseQuery("mis hugo"), text, [])).toBe(true);
    expect(matches(parseQuery("mis zola"), text, [])).toBe(false);
    expect(matches(parseQuery("TOTO hugo"), text, [fold("toto")])).toBe(true);
    expect(matches(parseQuery('"hugo victor"'), text, [])).toBe(false);
  });

  test("matches come in text order, without overlaps", () => {
    expect(findMatches("abc abcd bc", ["bc", "abcd"])).toEqual([
      { start: 1, end: 3 }, { start: 4, end: 8 }, { start: 9, end: 11 },
    ]);
  });

  test("past the limit, matches stop being counted", () => {
    expect(findMatches("e".repeat(5000), ["e"])).toHaveLength(MAX_MATCHES + 1);
  });

  test("the snippet starts near the first match after the title, matches marked", () => {
    const text = "Hugo\nIntro line.\n" + "word ".repeat(20) + "Victor Hugo wrote it, hugo said.";
    const pieces = snippet(text, fold(text), ["hugo"])!;
    expect(pieces[0]).toEqual({ text: "…", match: false });
    expect(pieces.filter((p) => p.match).map((p) => p.text)).toEqual(["Hugo", "hugo"]);
    expect(pieces.map((p) => p.text).join("")).toBe("…word word word word Victor Hugo wrote it, hugo said.");
    expect(snippet("Hugo\nnothing here", fold("Hugo\nnothing here"), ["hugo"])).toBeUndefined();
  });

  test("the index folds a note again only when its text changed", () => {
    const index = new SearchIndex();
    expect(index.get("a", "Été")).toBe("ete");
    expect(index.get("a", "Hiver")).toBe("hiver");
  });

  test("cutAtMatches: the pieces of a text at an offset, matches overlapping its ends cut", () => {
    const matches = [{ start: 0, end: 2 }, { start: 4, end: 7 }, { start: 9, end: 10 }, { start: 20, end: 22 }];
    // "cdefgh" is at offset 2: the second match is "efg", the third is past it.
    expect(cutAtMatches("cdefgh", 2, matches)).toEqual([
      { text: "cd", pos: 2, match: -1 }, { text: "efg", pos: 4, match: 1 }, { text: "h", pos: 7, match: -1 },
    ]);
    expect(cutAtMatches("abc", 1, matches)).toEqual([{ text: "a", pos: 1, match: 0 }, { text: "bc", pos: 2, match: -1 }]);
    expect(cutAtMatches("xyz", 11, matches)).toEqual([{ text: "xyz", pos: 11, match: -1 }]);
    expect(cutAtMatches("xyz", 5, [])).toEqual([{ text: "xyz", pos: 5, match: -1 }]);
  });
});
