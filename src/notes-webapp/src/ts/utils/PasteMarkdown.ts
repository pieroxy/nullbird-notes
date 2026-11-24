/**
 * Pasted HTML as Markdown, for a Markdown note: what the notes' Markdown can hold is kept (bold,
 * italic, strikethrough, links, lists and tasks, headings, code, quotes, tables, paragraphs and
 * line breaks); the rest (colors, fonts, sizes, images) becomes plain text or goes.
 * <p>
 * Works on the DOM's interface, whatever builds it: the page parses with DOMParser, inert (no
 * script runs, nothing loads); tests build nodes by hand. Bold, italic and strikethrough are read
 * from styles too: Google Docs marks them that way, and wraps everything in a <b> that isn't bold.
 */
export interface PasteNode {
  nodeType: number;
  nodeName: string;
  textContent: string | null;
  childNodes: ArrayLike<PasteNode>;
  getAttribute?(name: string): string | null;
}

const TEXT = 3;
/** What style attributes are renamed to before parsing (see htmlToMarkdown). */
const RENAMED_STYLE = "data-paste-style";
const ELEMENT = 1;

const BLOCKS = new Set(["P", "DIV", "SECTION", "ARTICLE", "HEADER", "FOOTER", "MAIN", "ASIDE", "NAV", "FIGURE", "FIGCAPTION",
  "ADDRESS", "DL", "DT", "DD", "CENTER", "FORM", "FIELDSET", "BODY", "HTML"]);
const SKIPPED = new Set(["SCRIPT", "STYLE", "HEAD", "TITLE", "META", "LINK", "NOSCRIPT", "TEMPLATE", "SVG", "BUTTON", "SELECT", "TEXTAREA"]);
const SAFE_LINK = /^(https?:|mailto:)/i;

interface Style {
  bold: boolean;
  italic: boolean;
  strike: boolean;
}

interface Ctx extends Style {
  /** Something kept beyond plain text: otherwise the plain text is pasted as is. */
  kept: { any: boolean };
}

function attr(node: PasteNode, name: string): string | null {
  return node.getAttribute ? node.getAttribute(name) : null;
}

/** The declarations of an element's style attribute, by property. */
function styleOf(node: PasteNode): Map<string, string> {
  const map = new Map<string, string>();
  for (const declaration of (attr(node, "style") ?? attr(node, RENAMED_STYLE) ?? "").split(";")) {
    const at = declaration.indexOf(":");
    if (at > 0) map.set(declaration.slice(0, at).trim().toLowerCase(), declaration.slice(at + 1).trim().toLowerCase());
  }
  return map;
}

function isBoldWeight(weight: string | undefined): boolean | undefined {
  if (!weight) return undefined;
  if (weight === "bold" || weight === "bolder") return true;
  if (weight === "normal" || weight === "lighter") return false;
  const n = parseInt(weight, 10);
  return isNaN(n) ? undefined : n >= 600;
}

/** The element's bold, italic and strikethrough, its tag first, its style over it. */
function styled(node: PasteNode, ctx: Ctx): Ctx {
  const name = node.nodeName;
  const style = styleOf(node);
  let bold = ctx.bold || name === "B" || name === "STRONG";
  const weight = isBoldWeight(style.get("font-weight"));
  if (weight !== undefined) bold = weight;
  let italic = ctx.italic || name === "I" || name === "EM";
  const fontStyle = style.get("font-style");
  if (fontStyle) italic = fontStyle === "italic" || fontStyle === "oblique";
  const decoration = style.get("text-decoration") ?? style.get("text-decoration-line") ?? "";
  const strike = ctx.strike || name === "S" || name === "DEL" || name === "STRIKE" || decoration.includes("line-through");
  return { ...ctx, bold, italic, strike };
}

