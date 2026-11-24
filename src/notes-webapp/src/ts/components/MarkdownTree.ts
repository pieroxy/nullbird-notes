import { SyntaxNode } from "@lezer/common";
import { MarkdownConfig, Table } from "@lezer/markdown";
import { isMergeMark, mergeMarkText } from "../utils/MergeMarks";
import { markdownGrammar } from "./MarkdownGrammar";

/**
 * A note's Markdown as blocks and inline content, for its rendered view: plain data, which the view
 * turns into elements. {@code pos} is the source offset an element starts at; a text's is set only
 * when it's the source verbatim, so that an offset in it maps back to the source.
 */
export type Inline =
  | { t: "text"; text: string; pos?: number }
  | { t: "br" }
  | { t: "em" | "strong" | "strike"; children: Inline[] }
  | { t: "code"; text: string }
  /** No href: its URL isn't one to follow (not http, https or mailto, or a missing reference). */
  | { t: "link"; href?: string; children: Inline[] };

export type Align = "left" | "center" | "right" | undefined;

export type Block =
  | { t: "p"; pos: number; children: Inline[] }
  | { t: "h"; pos: number; level: number; children: Inline[] }
  | { t: "quote"; pos: number; children: Block[] }
  | { t: "list"; pos: number; ordered: boolean; start: number; items: ListItem[] }
  | { t: "code"; pos: number; text: string }
  | { t: "hr"; pos: number }
  | { t: "table"; pos: number; align: Align[]; head: Inline[][]; rows: Inline[][][] }
  /** A merge mark's line (see MergeMarks): {@code text}, the sentence after its tag. */
  | { t: "merge"; pos: number; text: string };

export interface ListItem {
  pos: number;
  /** {@code marker}: the offset of its "[ ]" or "[x]". */
  task?: { done: boolean; marker: number };
  children: Block[];
}

/**
 * A merge mark's line, a block of its own: it ends the paragraph above it, which the line below it
 * would otherwise continue (Merge3 puts it right above the line where the clash starts).
 */
const MergeMark: MarkdownConfig = {
  defineNodes: [{ name: "MergeMark", block: true }],
  parseBlock: [{
    name: "MergeMark",
    parse(cx, line) {
      if (!isMergeMark(line.text)) return false;
      cx.addElement(cx.elt("MergeMark", cx.lineStart, cx.lineStart + line.text.length));
      cx.nextLine();
      return true;
    },
    endLeaf: (_cx, line) => isMergeMark(line.text),
  }],
};

/** The coloring's grammar, plus tables and merge marks. */
const viewParser = markdownGrammar.configure([Table, MergeMark]);

/** Children that only delimit their parent: nothing of theirs shows. */
const MARKS = new Set(["EmphasisMark", "StrikethroughMark", "CodeMark", "LinkMark", "HeaderMark", "QuoteMark", "ListMark", "TaskMarker", "LinkTitle", "LinkLabel"]);

const ENTITIES: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: "\"", apos: "'", nbsp: " " };

/**
 * The note's title, its first non-blank line, is a heading: as if "# " came first, unless it's a
 * heading already. A list item's, a quote's or a task's marker in front of it goes, as in the note
 * list. A merge mark above it stays above it, the title still the line after.
 */
export function parseMarkdown(text: string): Block[] {
  const title = /[^\s]/.exec(text);
  if (!title) return [];
  const lineStart = text.lastIndexOf("\n", title.index) + 1;
  const nl = text.indexOf("\n", title.index);
  const lineEnd = nl < 0 ? text.length : nl;
  const line = text.slice(lineStart, lineEnd);
  if (isMergeMark(line)) {
    const mark: Block = { t: "merge", pos: lineStart, text: mergeMarkText(line) };
    return [mark, ...parseMarkdown(text.slice(0, lineStart) + blank(line) + text.slice(lineEnd))];
  }
  if (/^ {0,3}#{1,6}(\s|$)/.test(line)) return parse(text);
  const contentStart = lineStart + /^\s*(?:>\s?|[-*+]\s+(?:\[[ xX]\]\s+)?|\d{1,9}[.)]\s+)*/.exec(line)![0].length;
  // Blanked out as spaces, what isn't parsed keeps every offset where it was.
  const body = blank(text.slice(0, lineEnd)) + text.slice(lineEnd);
  const bodyTop = viewParser.parse(body).topNode;
  const refs = references(body, bodyTop);
  const titleText = blank(text.slice(0, contentStart)) + text.slice(contentStart, lineEnd);
  const titleTop = viewParser.parse(titleText).topNode;
  const parsed = titleTop.firstChild?.name === "Paragraph" ? new Builder(titleText, refs).block(titleTop.firstChild) : undefined;
  const children: Inline[] = parsed?.t === "p" ? parsed.children : [{ t: "text", text: text.slice(contentStart, lineEnd).trim(), pos: undefined }];
  return [{ t: "h", pos: lineStart, level: 1, children }, ...new Builder(body, refs).blocks(bodyTop)];
}

