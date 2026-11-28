/**
 * Bold and italic in Markdown, toggled on a selection: "**" and "*" around it. Runs of asterisks
 * count as both: "***" is bold and italic, so that toggling one of them leaves the other.
 */

export type InlineStyle = "bold" | "italic";

export interface InlineEdit {
  changes: { from: number; to: number; insert: string }[];
  /** The selection after, over the same text. */
  anchor: number;
  head: number;
}

const MARKERS: Record<InlineStyle, string> = { bold: "**", italic: "*" };
const BITS: Record<InlineStyle, number> = { bold: 2, italic: 1 };

/** Asterisks in a row from {@code at}, going by {@code step}; at most 3. */
function run(text: string, at: number, step: 1 | -1, limit: number): number {
  let count = 0;
  while (count < 3 && count < limit && text[at + count * step] === "*") count++;
  return count;
}

/** Whether a run of {@code stars} on both sides holds {@code style}. */
function holds(stars: number, style: InlineStyle): boolean {
  return (stars & BITS[style]) !== 0;
}

/**
 * Toggles {@code style} on {@code from..to} of {@code text}: removed if the selection holds it, its
 * markers inside or right outside it; added otherwise, spaces at the selection's ends left out of
 * it (Markdown wants none inside). With nothing selected, a pair of markers, the caret between.
 */
export function toggleInline(text: string, from: number, to: number, style: InlineStyle): InlineEdit {
  const marker = MARKERS[style];
  const length = marker.length;
  // Inside the selection: "**word**" selected.
  const inner = Math.min(run(text, from, 1, to - from), run(text, to - 1, -1, to - from));
  if (from < to && holds(inner, style) && 2 * inner <= to - from) {
    return {
      changes: [{ from, to: from + length, insert: "" }, { from: to - length, to, insert: "" }],
      anchor: from, head: to - 2 * length,
    };
  }
  // Right outside it: "word" selected in "**word**", or the caret in "**|**".
  const outer = Math.min(run(text, from - 1, -1, from), run(text, to, 1, text.length - to));
  if (holds(outer, style)) {
    return {
      changes: [{ from: from - length, to: from, insert: "" }, { from: to, to: to + length, insert: "" }],
      anchor: from - length, head: to - length,
    };
  }
  let start = from;
  let end = to;
  while (start < end && /\s/.test(text[start])) start++;
  while (end > start && /\s/.test(text[end - 1])) end--;
  if (start === end && from !== to) {
    // Only spaces selected: the pair goes where the selection started.
    end = start = from;
  }
  return {
    changes: [{ from: start, to: start, insert: marker }, { from: end, to: end, insert: marker }],
    anchor: start + length, head: end + length,
  };
}
