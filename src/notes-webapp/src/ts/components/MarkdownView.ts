import m from "mithril";
import { cutAtMatches, MAX_MATCHES, Range } from "../utils/Search";
import { Block, Inline, ListItem, parseMarkdown } from "./MarkdownTree";

/** While a search is active: as the editor gets it (see FindAttrs). */
export interface ViewFind {
  matches: Range[];
  current: number;
  jump: number;
}

/** A source offset shown at {@code offset} pixels below the top (negative: partly scrolled off). */
export interface ScrollAnchor {
  pos: number;
  offset: number;
}

export interface MarkdownViewAttrs {
  text: string;
  find?: ViewFind;
  /** Where to scroll to when it shows: what the editor had at its top. */
  anchor?: ScrollAnchor;
  /** A task's box clicked: {@code marker} is the offset of its "[ ]" or "[x]". */
  onToggleTask: (marker: number) => void;
  /** Double-clicked: the source offset nearest to where. */
  onEditAt: (pos: number) => void;
}

interface Context {
  attrs: MarkdownViewAttrs;
  matches: Range[];
  current: number;
}

/**
 * A note's Markdown, rendered: elements built from the parsed tree, never HTML from the text.
 * Blocks carry their source offset ({@code data-pos}), and texts that are the source verbatim
 * theirs ({@code data-text-pos}): for a double-click to find where it was in the source, and for
 * search matches to be marked. A match in what doesn't show as typed (a link's URL, an entity)
 * isn't marked; stepping to it scrolls to its block.
 */
export class MarkdownView implements m.ClassComponent<MarkdownViewAttrs> {
  private text?: string;
  private blocks: Block[] = [];
  private lastJump?: number;

  /** At the editor's place, rather than on the current match, during a search too. */
  oncreate({ attrs, dom }: m.VnodeDOM<MarkdownViewAttrs>) {
    if (attrs.anchor) {
      scrollToAnchor(dom as HTMLElement, attrs.anchor);
      this.lastJump = attrs.find?.jump;
    }
    this.showCurrent(attrs, dom as HTMLElement);
  }

  onupdate({ attrs, dom }: m.VnodeDOM<MarkdownViewAttrs>) {
    this.showCurrent(attrs, dom as HTMLElement);
  }

  private showCurrent(attrs: MarkdownViewAttrs, dom: HTMLElement) {
    if (!attrs.find || attrs.find.jump === this.lastJump) return;
    this.lastJump = attrs.find.jump;
    const match = attrs.find.matches[attrs.find.current];
    if (!match) return;
    let target = dom.querySelector("mark.current");
    if (!target) {
      // The innermost block starting at or before the match.
      for (const block of dom.querySelectorAll<HTMLElement>("[data-pos]")) {
        if (Number(block.dataset.pos) <= match.start) target = block;
      }
    }
    target?.scrollIntoView({ block: "center" });
  }

  view({ attrs }: m.Vnode<MarkdownViewAttrs>): m.Children {
    if (attrs.text !== this.text) {
      this.text = attrs.text;
      this.blocks = parseMarkdown(attrs.text);
    }
    const ctx: Context = { attrs, matches: attrs.find?.matches.slice(0, MAX_MATCHES) ?? [], current: attrs.find?.current ?? -1 };
    return m(".mdview", { ondblclick: (e: MouseEvent) => editAt(e, attrs) },
      this.blocks.length ? this.blocks.map((block) => renderBlock(block, ctx)) : m("p.muted", "Empty note."));
  }
}

const POSITIONED = "[data-pos], [data-text-pos]";

function posOf(element: HTMLElement): number {
  return Number(element.dataset.textPos ?? element.dataset.pos);
}

/** The innermost element at the top of the view in {@code editor} (its .mdview), if it shows. */
export function viewTop(editor: HTMLElement): ScrollAnchor | undefined {
  const view = editor.querySelector<HTMLElement>(".mdview");
  if (!view) return undefined;
  const top = view.getBoundingClientRect().top;
  let best: HTMLElement | undefined;
  let bestTop = 0;
  // In document order, an element's descendants come right after it.
  for (const element of view.querySelectorAll<HTMLElement>(POSITIONED)) {
    const rect = element.getBoundingClientRect();
    if (rect.bottom <= top) continue;
    if (best && !best.contains(element)) break;
    best = element;
    bestTop = rect.top;
  }
  return best ? { pos: posOf(best), offset: bestTop - top } : undefined;
}