/** Plain text, its characters that would make Markdown escaped. */
function escape(text: string): string {
  return text
    .replace(/([\\`*[\]~])/g, "\\$1")
    // An underscore inside a word never emphasizes: only those at a word's edge.
    .replace(/(^|[^\p{L}\p{N}])_|_(?=[^\p{L}\p{N}]|$)/gu, (m) => m.replace("_", "\\_"));
}

/** At a line's start, what would make it a heading, list item, quote or rule. */
function escapeLineStart(line: string): string {
  return line.replace(/^(\s*)(#{1,6}\s|[-+]\s|>|\d{1,9}[.)]\s)/, (_m, space: string, mark: string) => space + "\\" + mark);
}

/** Markers around a text, its spaces kept outside: "** bold**" wouldn't be bold. */
function wrap(text: string, marker: string): string {
  const match = /^(\s*)([\s\S]*?)(\s*)$/.exec(text)!;
  return match[2] ? match[1] + marker + match[2] + marker + match[3] : text;
}

/** Inline content as Markdown, on one or more lines (breaks kept). */
function inline(nodes: ArrayLike<PasteNode>, ctx: Ctx): string {
  let out = "";
  for (const node of Array.from(nodes)) out += inlineNode(node, ctx);
  return out;
}

function inlineNode(node: PasteNode, ctx: Ctx): string {
  if (node.nodeType === TEXT) {
    const text = (node.textContent ?? "").replace(/[\s ]+/g, " ");
    return text ? escape(text) : "";
  }
  if (node.nodeType !== ELEMENT || SKIPPED.has(node.nodeName)) return "";
  const name = node.nodeName;
  if (name === "BR") return "\n";
  if (name === "IMG") return "";
  if (name === "INPUT") return "";
  if (name === "CODE" || name === "KBD" || name === "SAMP" || name === "TT") {
    const text = (node.textContent ?? "").replace(/\s+/g, " ");
    if (!text.trim()) return text;
    ctx.kept.any = true;
    const longest = Math.max(0, ...(text.match(/`+/g) ?? []).map((run) => run.length));
    const fence = "`".repeat(longest + 1);
    return fence + (text.startsWith("`") || text.endsWith("`") ? " " + text + " " : text) + fence;
  }
  if (name === "A") {
    const href = (attr(node, "href") ?? "").trim();
    const text = inline(node.childNodes, ctx).replace(/\n+/g, " ");
    if (!SAFE_LINK.test(href)) return text;
    ctx.kept.any = true;
    const url = href.replace(/\(/g, "%28").replace(/\)/g, "%29").replace(/ /g, "%20");
    const label = text.trim() || escape(href);
    return label === escape(href) ? href : "[" + label + "](" + url + ")";
  }
  const inner = styled(node, ctx);
  let text = inline(node.childNodes, inner);
  // Markers only where the style starts: nested bold isn't bold twice.
  if (inner.strike && !ctx.strike) text = mark(text, "~~", ctx);
  if (inner.italic && !ctx.italic) text = mark(text, "*", ctx);
  if (inner.bold && !ctx.bold) text = mark(text, "**", ctx);
  return text;
}

/** Each line wrapped on its own: markers don't cross a line break. */
function mark(text: string, marker: string, ctx: Ctx): string {
  if (!text.trim()) return text;
  ctx.kept.any = true;
  return text.split("\n").map((line) => wrap(line, marker)).join("\n");
}

/** Lines of inline content, trimmed, line starts escaped. */
function paragraph(text: string): string {
  return text.split("\n").map((line) => escapeLineStart(line.trim())).filter((line, i, all) => line || (i > 0 && i < all.length - 1)).join("\n").trim();
}

/** Block content as Markdown blocks. */
function blocks(nodes: ArrayLike<PasteNode>, ctx: Ctx): string[] {
  const out: string[] = [];
  let pending: PasteNode[] = [];
  const flush = () => {
    if (!pending.length) return;
    const text = paragraph(inline(pending, ctx));
    if (text) out.push(text);
    pending = [];
  };
  for (const node of Array.from(nodes)) {
    if (node.nodeType === ELEMENT && !isBlock(node) && !SKIPPED.has(node.nodeName) && holdsBlocks(node)) {
      // An inline element around blocks (Google Docs wraps everything in a <b>): a container.
      flush();
      out.push(...blocks(node.childNodes, ctx));
      continue;
    }
    if (node.nodeType !== ELEMENT || !isBlock(node)) {
      pending.push(node);
      continue;
    }
    flush();
    out.push(...block(node, ctx));
  }
  flush();
  return out;
}

function holdsBlocks(node: PasteNode): boolean {
  return Array.from(node.childNodes).some((child) => child.nodeType === ELEMENT && (isBlock(child) || holdsBlocks(child)));
}

