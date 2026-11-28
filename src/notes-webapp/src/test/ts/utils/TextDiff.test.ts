import { DiffLine, diffLines, Fold, folded, hasChanges, tokens, wordDiff } from "../../../ts/utils/TextDiff";

/** Each line as "  text", "- text" or "+ text", changed words in [brackets]. */
function show(lines: DiffLine[]): string[] {
  return lines.map((line) => (line.kind === "same" ? "  " : line.kind === "removed" ? "- " : "+ ")
    + line.pieces.map((p) => (p.changed && line.pieces.length > 1 ? "[" + p.text + "]" : p.text)).join(""));
}

describe("TextDiff", () => {
  test("a line added, one removed, the rest the same", () => {
    expect(show(diffLines("Groceries\nmilk\nbread", "Groceries\nbread\neggs"))).toEqual([
      "  Groceries", "- milk", "  bread", "+ eggs",
    ]);
  });

  test("a changed line: the words that changed marked on both sides", () => {
    expect(show(diffLines("Groceries\nbuy some milk today", "Groceries\nbuy oat milk today"))).toEqual([
      "  Groceries", "- buy [some] milk today", "+ buy [oat] milk today",
    ]);
  });

  test("lines too unlike: whole lines, no words marked", () => {
    expect(show(diffLines("one\ncompletely different", "one\nnothing alike here"))).toEqual([
      "  one", "- completely different", "+ nothing alike here",
    ]);
  });

  test("from nothing, to nothing, no change", () => {
    expect(show(diffLines("", "a\nb"))).toEqual(["+ a", "+ b"]);
    expect(show(diffLines("a", ""))).toEqual(["- a"]);
    expect(hasChanges(diffLines("same\ntext", "same\ntext"))).toBe(false);
    expect(diffLines("", "")).toEqual([]);
  });

  test("the edits are minimal, wherever they are", () => {
    const before = ["a", "b", "c", "a", "b", "b", "a"].join("\n");
    const after = ["c", "b", "a", "b", "a", "c"].join("\n");
    const lines = diffLines(before, after);
    expect(lines.filter((l) => l.kind !== "same")).toHaveLength(5);
    // Read back, each side is whole.
    expect(lines.filter((l) => l.kind !== "added").map((l) => l.pieces.map((p) => p.text).join("")).join("\n")).toBe(before);
    expect(lines.filter((l) => l.kind !== "removed").map((l) => l.pieces.map((p) => p.text).join("")).join("\n")).toBe(after);
  });

  test("a huge rewrite doesn't take forever: all removed, all added", () => {
    const before = Array.from({ length: 3000 }, (_, i) => "old " + i).join("\n");
    const after = Array.from({ length: 3000 }, (_, i) => "new " + i).join("\n");
    const started = Date.now();
    const lines = diffLines(before, after);
    expect(Date.now() - started).toBeLessThan(3000);
    expect(lines.filter((l) => l.kind === "removed")).toHaveLength(3000);
    expect(lines.filter((l) => l.kind === "added")).toHaveLength(3000);
  });

  test("tokens: words, spaces, each other character", () => {
    expect(tokens("Hé, l'été  2026!")).toEqual(["Hé", ",", " ", "l", "'", "été", "  ", "2026", "!"]);
    expect(wordDiff("abc", "abd")).toBeUndefined();
  });

  test("unchanged stretches fold, but for 3 lines around each change", () => {
    const before = Array.from({ length: 20 }, (_, i) => "line " + i).join("\n");
    const after = before.replace("line 10", "line ten");
    const out = folded(diffLines(before, after));
    const shape = out.map((item) => (item.kind === "fold" ? "fold " + (item as Fold).lines.length : item.kind));
    expect(shape).toEqual(["fold 7", "same", "same", "same", "removed", "added", "same", "same", "same", "fold 6"]);
  });

  test("a single unchanged line isn't folded", () => {
    const before = ["a", "b", "c", "d", "e", "f", "g", "h", "i"].join("\n");
    const after = before.replace("a", "A").replace("i", "I");
    expect(folded(diffLines(before, after)).some((item) => item.kind === "fold")).toBe(false);
  });
});
