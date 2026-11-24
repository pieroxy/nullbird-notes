import { MERGE_TAG } from "../../../ts/auto/Constants";
import { hasMergeMark, isMergeMark, mergeMarkText, noteHasMergeMark, removedMergeMark } from "../../../ts/utils/MergeMarks";
import { titleAndExcerpt } from "../../../ts/utils/format";

const MARK = MERGE_TAG + " Auto-merged below: watch out";

describe("MergeMarks", () => {
  test("by the tag at the start of a line, whatever follows", () => {
    expect(isMergeMark(MARK)).toBe(true);
    expect(isMergeMark(MERGE_TAG + " Fusion automatique")).toBe(true);
    expect(isMergeMark(" " + MARK)).toBe(false);
    expect(isMergeMark("See " + MERGE_TAG)).toBe(false);
    expect(mergeMarkText(MARK)).toBe("Auto-merged below: watch out");
  });

  test("in a note: on any line, not inside one", () => {
    expect(hasMergeMark(MARK + "\ntext")).toBe(true);
    expect(hasMergeMark("Title\n" + MARK + "\ntext")).toBe(true);
    expect(hasMergeMark("Title " + MARK)).toBe(false);
    expect(noteHasMergeMark("a", "x\n" + MARK)).toBe(true);
    expect(noteHasMergeMark("a", "x")).toBe(false);
  });

  test("removed with its line break; the last line, with the one before", () => {
    const text = "Title\n" + MARK + "\nI like dogs birds.";
    expect(removedMergeMark(text, 6)).toEqual({ from: 6, to: 6 + MARK.length + 1 });
    const last = "Title\n" + MARK;
    expect(removedMergeMark(last, 6)).toEqual({ from: 5, to: last.length });
    expect(removedMergeMark(text, 0)).toBeUndefined();
    expect(removedMergeMark(text, 7)).toBeUndefined();
  });

  test("not a note's title nor its excerpt", () => {
    expect(titleAndExcerpt(MARK + "\nTitle\n" + MARK + "\nBody")).toEqual({ title: "Title", excerpt: "Body" });
  });
});
