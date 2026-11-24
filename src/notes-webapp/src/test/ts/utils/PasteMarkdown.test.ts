import { PasteNode, toMarkdown } from "../../../ts/utils/PasteMarkdown";

/** An element, as DOMParser would build it: just what the converter reads. */
function h(name: string, attrs: Record<string, string> = {}, ...children: (PasteNode | string)[]): PasteNode {
  const childNodes = children.map((c) => (typeof c === "string" ? t(c) : c));
  return {
    nodeType: 1,
    nodeName: name.toUpperCase(),
    childNodes,
    get textContent() {
      return childNodes.map((c) => c.textContent ?? "").join("");
    },
    getAttribute: (n: string) => (n in attrs ? attrs[n] : null),
  };
}

function t(text: string): PasteNode {
  return { nodeType: 3, nodeName: "#text", textContent: text, childNodes: [] };
}

const body = (...children: (PasteNode | string)[]) => h("body", {}, ...children);

describe("PasteMarkdown", () => {
  test("bold, italic, strikethrough, links: kept; spaces outside the markers", () => {
    expect(toMarkdown(body(h("p", {}, "A ", h("b", {}, "bold "), "and ", h("em", {}, "italic"), ", ", h("del", {}, "gone"), ".")))).toBe("A **bold** and *italic*, ~~gone~~.");
    expect(toMarkdown(body(h("p", {}, "See ", h("a", { href: "https://example.com/a(b)" }, "this page"), ".")))).toBe("See [this page](https://example.com/a%28b%29).");
    expect(toMarkdown(body(h("p", {}, h("a", { href: "https://example.com" }, "https://example.com"))))).toBe("https://example.com");
  });

  test("links only to the web or mail: others are their text", () => {
    expect(toMarkdown(body(h("p", {}, h("b", {}, "x"), " ", h("a", { href: "javascript:alert(1)" }, "click"))))).toBe("**x** click");
  });

  test("Google Docs: bold and italic from styles; its wrapping <b> isn't bold", () => {
    const docs = body(h("b", { style: "font-weight:normal;", id: "docs-internal-guid-1" },
      h("p", {}, h("span", { style: "font-weight:700" }, "Heavy"), h("span", {}, " then "), h("span", { style: "font-style:italic" }, "slanted"))));
    expect(toMarkdown(docs)).toBe("**Heavy** then *slanted*");
    // Around paragraphs and lists, as Docs writes several of them.
    expect(toMarkdown(body(h("b", { style: "font-weight:normal" }, h("p", {}, h("span", { style: "font-weight:700" }, "A")),
      h("ul", {}, h("li", {}, h("p", {}, "x")), h("li", {}, h("p", {}, "y"))))))).toBe("**A**\n\n- x\n- y");
  });

  test("lists: bullets, numbers from their start, nested, tasks", () => {
    const html = body(
      h("ul", {}, h("li", {}, "One"), h("li", {}, "Two", h("ul", {}, h("li", {}, "Two and a half")))),
      h("ol", { start: "3" }, h("li", {}, "Third"), h("li", {}, "Fourth")),
      h("ul", {}, h("li", {}, h("input", { type: "checkbox", checked: "" }), " Done"), h("li", {}, h("input", { type: "checkbox" }), " To do")),
    );
    expect(toMarkdown(html)).toBe("- One\n- Two\n  - Two and a half\n\n3. Third\n4. Fourth\n\n- [x] Done\n- [ ] To do");
  });

  test("headings, quotes, code, rules, paragraphs and line breaks", () => {
    const html = body(
      h("h2", {}, "Title"),
      h("p", {}, "First line", h("br"), "second line"),
      h("blockquote", {}, h("p", {}, "Quoted")),
      h("p", {}, "Run ", h("code", {}, "npm test"), " now"),
      h("pre", {}, h("code", { class: "language-js" }, "const a = 1;\nconst b = 2;\n")),
      h("hr"),
    );
    expect(toMarkdown(html)).toBe("## Title\n\nFirst line\nsecond line\n\n> Quoted\n\nRun `npm test` now\n\n```js\nconst a = 1;\nconst b = 2;\n```\n\n---");
  });

  test("a table of data: a Markdown table; one laying a page out: its content", () => {
    const data = body(h("table", {}, h("thead", {}, h("tr", {}, h("th", {}, "Name"), h("th", {}, "Qty"))),
      h("tbody", {}, h("tr", {}, h("td", {}, "Flour"), h("td", {}, "200 g")), h("tr", {}, h("td", {}, "a|b"), h("td", {}, h("b", {}, "1"))))));
    expect(toMarkdown(data)).toBe("| Name | Qty |\n| --- | --- |\n| Flour | 200 g |\n| a\\|b | **1** |");
    const layout = body(h("table", {}, h("tr", {}, h("td", {}, h("h1", {}, "News")), h("td", {}, h("p", {}, "Side")))));
    expect(toMarkdown(layout)).toBe("# News\n\nSide");
  });

  test("text that would make Markdown is escaped", () => {
    expect(toMarkdown(body(h("p", {}, h("b", {}, "Note"), ": 2*3 = 6, see [1], snake_case, _under_")))).toBe("**Note**: 2\\*3 = 6, see \\[1\\], snake_case, \\_under\\_");
    expect(toMarkdown(body(h("p", {}, "# not a heading"), h("p", {}, h("i", {}, "x"))))).toBe("\\# not a heading\n\n*x*");
  });

  test("nothing kept, or code from an editor: none, the plain text is pasted", () => {
    expect(toMarkdown(body(h("p", {}, "Just text")))).toBeUndefined();
    expect(toMarkdown(body(h("div", { style: "font-family: 'Droid Sans Mono', monospace; white-space: pre;" },
      h("div", {}, h("span", { style: "font-weight: bold" }, "const"), " a"))))).toBeUndefined();
    expect(toMarkdown(body(h("script", {}, "alert(1)"), h("p", {}, "x")))).toBeUndefined();
  });
});
