import { markdownClasses, parsedState } from "../../../ts/components/MarkdownStyle";

/** "text: class" for each stretch, "line N: class" for each line. */
function styled(doc: string): string[] {
  const state = parsedState(doc);
  return markdownClasses(state, 0, state.doc.length).map((c) => c.line
    ? "line " + state.doc.lineAt(c.from).number + ": " + c.cls
    : JSON.stringify(state.sliceDoc(c.from, c.to)) + ": " + c.cls);
}

describe("MarkdownStyle", () => {
  test("headings: the line, and its # muted", () => {
    expect(styled("# Title\n### Sub")).toEqual([
      "line 1: cm-md-h1", "\"#\": cm-md-mark", "line 2: cm-md-h3", "\"###\": cm-md-mark",
    ]);
  });

  test("emphasis, strong, strikethrough, inline code, markers muted", () => {
    expect(styled("a *b* **c** ~~d~~ `e`")).toEqual([
      "\"*b*\": cm-md-em", "\"*\": cm-md-mark", "\"*\": cm-md-mark",
      "\"**c**\": cm-md-strong", "\"**\": cm-md-mark", "\"**\": cm-md-mark",
      "\"~~d~~\": cm-md-strike", "\"~~\": cm-md-mark", "\"~~\": cm-md-mark",
      "\"`e`\": cm-md-code", "\"`\": cm-md-mark", "\"`\": cm-md-mark",
    ]);
  });

  test("lists and tasks: the marker, and a done task's text muted", () => {
    expect(styled("- one\n1. two\n- [x] done\n- [ ] todo")).toEqual([
      "\"-\": cm-md-listmark", "\"1.\": cm-md-listmark",
      "\"-\": cm-md-listmark", "\" done\": cm-md-done", "\"[x]\": cm-md-taskmark",
      "\"-\": cm-md-listmark", "\"[ ]\": cm-md-taskmark",
    ]);
  });

  test("a fenced code block: each line, and nothing inside it is Markdown", () => {
    expect(styled("```js\n# not a title *nor this*\n```")).toEqual([
      "line 1: cm-md-codeblock", "line 2: cm-md-codeblock", "line 3: cm-md-codeblock",
      "\"```\": cm-md-mark", "\"js\": cm-md-mark", "\"```\": cm-md-mark",
    ]);
  });

  test("a quote: each line, its > muted", () => {
    expect(styled("> one\n> two")).toEqual([
      "line 1: cm-md-quote", "line 2: cm-md-quote", "\">\": cm-md-mark", "\">\": cm-md-mark",
    ]);
  });

  test("links: the link, its url muted; a bare URL is a link", () => {
    expect(styled("[site](https://example.com) and https://example.org")).toEqual([
      "\"[site](https://example.com)\": cm-md-link", "\"[\": cm-md-mark", "\"]\": cm-md-mark", "\"(\": cm-md-mark",
      "\"https://example.com\": cm-md-mark", "\")\": cm-md-mark", "\"https://example.org\": cm-md-url",
    ]);
  });

  test("a rule is muted", () => {
    expect(styled("a\n\n---\n\nb")).toEqual(["\"---\": cm-md-mark"]);
  });

  test("what plain-text notes would trip on stays plain", () => {
    // A separator under a line, an indented line, HTML.
    expect(styled("Shopping\n---\n\n    indented, not code\n\n<b>not html</b>")).toEqual(["\"---\": cm-md-mark"]);
  });

  test("a table stays text", () => {
    expect(styled("| a | b |\n|---|---|\n| 1 | 2 |")).toEqual([]);
  });
});
