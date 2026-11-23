import { defineLanguageFacet, ensureSyntaxTree, Language, languageDataProp, syntaxTree } from "@codemirror/language";
import { EditorState, Extension, Range } from "@codemirror/state";
import { Decoration, DecorationSet, EditorView, ViewPlugin, ViewUpdate } from "@codemirror/view";
import { SyntaxNodeRef } from "@lezer/common";
import { Autolink, parser, Strikethrough, TaskList } from "@lezer/markdown";

/**
 * Markdown, colored in the editor: the text stays as typed, markers included (muted, never
 * hidden: the caret would jump around them). A note's rendered view is another matter.
 * <p>
 * CommonMark with GFM's strikethrough, task lists and bare URLs, minus what plain-text notes
 * would trip on: a line underlined with "---" or "===" (a separator under a line would make it a
 * heading), a block indented by 4 spaces (indented lines would become code), and HTML. Tables
 * aren't parsed: they need the rendered view.
 */

const data = defineLanguageFacet();

export const markdownParser = parser.configure([
  Strikethrough,
  TaskList,
  Autolink,
  {
    remove: ["SetextHeading", "IndentedCode", "HTMLBlock", "HTMLTag"],
    props: [languageDataProp.add({ Document: data })],
  },
]);

export const markdownLanguage = new Language(data, markdownParser, [], "markdown");

/** A class for a line (from is the line's start), or for a stretch of text. */
export interface MarkdownClass {
  from: number;
  to: number;
  cls: string;
  line: boolean;
}

const MARKS: Record<string, string> = {
  Emphasis: "cm-md-em",
  StrongEmphasis: "cm-md-strong",
  Strikethrough: "cm-md-strike",
  InlineCode: "cm-md-code",
  ListMark: "cm-md-listmark",
  TaskMarker: "cm-md-taskmark",
  Link: "cm-md-link",
  Image: "cm-md-link",
  Autolink: "cm-md-url",
  HorizontalRule: "cm-md-mark",
  HeaderMark: "cm-md-mark",
  EmphasisMark: "cm-md-mark",
  StrikethroughMark: "cm-md-mark",
  QuoteMark: "cm-md-mark",
  CodeMark: "cm-md-mark",
  CodeInfo: "cm-md-mark",
  LinkMark: "cm-md-mark",
  LinkTitle: "cm-md-mark",
};

/** Every line of a block gets the block's class. */
const BLOCKS: Record<string, string> = {
  FencedCode: "cm-md-codeblock",
  Blockquote: "cm-md-quote",
};

/** The classes for the Markdown between {@code from} and {@code to}, as far as it's parsed yet. */
export function markdownClasses(state: EditorState, from: number, to: number): MarkdownClass[] {
  const classes: MarkdownClass[] = [];
  const line = (pos: number, cls: string) => classes.push({ from: state.doc.lineAt(pos).from, to: state.doc.lineAt(pos).from, cls, line: true });
  syntaxTree(state).iterate({
    from,
    to,
    enter: (node: SyntaxNodeRef) => {
      const name = node.name;
      const heading = /^ATXHeading(\d)$/.exec(name);
      if (heading) {
        line(node.from, "cm-md-h" + heading[1]);
      } else if (BLOCKS[name]) {
        // Only the lines in view: a block can be long.
        const first = state.doc.lineAt(Math.max(node.from, from)).number;
        const last = state.doc.lineAt(Math.min(node.to, to)).number;
        for (let n = first; n <= last; n++) line(state.doc.line(n).from, BLOCKS[name]);
      } else if (name === "URL") {
        // A link's (url) is muted, a bare one is the link.
        const parent = node.node.parent?.name;
        classes.push({ from: node.from, to: node.to, cls: parent === "Link" || parent === "Image" ? "cm-md-mark" : "cm-md-url", line: false });
      } else if (name === "Task") {
        const marker = node.node.getChild("TaskMarker");
        if (marker && /x/i.test(state.sliceDoc(marker.from, marker.to)) && marker.to < node.to) {
          classes.push({ from: marker.to, to: node.to, cls: "cm-md-done", line: false });
        }
      } else if (MARKS[name] && node.to > node.from) {
        classes.push({ from: node.from, to: node.to, cls: MARKS[name], line: false });
      }
    },
  });
  return classes;
}

