import { Block, Inline, parseMarkdown, toggledTask } from "../../../ts/components/MarkdownTree";

/** Inline content as a compact string: *em*, **strong**, ~~strike~~, `code`, [text](href), / for a break. */
function inline(nodes: Inline[]): string {
  return nodes.map((node) => {
    switch (node.t) {
      case "text": return node.text;
      case "br": return "/";
      case "em": return "*" + inline(node.children) + "*";
      case "strong": return "**" + inline(node.children) + "**";
      case "strike": return "~~" + inline(node.children) + "~~";
      case "code": return "`" + node.text + "`";
      case "link": return "[" + inline(node.children) + "](" + (node.href ?? "-") + ")";
    }
  }).join("");
}

function only(text: string): Block {
  const blocks = parseMarkdown(text);
  expect(blocks).toHaveLength(1);
  return blocks[0];
}

function paragraph(text: string): string {
  const block = only(text);
  if (block.t !== "p") throw new Error("not a paragraph: " + block.t);
  return inline(block.children);
}

describe("MarkdownTree", () => {
  test("headings, paragraphs, rules", () => {
    const blocks = parseMarkdown("# Title ##\n\nSome text\n\n---\n### Sub");
    expect(blocks.map((b) => b.t)).toEqual(["h", "p", "hr", "h"]);
    expect(blocks[0]).toMatchObject({ level: 1 });
    expect(inline((blocks[0] as { children: Inline[] }).children)).toBe("Title");
    expect(blocks[3]).toMatchObject({ level: 3, pos: 27 });
  });

  test("a line break in a paragraph stays one, indentation and trailing spaces go", () => {
    expect(paragraph("milk  \n  eggs\nbread ")).toBe("milk/eggs/bread");
  });

  test("emphasis, strong, strikethrough, code, nested", () => {
    expect(paragraph("a *b* **c *d*** ~~e~~ ` f `")).toBe("a *b* **c *d*** ~~e~~ `f`");
  });

  test("escapes and entities", () => {
    expect(paragraph("\\*not em\\* &amp; &#65; &#x42; &bogus;")).toBe("*not em* & A B &bogus;");
  });

  test("links: inline, by reference, bare; only http, https and mailto are followed", () => {
    expect(paragraph("[a](https://x.org) [b][r] [r] <x> www.y.com [c](javascript:alert(1)) [d](mailto:m@n.o) [nope]\n\n[R]: http://r.org"))
      .toBe("[a](https://x.org) [b](http://r.org) [r](http://r.org) <x> [www.y.com](http://www.y.com) [c](-) [d](mailto:m@n.o) [nope]");
  });

  test("an image is a link to it, its alt text the text", () => {
    expect(paragraph("![a cat](https://x.org/cat.png)")).toBe("[a cat](https://x.org/cat.png)");
  });

  test("lists: bullets, numbered from their first number, nested", () => {
    const blocks = parseMarkdown("- a\n  - b\n- c\n\n3. x\n4. y");
    expect(blocks).toHaveLength(2);
    const [bullets, numbered] = blocks;
    if (bullets.t !== "list" || numbered.t !== "list") throw new Error("not lists");
    expect(bullets.ordered).toBe(false);
    expect(bullets.items.map((item) => item.children.map((b) => b.t))).toEqual([["p", "list"], ["p"]]);
    expect(numbered).toMatchObject({ ordered: true, start: 3 });
  });

  test("tasks: done or not, the marker's offset, continuation lines", () => {
    const text = "- [ ] todo\n- [x] done\n  more";
    const list = only(text);
    if (list.t !== "list") throw new Error("not a list");
    expect(list.items.map((item) => item.task)).toEqual([{ done: false, marker: 2 }, { done: true, marker: 13 }]);
    const body = list.items[1].children[0];
    expect(body.t === "p" && inline(body.children)).toBe("done/more");
  });

  test("toggling a task's marker, and not what isn't one", () => {
    expect(toggledTask("- [ ] a", 2)).toEqual({ from: 3, to: 4, insert: "x" });
    expect(toggledTask("- [X] a", 2)).toEqual({ from: 3, to: 4, insert: " " });
    expect(toggledTask("- [ ] a", 1)).toBeUndefined();
  });

  test("code blocks: between the fences, without the quote marks or the item's indentation", () => {
    const [quote, list] = parseMarkdown("> ```\n> a\n>   b\n> ```\n\n- item\n  ```\n  c\n    d\n  ```");
    expect(quote.t === "quote" && quote.children[0]).toEqual({ t: "code", pos: 2, text: "a\n  b" });
    const item = list.t === "list" ? list.items[0] : undefined;
    expect(item?.children[1]).toMatchObject({ t: "code", text: "c\n  d" });
  });

  test("an unclosed code block runs to the end", () => {
    expect(only("```\na\nb")).toMatchObject({ t: "code", text: "a\nb" });
  });

  test("quotes hold blocks; a paragraph's lines lose their quote marks", () => {
    const quote = only("> a\n> b\n>\n> - c");
    if (quote.t !== "quote") throw new Error("not a quote");
    expect(quote.children.map((b) => b.t)).toEqual(["p", "list"]);
    expect(quote.children[0].t === "p" && inline(quote.children[0].children)).toBe("a/b");
  });

  test("tables: header, alignment, empty and missing cells, extra ones dropped", () => {
    const table = only("| a | b | c |\n|:--|:-:|--:|\n| 1 | | *3* |\n| 4 |\n| 5 | 6 | 7 | 8 |");
    if (table.t !== "table") throw new Error("not a table");
    expect(table.align).toEqual(["left", "center", "right"]);
    expect(table.head.map(inline)).toEqual(["a", "b", "c"]);
    expect(table.rows.map((row) => row.map(inline))).toEqual([["1", "", "*3*"], ["4", "", ""], ["5", "6", "7"]]);
  });

  test("texts as typed keep their source offset, for a double-click to map back", () => {
    const text = "# Hi\n\nab *cd*\nef";
    const p = parseMarkdown(text)[1];
    if (p.t !== "p") throw new Error("not a paragraph");
    const texts: { text: string; pos?: number }[] = [];
    const collect = (nodes: Inline[]) => nodes.forEach((n) => {
      if (n.t === "text") texts.push({ text: n.text, pos: n.pos });
      else if ("children" in n) collect(n.children);
    });
    collect(p.children);
    for (const t of texts) expect(text.slice(t.pos!, t.pos! + t.text.length)).toBe(t.text);
    expect(texts.map((t) => t.text)).toEqual(["ab ", "cd", "ef"]);
  });

  test("no HTML: tags are text", () => {
    expect(paragraph("<b>x</b> <script>y</script>")).toBe("<b>x</b> <script>y</script>");
  });

  test("an empty note has no blocks", () => {
    expect(parseMarkdown("")).toEqual([]);
    expect(parseMarkdown("\n\n")).toEqual([]);
  });
});
