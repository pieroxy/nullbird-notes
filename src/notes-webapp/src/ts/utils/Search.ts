/**
 * Searching notes, as pure functions. Texts are compared folded: lowercase, accents removed, one
 * UTF-16 unit for one, so that a position in the folded text is the same in the note.
 */

export interface Range {
  start: number;
  end: number;
}

/** Past this many matches in a note, the rest aren't counted: "1000+". */
export const MAX_MATCHES = 1000;

const foldedChars = new Map<string, string>();

function foldChar(c: string): string {
  let folded = foldedChars.get(c);
  if (folded === undefined) {
    const base = c.normalize("NFD")[0].toLowerCase();
    const lower = c.toLowerCase();
    folded = base.length === 1 ? base : lower.length === 1 ? lower : c;
    foldedChars.set(c, folded);
  }
  return folded;
}

export function fold(text: string): string {
  if (!/[^\u0000-\u007f]/.test(text)) return text.toLowerCase();
  let folded = "";
  let start = 0;
  // ASCII runs in one go: notes are mostly ASCII, and a 3 MB note is folded on each edit.
  for (let i = 0; i < text.length; i++) {
    if (text.charCodeAt(i) < 0x80) continue;
    folded += text.slice(start, i).toLowerCase() + foldChar(text[i]);
    start = i + 1;
  }
  return folded + text.slice(start).toLowerCase();
}

/** Words, and phrases in double quotes, folded. An unclosed quote runs to the end. */
export function parseQuery(query: string): string[] {
  const terms: string[] = [];
  const pattern = /"([^"]*)"?|(\S+)/g;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(query)) !== null) {
    const term = fold((match[1] ?? match[2]).trim());
    if (term && !terms.includes(term)) terms.push(term);
  }
  return terms;
}

/** Every term is in the text or in a tag. */
export function matches(terms: string[], foldedText: string, foldedTags: string[]): boolean {
  return terms.every((term) => foldedText.includes(term) || foldedTags.some((tag) => tag.includes(term)));
}

/** Each occurrence of any term, in text order, without overlaps; at most {@link MAX_MATCHES} + 1. */
export function findMatches(foldedText: string, terms: string[]): Range[] {
  const all: Range[] = [];
  for (const term of terms) {
    let count = 0;
    for (let at = foldedText.indexOf(term); at >= 0 && count <= MAX_MATCHES; at = foldedText.indexOf(term, at + term.length)) {
      all.push({ start: at, end: at + term.length });
      count++;
    }
  }
  all.sort((a, b) => a.start - b.start || b.end - a.end);
  const ranges: Range[] = [];
  for (const range of all) {
    if (ranges.length > 0 && range.start < ranges[ranges.length - 1].end) continue;
    ranges.push(range);
    if (ranges.length > MAX_MATCHES) break;
  }
  return ranges;
}

/** Pieces of a line of text, the matches marked. */
export type Snippet = { text: string; match: boolean }[];

const SNIPPET_BEFORE = 30;
const SNIPPET_LENGTH = 160;

/**
 * Around the first match after the title line, for the note list; undefined without one there.
 * Starts a little before the match, at most at its line's start, which is cut with an ellipsis.
 */
export function snippet(text: string, foldedText: string, terms: string[]): Snippet | undefined {
  const titleEnd = firstLineEnd(text);
  const first = findMatches(foldedText.slice(titleEnd), terms)[0];
  if (!first) return undefined;
  const at = titleEnd + first.start;
  const lineStart = text.lastIndexOf("\n", at - 1) + 1;
  const lineEnd = text.indexOf("\n", at) < 0 ? text.length : text.indexOf("\n", at);
  let start = Math.max(lineStart, at - SNIPPET_BEFORE);
  const space = text.indexOf(" ", start);
  if (start > lineStart && space >= 0 && space < at) start = space + 1;
  const end = Math.min(lineEnd, start + SNIPPET_LENGTH);
  const pieces: Snippet = start > lineStart ? [{ text: "…", match: false }] : [];
  let position = start;
  for (const range of findMatches(foldedText.slice(start, end), terms)) {
    if (range.start > position - start) pieces.push({ text: text.slice(position, start + range.start), match: false });
    pieces.push({ text: text.slice(start + range.start, start + range.end), match: true });
    position = start + range.end;
  }
  if (position < end) pieces.push({ text: text.slice(position, end), match: false });
  return pieces;
}

/** Like the note list's title: the first non-blank line. */
function firstLineEnd(text: string): number {
  const titleStart = text.search(/\S/);
  if (titleStart < 0) return text.length;
  const newline = text.indexOf("\n", titleStart);
  return newline < 0 ? text.length : newline;
}

/** Folded texts, kept until a note's text changes. */
export class SearchIndex {
  private readonly folded = new Map<string, { text: string; folded: string }>();

  get(id: string, text: string): string {
    const cached = this.folded.get(id);
    if (cached && cached.text === text) return cached.folded;
    const folded = fold(text);
    this.folded.set(id, { text, folded });
    return folded;
  }

  /** Forgets the notes not in {@code ids}. */
  retain(ids: Set<string>) {
    for (const id of this.folded.keys()) if (!ids.has(id)) this.folded.delete(id);
  }
}