/** The last element starting at or before the anchor's offset (offsets grow in document order), placed as it was. */
function scrollToAnchor(view: HTMLElement, anchor: ScrollAnchor) {
  let target: HTMLElement | undefined;
  for (const element of view.querySelectorAll<HTMLElement>(POSITIONED)) {
    if (posOf(element) > anchor.pos) break;
    target = element;
  }
  if (target) view.scrollTop += target.getBoundingClientRect().top - view.getBoundingClientRect().top - anchor.offset;
}

/** Below the last block, or in an empty note: at the end. */
function editAt(e: MouseEvent, { text, onEditAt }: MarkdownViewAttrs) {
  const target = e.target as Element;
  if (target.closest("a, input")) return;
  const selection = window.getSelection();
  const node = selection?.anchorNode;
  if (node && node.nodeType === Node.TEXT_NODE) {
    const piece = node.parentElement?.closest<HTMLElement>("[data-text-pos]");
    if (piece && piece.firstChild === node) {
      onEditAt(Number(piece.dataset.textPos) + selection!.anchorOffset);
      return;
    }
  }
  const block = target.closest<HTMLElement>("[data-pos]");
  onEditAt(block ? Number(block.dataset.pos) : text.length);
}

function renderBlock(block: Block, ctx: Context): m.Children {
  const pos = { "data-pos": block.pos };
  switch (block.t) {
    case "p":
      return m("p", pos, renderInline(block.children, ctx));
    case "h":
      return m("h" + block.level, pos, renderInline(block.children, ctx));
    case "quote":
      return m("blockquote", pos, block.children.map((child) => renderBlock(child, ctx)));
    case "list":
      return m(block.ordered ? "ol" : "ul", { ...pos, start: block.ordered && block.start !== 1 ? block.start : undefined },
        block.items.map((item) => renderItem(item, ctx)));
    case "code":
      // Its text is the source's, but for the quote marks and indentation taken out: not marked.
      return m("pre", pos, m("code", block.text));
    case "hr":
      return m("hr", pos);
    case "table":
      return m(".tablewrap", pos, m("table", [
        m("thead", m("tr", block.head.map((cell, i) => m("th", { class: alignClass(block.align[i]) }, renderInline(cell, ctx))))),
        m("tbody", block.rows.map((row) => m("tr", row.map((cell, i) => m("td", { class: alignClass(block.align[i]) }, renderInline(cell, ctx)))))),
      ]));
  }
}

function alignClass(align: string | undefined): string | undefined {
  return align ? "align" + align : undefined;
}

function renderItem(item: ListItem, ctx: Context): m.Children {
  const children = item.children.map((child) => renderBlock(child, ctx));
  if (!item.task) return m("li", { "data-pos": item.pos }, children);
  const task = item.task;
  return m("li.task" + (task.done ? ".done" : ""), { "data-pos": item.pos }, [
    m("input", { type: "checkbox", checked: task.done, onchange: () => ctx.attrs.onToggleTask(task.marker), "aria-label": task.done ? "Done" : "To do" }),
    m(".taskbody", children),
  ]);
}

function renderInline(nodes: Inline[], ctx: Context): m.Children {
  return nodes.map((node) => {
    switch (node.t) {
      case "text":
        return node.pos === undefined ? node.text : renderText(node.text, node.pos, ctx);
      case "br":
        return m("br");
      case "em":
        return m("em", renderInline(node.children, ctx));
      case "strong":
        return m("strong", renderInline(node.children, ctx));
      case "strike":
        return m("s", renderInline(node.children, ctx));
      case "code":
        return m("code", node.text);
      case "link":
        return node.href
          ? m("a", { href: node.href, target: "_blank", rel: "noopener noreferrer" }, renderInline(node.children, ctx))
          : m("span.deadlink", renderInline(node.children, ctx));
    }
  });
}

/** Each piece its own element, holding only its text: an offset in it maps back to the source. */
function renderText(text: string, pos: number, ctx: Context): m.Children {
  return cutAtMatches(text, pos, ctx.matches).map((piece) => piece.match < 0
    ? m("span", { "data-text-pos": piece.pos }, piece.text)
    : m("mark" + (piece.match === ctx.current ? ".current" : ""), { "data-text-pos": piece.pos }, piece.text));
}
