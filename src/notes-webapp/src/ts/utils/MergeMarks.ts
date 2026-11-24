import { MERGE_TAG } from "../auto/Constants";

/**
 * The lines the server puts above each clash its merge couldn't settle (see Merge3): recognized by
 * the tag starting them, never by the sentence after it, which can change or be translated. They
 * stand out in the editor and the view, a dot marks their notes in the list, and the tag panel
 * filters on them. Removing one, once the text below it is checked, is all it takes.
 */
export function isMergeMark(line: string): boolean {
  return line.startsWith(MERGE_TAG);
}

export function hasMergeMark(text: string): boolean {
  return text.startsWith(MERGE_TAG) || text.includes("\n" + MERGE_TAG);
}

/** The sentence after the tag. */
export function mergeMarkText(line: string): string {
  return line.slice(MERGE_TAG.length).trim();
}

/**
 * What removes the line starting at {@code lineStart}, with its line break; undefined if it isn't a
 * mark there any more (the text changed since).
 */
export function removedMergeMark(text: string, lineStart: number): { from: number; to: number } | undefined {
  if (lineStart !== 0 && text[lineStart - 1] !== "\n") return undefined;
  if (!text.startsWith(MERGE_TAG, lineStart)) return undefined;
  const nl = text.indexOf("\n", lineStart);
  // The last line: the break before it goes instead.
  if (nl < 0) return { from: Math.max(0, lineStart - 1), to: text.length };
  return { from: lineStart, to: nl + 1 };
}

/** By note: a note's text is the same string until it's edited, and lists are redrawn often. */
const known = new Map<string, { text: string; has: boolean }>();

export function noteHasMergeMark(id: string, text: string): boolean {
  const entry = known.get(id);
  if (entry && entry.text === text) return entry.has;
  const has = hasMergeMark(text);
  known.set(id, { text, has });
  return has;
}

/** Notes gone: their entries too. */
export function retainMergeMarks(ids: Set<string>) {
  for (const id of known.keys()) if (!ids.has(id)) known.delete(id);
}
