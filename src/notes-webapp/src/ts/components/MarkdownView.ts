import m from "mithril";
import { Block, Inline, ListItem, parseMarkdown } from "./MarkdownTree";

export interface MarkdownViewAttrs {
  text: string;
  /** A task's box clicked: {@code marker} is the offset of its "[ ]" or "[x]". */
  onToggleTask: (marker: number) => void;
  /** Double-clicked: the source offset nearest to where. */
  onEditAt: (pos: number) => void;
}

/**
 * A note's Markdown, rendered: elements built from the parsed tree, never HTML from the text.
 * Blocks carry their source offset ({@code data-pos}), and texts that are the source verbatim
 * theirs ({@code data-text-pos}), for a double-click to find where it was in the source.
 */
export class MarkdownView implements m.ClassComponent<MarkdownViewAttrs> {
  private text?: string;
  private blocks: Block[] = [];

  view({ attrs }: m.Vnode<MarkdownViewAttrs>): m.Children {
    if (attrs.text !== this.text) {
      this.text = attrs.text;
      this.blocks = parseMarkdown(attrs.text);
    }
    return m(".mdview", { ondblclick: (e: MouseEvent) => editAt(e, attrs) },
      this.blocks.length ? this.blocks.map((block) => renderBlock(block, attrs)) : m("p.muted", "Empty note."));
  }
}

/** Below the last block, or in an empty note: at the end. */
function editAt(e: MouseEvent, { text, onEditAt }: MarkdownViewAttrs) {
  const target = e.target as Element;
  if (target.closest("a, input")) return;
  const selection = window.getSelection();
  const node = selection?.anchorNode;
  if (node && node.nodeType === Node.TEXT_NODE) {
    const span = node.parentElement?.closest<HTMLElement>("[data-text-pos]");
    if (span && span.firstChild === node) {
      onEditAt(Number(span.dataset.textPos) + selection!.anchorOffset);
      return;
    }
  }
  const block = target.closest<HTMLElement>("[data-pos]");
  onEditAt(block ? Number(block.dataset.pos) : text.length);
}

function renderBlock(block: Block, attrs: MarkdownViewAttrs): m.Children {
  const pos = { "data-pos": block.pos };
  switch (block.t) {
    case "p":
      return m("p", pos, renderInline(block.children));
    case "h":
      return m("h" + block.level, pos, renderInline(block.children));
    case "quote":
      return m("blockquote", pos, block.children.map((child) => renderBlock(child, attrs)));
    case "list":
      return m(block.ordered ? "ol" : "ul", { ...pos, start: block.ordered && block.start !== 1 ? block.start : undefined },
        block.items.map((item) => renderItem(item, attrs)));
    case "code":
      return m("pre", pos, m("code", block.text));
    case "hr":
      return m("hr", pos);
    case "table":
      return m(".tablewrap", pos, m("table", [
        m("thead", m("tr", block.head.map((cell, i) => m("th", { class: alignClass(block.align[i]) }, renderInline(cell))))),
        m("tbody", block.rows.map((row) => m("tr", row.map((cell, i) => m("td", { class: alignClass(block.align[i]) }, renderInline(cell)))))),
      ]));
  }
}

function alignClass(align: string | undefined): string | undefined {
  return align ? "align" + align : undefined;
}

function renderItem(item: ListItem, attrs: MarkdownViewAttrs): m.Children {
  const children = item.children.map((child) => renderBlock(child, attrs));
  if (!item.task) return m("li", { "data-pos": item.pos }, children);
  const task = item.task;
  return m("li.task" + (task.done ? ".done" : ""), { "data-pos": item.pos }, [
    m("input", { type: "checkbox", checked: task.done, onchange: () => attrs.onToggleTask(task.marker), "aria-label": task.done ? "Done" : "To do" }),
    m(".taskbody", children),
  ]);
}

function renderInline(nodes: Inline[]): m.Children {
  return nodes.map((node) => {
    switch (node.t) {
      case "text":
        return node.pos === undefined ? node.text : m("span", { "data-text-pos": node.pos }, node.text);
      case "br":
        return m("br");
      case "em":
        return m("em", renderInline(node.children));
      case "strong":
        return m("strong", renderInline(node.children));
      case "strike":
        return m("s", renderInline(node.children));
      case "code":
        return m("code", node.text);
      case "link":
        return node.href
          ? m("a", { href: node.href, target: "_blank", rel: "noopener noreferrer" }, renderInline(node.children))
          : m("span.deadlink", renderInline(node.children));
    }
  });
}