const lineDecorations = new Map<string, Decoration>();
const markDecorations = new Map<string, Decoration>();

function decorations(view: EditorView): DecorationSet {
  const lines = new Map<number, Set<string>>();
  const ranges: Range<Decoration>[] = [];
  for (const { from, to } of view.visibleRanges) {
    for (const c of markdownClasses(view.state, from, to)) {
      if (c.line) {
        if (!lines.has(c.from)) lines.set(c.from, new Set());
        lines.get(c.from)!.add(c.cls);
      } else {
        let mark = markDecorations.get(c.cls);
        if (!mark) markDecorations.set(c.cls, (mark = Decoration.mark({ class: c.cls })));
        ranges.push(mark.range(c.from, c.to));
      }
    }
  }
  for (const [from, set] of lines) {
    const cls = [...set].sort().join(" ");
    let deco = lineDecorations.get(cls);
    if (!deco) lineDecorations.set(cls, (deco = Decoration.line({ class: cls })));
    ranges.push(deco.range(from));
  }
  return Decoration.set(ranges, true);
}

const plugin = ViewPlugin.fromClass(class {
  decorations: DecorationSet;

  constructor(view: EditorView) {
    this.decorations = decorations(view);
  }

  update(update: ViewUpdate) {
    // The tree changes on its own too: a long note is parsed in the background.
    if (update.docChanged || update.viewportChanged || syntaxTree(update.startState) !== syntaxTree(update.state)) {
      this.decorations = decorations(update.view);
    }
  }
}, { decorations: (value) => value.decorations });

const MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";

/** In the editor's shadow root, as its theme: the page's CSS variables apply there. */
const theme = EditorView.theme({
  ".cm-md-h1": { fontSize: "1.45em", fontWeight: "700" },
  ".cm-md-h2": { fontSize: "1.25em", fontWeight: "700" },
  ".cm-md-h3": { fontSize: "1.1em", fontWeight: "700" },
  ".cm-md-h4, .cm-md-h5, .cm-md-h6": { fontWeight: "700" },
  ".cm-md-mark": { color: "var(--text-muted)", fontWeight: "normal" },
  ".cm-md-em": { fontStyle: "italic" },
  ".cm-md-strong": { fontWeight: "700" },
  ".cm-md-strike": { textDecoration: "line-through" },
  ".cm-md-code": { fontFamily: MONO, fontSize: "0.92em", background: "var(--bg-pane)", borderRadius: "3px", padding: "1px 0" },
  ".cm-md-codeblock": { fontFamily: MONO, fontSize: "var(--editor-mono-font-size)", background: "var(--bg-pane)" },
  // A bar in the line's padding: the text doesn't move.
  ".cm-md-quote": {
    color: "var(--text-muted)",
    backgroundImage: "linear-gradient(var(--border), var(--border))",
    backgroundSize: "3px 100%",
    backgroundPosition: "12px 0",
    backgroundRepeat: "no-repeat",
  },
  ".cm-md-listmark, .cm-md-taskmark": { color: "var(--accent)" },
  ".cm-md-done": { color: "var(--text-muted)" },
  ".cm-md-link, .cm-md-url": { color: "var(--accent)" },
});

/** What a note with the Markdown option gets in its editor. */
export const markdownStyle: Extension = [markdownLanguage, plugin, theme];

/** For tests: the state parsed to the end. */
export function parsedState(doc: string): EditorState {
  const state = EditorState.create({ doc, extensions: markdownLanguage });
  ensureSyntaxTree(state, state.doc.length, 5000);
  return state;
}