function parse(text: string): Block[] {
  const top = viewParser.parse(text).topNode;
  return new Builder(text, references(text, top)).blocks(top);
}

function blank(text: string): string {
  return text.replace(/[^\n]/g, " ");
}

/** A task's marker toggled, or undefined if {@code marker} isn't one (the text changed since). */
export function toggledTask(text: string, marker: number): { from: number; to: number; insert: string } | undefined {
  const box = text.slice(marker, marker + 3);
  if (box === "[ ]") return { from: marker + 1, to: marker + 2, insert: "x" };
  if (box === "[x]" || box === "[X]") return { from: marker + 1, to: marker + 2, insert: " " };
  return undefined;
}

/** "[label]: url" definitions, by normalized label. */
function references(text: string, top: SyntaxNode): Map<string, string> {
  const refs = new Map<string, string>();
  for (const node of top.getChildren("LinkReference")) {
    const label = node.getChild("LinkLabel");
    const url = node.getChild("URL");
    if (!label || !url) continue;
    const key = normalizeLabel(text.slice(label.from + 1, label.to - 1));
    if (!refs.has(key)) refs.set(key, destination(text.slice(url.from, url.to)));
  }
  return refs;
}

function normalizeLabel(label: string): string {
  return label.trim().replace(/\s+/g, " ").toLowerCase();
}

function destination(url: string): string {
  return url.startsWith("<") && url.endsWith(">") ? url.slice(1, -1) : url;
}

/** Only links that leave the page to somewhere safe; "www." ones as http. */
function safeHref(url: string): string | undefined {
  if (/^(https?:|mailto:)/i.test(url)) return url;
  if (/^www\./i.test(url)) return "http://" + url;
  return undefined;
}

function decodeEntity(entity: string): string {
  const body = entity.slice(1, -1);
  const numeric = /^#(x[0-9a-f]+|\d+)$/i.exec(body);
  if (numeric) {
    const code = numeric[1][0].toLowerCase() === "x" ? parseInt(numeric[1].slice(1), 16) : parseInt(numeric[1], 10);
    return code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : "�";
  }
  return ENTITIES[body] ?? entity;
}

class Builder {
  /** In inline content: whether the next text starts a line, whose indentation then goes. */
  private lineStart = true;

  constructor(private readonly text: string, private readonly refs: Map<string, string>) {
  }

  blocks(parent: SyntaxNode): Block[] {
    const blocks: Block[] = [];
    for (let node = parent.firstChild; node; node = node.nextSibling) {
      const block = this.block(node);
      if (block) blocks.push(block);
    }
    return blocks;
  }

  block(node: SyntaxNode): Block | undefined {
    const name = node.name;
    const heading = /^ATXHeading(\d)$/.exec(name);
    if (heading) return { t: "h", pos: node.from, level: Number(heading[1]), children: this.inline(node) };
    switch (name) {
      case "Paragraph":
        return { t: "p", pos: node.from, children: this.inline(node) };
      case "Blockquote":
        return { t: "quote", pos: node.from, children: this.blocks(node) };
      case "BulletList":
      case "OrderedList":
        return this.list(node);
      case "FencedCode":
        return { t: "code", pos: node.from, text: this.codeText(node) };
      case "HorizontalRule":
        return { t: "hr", pos: node.from };
      case "MergeMark":
        return { t: "merge", pos: node.from, text: mergeMarkText(this.text.slice(node.from, node.to)) };
      case "Table":
        return this.table(node);
      case "LinkReference":
      case "QuoteMark":
      case "ListMark":
        return undefined;
      default:
        // Whatever else the grammar may produce, as typed.
        return { t: "p", pos: node.from, children: this.inline(node, true) };
    }
  }

  private list(node: SyntaxNode): Block {
    const ordered = node.name === "OrderedList";
    const items: ListItem[] = [];
    for (const item of node.getChildren("ListItem")) {
      const children: Block[] = [];
      let task: ListItem["task"];
      for (let child = item.firstChild; child; child = child.nextSibling) {
        if (child.name === "Task") {
          const marker = child.getChild("TaskMarker");
          if (marker) task = { done: /x/i.test(this.text.slice(marker.from, marker.to)), marker: marker.from };
          children.push({ t: "p", pos: child.from, children: this.inline(child) });
        } else {
          const block = this.block(child);
          if (block) children.push(block);
        }
      }
      items.push({ pos: item.from, task, children });
    }
    const first = node.getChild("ListItem")?.getChild("ListMark");
    const start = ordered && first ? parseInt(this.text.slice(first.from, first.to), 10) : 1;
    return { t: "list", pos: node.from, ordered, start: isNaN(start) ? 1 : start, items };
  }

