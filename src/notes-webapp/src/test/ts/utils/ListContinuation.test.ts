import { continueList } from "../../../ts/utils/ListContinuation";

/** The text after Enter where "|" is, or null for a plain line break. */
function enter(textWithCaret: string): string | null {
  const cursor = textWithCaret.indexOf("|");
  const text = textWithCaret.replace("|", "");
  const edit = continueList(text, cursor);
  if (!edit) return null;
  const result = text.slice(0, edit.from) + edit.insert;
  return result + "|" + text.slice(edit.to);
}

describe("ListContinuation", () => {
  test("bullets continue with the same marker", () => {
    expect(enter("* one|")).toBe("* one\n* |");
    expect(enter("- one|")).toBe("- one\n- |");
    expect(enter("+ one|")).toBe("+ one\n+ |");
  });

  test("numbers go up, keeping their delimiter and width", () => {
    expect(enter("1. one|")).toBe("1. one\n2. |");
    expect(enter("9) nine|")).toBe("9) nine\n10) |");
    expect(enter("09. nine|")).toBe("09. nine\n10. |");
  });

  test("the indent and the spaces after the marker are kept", () => {
    expect(enter("text\n  * nested|\nmore")).toBe("text\n  * nested\n  * |\nmore");
    expect(enter("\t-   wide|")).toBe("\t-   wide\n\t-   |");
  });

  test("a checkbox continues unchecked", () => {
    expect(enter("- [x] done|")).toBe("- [x] done\n- [ ] |");
    expect(enter("1. [ ] todo|")).toBe("1. [ ] todo\n2. [ ] |");
  });

  test("in the middle of an item, the rest goes to the new one", () => {
    expect(enter("* foo|bar")).toBe("* foo\n* |bar");
  });

  test("an item with nothing after its marker ends the list", () => {
    expect(enter("* one\n* |")).toBe("* one\n|");
    expect(enter("* one\n  1. |  \nnext")).toBe("* one\n|\nnext");
    expect(enter("- [ ] |")).toBe("|");
  });

  test("anything else is a plain line break", () => {
    expect(enter("plain|")).toBeNull();
    expect(enter("*bold*|")).toBeNull();
    expect(enter("-1|")).toBeNull();
    expect(enter("1.5 kg|")).toBeNull();
    expect(enter("|* one")).toBeNull();
    expect(enter("* | one")).toBeNull();
    expect(enter("|")).toBeNull();
  });
});
