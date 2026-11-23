import { formatList, ListKind, taskShorthand } from "../../../ts/utils/ListFormat";

/** The text once the lines selected between "{" and "}" (or at "|") are made a list. */
function format(textWithSelection: string, kind: ListKind): string {
  let text = textWithSelection;
  let from: number;
  let to: number;
  if (text.includes("|")) {
    from = to = text.indexOf("|");
    text = text.replace("|", "");
  } else {
    from = text.indexOf("{");
    text = text.replace("{", "");
    to = text.indexOf("}");
    text = text.replace("}", "");
  }
  const edits = formatList(text, from, to, kind);
  for (const edit of [...edits].reverse()) text = text.slice(0, edit.from) + edit.insert + text.slice(edit.to);
  return text;
}

describe("ListFormat", () => {
  test("the caret's line becomes an item", () => {
    expect(format("one\ntw|o\nthree", "bullet")).toBe("one\n- two\nthree");
    expect(format("one\ntw|o", "task")).toBe("one\n- [ ] two");
    expect(format("tw|o", "number")).toBe("1. two");
  });

  test("an empty line gets a marker to type after", () => {
    expect(format("one\n|", "task")).toBe("one\n- [ ] ");
  });

  test("selected lines become items, skipping blank ones", () => {
    expect(format("{one\n\ntwo\nthr}ee\nfour", "bullet")).toBe("- one\n\n- two\n- three\nfour");
  });

  test("a selection ending at a line's start leaves that line", () => {
    expect(format("{one\ntwo\n}three", "bullet")).toBe("- one\n- two\nthree");
  });

  test("numbers count from 1 at each indent", () => {
    expect(format("{a\n  b\n  c\nd}", "number")).toBe("1. a\n  1. b\n  2. c\n2. d");
    expect(format("{5. a\n- b}", "number")).toBe("1. a\n2. b");
  });

  test("another kind of list is switched, keeping indents and checkboxes", () => {
    expect(format("{* a\n  + b}", "task")).toBe("- [ ] a\n  - [ ] b");
    expect(format("{- [x] a\n- b}", "task")).toBe("- [x] a\n- [ ] b");
    expect(format("{- [x] a\n- [ ] b}", "bullet")).toBe("- a\n- b");
    expect(format("{1. [x] a\n- b}", "task")).toBe("- [x] a\n- [ ] b");
  });

  test("lines all already of that kind become plain", () => {
    expect(format("{- a\n\n  * b}", "bullet")).toBe("a\n\n  b");
    expect(format("{3. a\n4) b}", "number")).toBe("a\nb");
    expect(format("- [x] |a", "task")).toBe("a");
  });

  test("nothing changes on lines already right", () => {
    expect(formatList("1. a\nb", 0, 6, "number")).toEqual([{ from: 5, to: 5, insert: "2. " }]);
  });

  test("the task shorthand is [] alone, indented or not", () => {
    expect(taskShorthand("[]")).toBe("");
    expect(taskShorthand("  []")).toBe("  ");
    expect(taskShorthand("a []")).toBeNull();
    expect(taskShorthand("[x]")).toBeNull();
  });
});
