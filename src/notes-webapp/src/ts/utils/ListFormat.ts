import { TextEdit } from "./ListContinuation";

export type ListKind = "bullet" | "number" | "task";

/** Indent, then a bullet or a number with its "." or ")", spaces, and an optional checkbox. */
const LIST_ITEM = /^([ \t]*)(?:([*+-])|(\d{1,9})[.)])( +)(\[[ xX]\] +)?/;
const INDENT = /^[ \t]*/;

/**
 * Makes the lines touched by {@code from}..{@code to} a list of {@code kind}, or plain lines if they
 * all already are one. Blank lines are skipped, unless they're all there is. Numbers count from 1 at
 * each indent. Edits are in document order, a line's existing checkbox kept when it stays a task.
 */
export function formatList(text: string, from: number, to: number, kind: ListKind): TextEdit[] {
  const start = text.lastIndexOf("\n", from - 1) + 1;
  // A selection ending at the start of a line doesn't take it in.
  if (to > from && text[to - 1] === "\n") to--;
  const newline = text.indexOf("\n", to);
  const end = newline < 0 ? text.length : newline;
  const lines: { pos: number; text: string }[] = [];
  let pos = start;
  for (const line of text.slice(start, end).split("\n")) {
    lines.push({ pos, text: line });
    pos += line.length + 1;
  }
  const filled = lines.filter((line) => line.text.trim() !== "");
  const touched = filled.length > 0 ? filled : lines;
  const remove = filled.length > 0 && filled.every((line) => kindOf(line.text) === kind);
  const counters = new Map<number, number>();
  const edits: TextEdit[] = [];
  for (const line of touched) {
    const match = LIST_ITEM.exec(line.text);
    const indent = match ? match[1] : INDENT.exec(line.text)![0];
    const prefixEnd = match ? match[0].length : indent.length;
    let prefix: string;
    if (remove) {
      prefix = "";
    } else if (kind === "number") {
      for (const key of counters.keys()) if (key > indent.length) counters.delete(key);
      const n = (counters.get(indent.length) ?? 0) + 1;
      counters.set(indent.length, n);
      prefix = n + ". " + (match?.[5] ?? "");
    } else if (kind === "task") {
      prefix = "- " + (match?.[5] ?? "[ ] ");
    } else {
      prefix = "- ";
    }
    if (line.text.slice(indent.length, prefixEnd) !== prefix) {
      edits.push({ from: line.pos + indent.length, to: line.pos + prefixEnd, insert: prefix });
    }
  }
  return edits;
}

function kindOf(line: string): ListKind | undefined {
  const match = LIST_ITEM.exec(line);
  if (!match) return undefined;
  if (match[5]) return "task";
  return match[2] ? "bullet" : "number";
}

/** "[]" alone at the start of a line, as typed before a space: the indent to give a task, or null. */
export function taskShorthand(lineBefore: string): string | null {
  const match = /^([ \t]*)\[\]$/.exec(lineBefore);
  return match ? match[1] : null;
}