function isBlock(node: PasteNode): boolean {
  const name = node.nodeName;
  return BLOCKS.has(name) || /^H[1-6]$/.test(name) || ["UL", "OL", "LI", "PRE", "BLOCKQUOTE", "TABLE", "HR"].includes(name);
}

function block(node: PasteNode, ctx: Ctx): string[] {
  const name = node.nodeName;
  if (SKIPPED.has(name)) return [];
  const heading = /^H([1-6])$/.exec(name);
  if (heading) {
    const text = inline(node.childNodes, styled(node, ctx)).replace(/\s*\n\s*/g, " ").trim();
    if (!text) return [];
    ctx.kept.any = true;
    return ["#".repeat(Number(heading[1])) + " " + text];
  }
  if (name === "UL" || name === "OL") return [list(name === "OL", parseInt(attr(node, "start") ?? "1", 10), node.childNodes, ctx)].filter(Boolean);
  // An item outside a list (a fragment copied from inside one): an item still.
  if (name === "LI") return [list(false, 1, [node], ctx)].filter(Boolean);
  if (name === "PRE") {
    const text = (node.textContent ?? "").replace(/\n$/, "");
    if (!text.trim()) return [];
    ctx.kept.any = true;
    const code = Array.from(node.childNodes).find((child) => child.nodeName === "CODE");
    const language = /(?:^|\s)(?:language|lang)-([\w+-]+)/.exec((code && attr(code, "class")) ?? attr(node, "class") ?? "")?.[1] ?? "";
    const longest = Math.max(2, ...(text.match(/^`{3,}/gm) ?? []).map((run) => run.length));
    const fence = "`".repeat(longest + 1);
    return [fence + language + "\n" + text + "\n" + fence];
  }
  if (name === "BLOCKQUOTE") {
    const inner = blocks(node.childNodes, ctx).join("\n\n");
    if (!inner) return [];
    ctx.kept.any = true;
    return [inner.split("\n").map((line) => (line ? "> " + line : ">")).join("\n")];
  }
  if (name === "HR") {
    ctx.kept.any = true;
    return ["---"];
  }
  if (name === "TABLE") return table(node, ctx);
  return blocks(node.childNodes, styled(node, ctx));
}

/** A list, its items' continuation lines and nested lists indented under their text. */
function list(ordered: boolean, start: number, children: ArrayLike<PasteNode>, ctx: Ctx): string {
  let number = isNaN(start) ? 1 : start;
  const items: string[] = [];
  for (const child of Array.from(children)) {
    if (child.nodeType !== ELEMENT) continue;
    if (child.nodeName === "UL" || child.nodeName === "OL") {
      // A list right in a list, as some editors write a nested one: under the previous item.
      const nested = list(child.nodeName === "OL", parseInt(attr(child, "start") ?? "1", 10), child.childNodes, ctx);
      if (nested && items.length) items[items.length - 1] += "\n" + indent(nested, ordered ? 3 : 2);
      else if (nested) items.push(nested);
      continue;
    }
    if (child.nodeName !== "LI") continue;
    const box = findCheckbox(child);
    const marker = (ordered ? number++ + ". " : "- ") + (box ? (attr(box, "checked") !== null ? "[x] " : "[ ] ") : "");
    const content = blocks(child.childNodes, ctx).join("\n");
    if (!content && !box) continue;
    items.push(marker + indent(content, marker.length).trimStart());
  }
  if (!items.length) return "";
  ctx.kept.any = true;
  return items.join("\n");
}

function findCheckbox(node: PasteNode): PasteNode | undefined {
  for (const child of Array.from(node.childNodes)) {
    if (child.nodeName === "INPUT" && (attr(child, "type") ?? "").toLowerCase() === "checkbox") return child;
    if (child.nodeName === "P" || child.nodeName === "LABEL" || child.nodeName === "SPAN") {
      const found = findCheckbox(child);
      if (found) return found;
    }
  }
  return undefined;
}

function indent(text: string, by: number): string {
  const pad = " ".repeat(by);
  return text.split("\n").map((line, i) => (i === 0 || !line ? line : pad + line)).join("\n");
}

function rowsOf(node: PasteNode): PasteNode[] {
  const rows: PasteNode[] = [];
  for (const child of Array.from(node.childNodes)) {
    if (child.nodeName === "TR") rows.push(child);
    else if (["THEAD", "TBODY", "TFOOT"].includes(child.nodeName)) rows.push(...rowsOf(child));
  }
  return rows;
}

const LAYOUT = new Set(["TABLE", "UL", "OL", "PRE", "BLOCKQUOTE", "H1", "H2", "H3", "H4", "H5", "H6"]);

function hasLayout(node: PasteNode): boolean {
  return Array.from(node.childNodes).some((child) => LAYOUT.has(child.nodeName) || (child.nodeType === ELEMENT && hasLayout(child)));
}

/**
 * A table of data as a Markdown table, its first row the header. One laying a page out (an email's,
 * holding lists, headings, other tables…), or of one column, is just its cells' content.
 */
function table(node: PasteNode, ctx: Ctx): string[] {
  const rows = rowsOf(node).map((row) => Array.from(row.childNodes).filter((cell) => cell.nodeName === "TD" || cell.nodeName === "TH"));
  const width = Math.max(0, ...rows.map((row) => row.length));
  if (width < 2 || hasLayout(node)) return rows.flatMap((row) => row.flatMap((cell) => blocks(cell.childNodes, ctx)));
  ctx.kept.any = true;
  const text = rows.map((row) => Array.from({ length: width }, (_, i) =>
    row[i] ? inline(row[i].childNodes, styled(row[i], ctx)).replace(/\s*\n\s*/g, " ").replace(/\|/g, "\\|").trim() : ""));
  const line = (cells: string[]) => "| " + cells.join(" | ") + " |";
  return [[line(text[0]), line(Array(width).fill("---")), ...text.slice(1).map(line)].join("\n")];
}

/**
 * Code from a code editor: its HTML only colors it, and is laid out preformatted or in a
 * fixed-width font from the top. Its plain text is the paste.
 */
function isEditorCode(root: PasteNode): boolean {
  const top = Array.from(root.childNodes).filter((n) => n.nodeType === ELEMENT || (n.nodeType === TEXT && n.textContent?.trim()));
  if (top.length !== 1 || top[0].nodeType !== ELEMENT) return false;
  const style = styleOf(top[0]);
  return (style.get("white-space") ?? "").startsWith("pre") || /monospace|consolas|courier|menlo/.test(style.get("font-family") ?? "");
}

/**
 * {@code root} (the parsed HTML's body) as Markdown; undefined when nothing of its formatting
 * would be kept, or it's code from an editor: the plain text is pasted as is, then.
 */
export function toMarkdown(root: PasteNode): string | undefined {
  if (isEditorCode(root)) return undefined;
  const ctx: Ctx = { bold: false, italic: false, strike: false, kept: { any: false } };
  const text = blocks(root.childNodes, ctx).join("\n\n").replace(/\n{3,}/g, "\n\n").trim();
  return ctx.kept.any && text ? text : undefined;
}

/** Allowed by the CSP's trusted-types directive (see SecurityHeadersFilter); created once, here only. */
const POLICY = "notes-paste";

/** The part of Trusted Types used here: TypeScript's DOM types don't have it yet. */
interface TrustedTypesWindow {
  trustedTypes?: { createPolicy(name: string, rules: { createHTML(html: string): string }): { createHTML(html: string): unknown } };
}

let policy: { createHTML(html: string): unknown } | undefined;

/**
 * The CSP requires Trusted Types for HTML. This policy passes the clipboard's HTML through for
 * DOMParser alone, which builds an inert document: no script runs, nothing loads. Kept in this
 * module: nothing else can create it, the CSP allowing it once.
 */
function trusted(html: string): string {
  const trustedTypes = (window as TrustedTypesWindow).trustedTypes;
  if (!trustedTypes) return html;
  policy ??= trustedTypes.createPolicy(POLICY, { createHTML: (candidate) => candidate });
  return policy.createHTML(html) as string;
}

/**
 * In the page: parsed inert, with DOMParser. Its style attributes renamed first, inside tags only:
 * the parsed document has the page's CSP, which would report each one as an inline style refused.
 */
export function htmlToMarkdown(html: string): string | undefined {
  const renamed = html.replace(/<[a-z][^<>]*>/gi, (tag) => tag.replace(/(\s)style\s*=/gi, "$1" + RENAMED_STYLE + "="));
  const doc = new DOMParser().parseFromString(trusted(renamed), "text/html");
  return toMarkdown(doc.body as unknown as PasteNode);
}