  /**
   * The lines between the fences, without the quote marks a blockquote puts in front of them nor
   * the indentation the opening fence has (in a list item, say).
   */
  private codeText(node: SyntaxNode): string {
    const marks = node.getChildren("CodeMark");
    const open = marks[0];
    if (!open) return this.text.slice(node.from, node.to);
    const close = marks.length > 1 ? marks[marks.length - 1] : undefined;
    const quoteMarks = node.getChildren("QuoteMark");
    const firstLine = this.text.indexOf("\n", open.to);
    if (firstLine < 0) return "";
    const end = close ? this.lineStartOf(close.from) - 1 : node.to;
    if (end <= firstLine) return "";
    // Before the opening fence: quote marks (the blockquote's own, not among quoteMarks), then a list item's indentation.
    const indent = this.text.slice(this.lineStartOf(open.from), open.from).replace(/^(\s*>\s?)*/, "").length;
    const lines: string[] = [];
    let at = firstLine + 1;
    while (at <= end) {
      const nl = this.text.indexOf("\n", at);
      const lineEnd = nl < 0 || nl > end ? end : nl;
      let from = this.contentStart(at, quoteMarks);
      for (let i = 0; i < indent && from < lineEnd && this.text[from] === " "; i++) from++;
      lines.push(this.text.slice(from, lineEnd));
      at = lineEnd + 1;
    }
    return lines.join("\n");
  }

  /** Where a line's content starts, after the quote marks among {@code quoteMarks} on it (and a space after each). */
  private contentStart(lineStart: number, quoteMarks: SyntaxNode[]): number {
    let at = lineStart;
    for (const mark of quoteMarks) {
      if (mark.from < at) continue;
      if (this.text.slice(at, mark.from).trim() !== "") break;
      at = mark.to;
      if (this.text[at] === " ") at++;
    }
    return at;
  }

  private lineStartOf(pos: number): number {
    return this.text.lastIndexOf("\n", pos - 1) + 1;
  }

  /** Cells from the delimiters, not the TableCell nodes: an empty cell has none. */
  private table(node: SyntaxNode): Block {
    const delimiterRow = node.getChildren("TableDelimiter")[0];
    const align: Align[] = delimiterRow
      ? this.text.slice(delimiterRow.from, delimiterRow.to).trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map((spec) => {
        const s = spec.trim();
        if (s.startsWith(":") && s.endsWith(":")) return "center";
        if (s.endsWith(":")) return "right";
        if (s.startsWith(":")) return "left";
        return undefined;
      })
      : [];
    const header = node.getChild("TableHeader");
    const columns = Math.max(align.length, 1);
    const fit = (cells: Inline[][]) => Array.from({ length: columns }, (_, i) => cells[i] ?? []);
    return {
      t: "table",
      pos: node.from,
      align: fit([]).map((_, i) => align[i]),
      head: fit(header ? this.cells(header) : []),
      rows: node.getChildren("TableRow").map((row) => fit(this.cells(row))),
    };
  }

  /** Between the pipes; the blanks before a leading pipe and after a trailing one aren't cells. */
  private cells(row: SyntaxNode): Inline[][] {
    const delimiters = row.getChildren("TableDelimiter");
    const spans: [number, number][] = [];
    let at = row.from;
    for (const d of delimiters) {
      spans.push([at, d.from]);
      at = d.to;
    }
    spans.push([at, row.to]);
    const blank = (span: [number, number]) => this.text.slice(span[0], span[1]).trim() === "";
    if (delimiters.length && blank(spans[0])) spans.shift();
    if (delimiters.length && spans.length && blank(spans[spans.length - 1])) spans.pop();
    const cellNodes = row.getChildren("TableCell");
    return spans.map(([from, to]) => {
      const cell = cellNodes.find((c) => c.from >= from && c.to <= to);
      return cell ? this.inline(cell) : [];
    });
  }

  /** {@code raw}: the node's text as typed, its children ignored. */
  private inline(node: SyntaxNode, raw = false): Inline[] {
    this.lineStart = true;
    const out: Inline[] = [];
    if (raw) this.gap(node.from, node.to, out);
    else this.children(node, node.from, node.to, out);
    return trimEnd(out);
  }

