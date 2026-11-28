import { InlineStyle, toggleInline } from "../../../ts/utils/InlineFormat";

/** The text after, the selection as [ and ] (| for a caret); the input marked the same way. */
function toggle(marked: string, style: InlineStyle): string {
  const caret = marked.indexOf("|");
  const from = caret >= 0 ? caret : marked.indexOf("[");
  const to = caret >= 0 ? caret : marked.indexOf("]") - 1;
  const text = marked.replace(/[[\]|]/g, "");
  const edit = toggleInline(text, from, to, style);
  let out = text;
  for (const change of [...edit.changes].reverse()) out = out.slice(0, change.from) + change.insert + out.slice(change.to);
  return edit.anchor === edit.head
    ? out.slice(0, edit.anchor) + "|" + out.slice(edit.anchor)
    : out.slice(0, edit.anchor) + "[" + out.slice(edit.anchor, edit.head) + "]" + out.slice(edit.head);
}

describe("InlineFormat", () => {
  test("wraps the selection, which stays on the same text", () => {
    expect(toggle("buy [milk] now", "bold")).toBe("buy **[milk]** now");
    expect(toggle("buy [milk] now", "italic")).toBe("buy *[milk]* now");
  });

  test("spaces at the selection's ends stay out", () => {
    expect(toggle("buy[ milk ]now", "bold")).toBe("buy **[milk]** now");
  });

  test("nothing selected: a pair, the caret between; again, gone", () => {
    expect(toggle("buy |", "bold")).toBe("buy **|**");
    expect(toggle("buy **|**", "bold")).toBe("buy |");
    expect(toggle("buy *|*", "italic")).toBe("buy |");
  });

  test("unwraps with the markers selected or right outside", () => {
    expect(toggle("buy [**milk**] now", "bold")).toBe("buy [milk] now");
    expect(toggle("buy **[milk]** now", "bold")).toBe("buy [milk] now");
    expect(toggle("buy *[milk]* now", "italic")).toBe("buy [milk] now");
  });

  test("bold and italic apart, though both are asterisks", () => {
    expect(toggle("**[milk]**", "italic")).toBe("***[milk]***");
    expect(toggle("***[milk]***", "italic")).toBe("**[milk]**");
    expect(toggle("***[milk]***", "bold")).toBe("*[milk]*");
    expect(toggle("*[milk]*", "bold")).toBe("***[milk]***");
  });

  test("only spaces selected: a pair where it started", () => {
    expect(toggle("a[  ]b", "italic")).toBe("a*|*  b");
  });
});