  private children(node: SyntaxNode, from: number, to: number, out: Inline[]) {
    let at = from;
    for (let child = node.firstChild; child; child = child.nextSibling) {
      if (child.from < at || child.to > to) continue;
      this.gap(at, child.from, out);
      this.node(child, out);
      at = child.to;
    }
    this.gap(at, to, out);
  }

  /** Text between nodes: a line break stays one (notes are typed line by line). */
  private gap(from: number, to: number, out: Inline[]) {
    let at = from;
    while (at < to) {
      const nl = this.text.indexOf("\n", at);
      const lineEnd = nl < 0 || nl >= to ? to : nl;
      let a = at;
      let b = lineEnd;
      if (this.lineStart) while (a < b && /[ \t]/.test(this.text[a])) a++;
      if (lineEnd === nl) while (b > a && /[ \t]/.test(this.text[b - 1])) b--;
      if (b > a) {
        pushText(out, this.text.slice(a, b), a);
        this.lineStart = false;
      }
      if (lineEnd !== nl) break;
      out.push({ t: "br" });
      this.lineStart = true;
      at = nl + 1;
    }
  }

  private node(node: SyntaxNode, out: Inline[]) {
    const name = node.name;
    if (MARKS.has(name)) return;
    switch (name) {
      case "Emphasis":
      case "StrongEmphasis":
      case "Strikethrough": {
        const children: Inline[] = [];
        this.children(node, node.from, node.to, children);
        out.push({ t: name === "Emphasis" ? "em" : name === "StrongEmphasis" ? "strong" : "strike", children });
        break;
      }
      case "InlineCode": {
        const marks = node.getChildren("CodeMark");
        const inner = marks.length >= 2 ? this.text.slice(marks[0].to, marks[marks.length - 1].from) : this.text.slice(node.from, node.to);
        const flat = inner.replace(/\n/g, " ");
        out.push({ t: "code", text: /^ .*[^ ].* $/.test(flat) ? flat.slice(1, -1) : flat });
        this.lineStart = false;
        break;
      }
      case "Link":
      case "Image":
        out.push(this.link(node));
        this.lineStart = false;
        break;
      case "URL":
      case "Autolink": {
        const url = node.name === "URL" ? node : node.getChild("URL");
        const target = url ? this.text.slice(url.from, url.to) : this.text.slice(node.from, node.to);
        out.push({ t: "link", href: safeHref(target), children: [{ t: "text", text: target, pos: url?.from }] });
        this.lineStart = false;
        break;
      }
      case "Escape":
        pushText(out, this.text.slice(node.from + 1, node.to), node.from + 1);
        this.lineStart = false;
        break;
      case "Entity":
        pushText(out, decodeEntity(this.text.slice(node.from, node.to)));
        this.lineStart = false;
        break;
      case "HardBreak":
        out.push({ t: "br" });
        this.lineStart = true;
        break;
      default:
        this.gap(node.from, node.to, out);
    }
  }

  /** Its text between the brackets; its URL inline, from a reference, or the text as a reference. */
  private link(node: SyntaxNode): Inline {
    const marks = node.getChildren("LinkMark");
    const open = marks[0];
    const close = marks.find((mark) => this.text.slice(mark.from, mark.to) === "]");
    const children: Inline[] = [];
    if (open && close) this.children(node, open.to, close.from, children);
    const url = node.getChild("URL");
    const label = node.getChild("LinkLabel");
    let target: string | undefined;
    if (url) {
      target = destination(this.text.slice(url.from, url.to));
    } else if (open && close) {
      const labelText = label && label.to - label.from > 2 ? this.text.slice(label.from + 1, label.to - 1) : this.text.slice(open.to, close.from);
      target = this.refs.get(normalizeLabel(labelText));
      // "[text]" with no such reference is no link: as typed.
      if (target === undefined && !label) return { t: "text", text: this.text.slice(node.from, node.to), pos: node.from };
    }
    return { t: "link", href: target === undefined ? undefined : safeHref(target), children };
  }
}

/** Not merged with a text before it: each keeps the position its offsets map from. */
function pushText(out: Inline[], text: string, pos?: number) {
  if (text) out.push({ t: "text", text, pos });
}

/** No break or space at the end of a block. */
function trimEnd(out: Inline[]): Inline[] {
  while (out.length) {
    const last = out[out.length - 1];
    if (last.t === "br") {
      out.pop();
    } else if (last.t === "text" && !last.text.trim()) {
      out.pop();
    } else {
      if (last.t === "text") last.text = last.text.replace(/[ \t]+$/, "");
      break;
    }
  }
  return out;
}
