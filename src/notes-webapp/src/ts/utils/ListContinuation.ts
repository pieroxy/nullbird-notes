/** Replace the text between {@code from} and {@code to} with {@code insert}; the caret ends after it. */
export interface TextEdit {
  from: number;
  to: number;
  insert: string;
}

/** Indent, then a bullet or a number with its "." or ")", spaces, and an optional checkbox. */
const LIST_ITEM = /^([ \t]*)(?:([*+-])|(\d{1,9})([.)]))( +)(\[[ xX]\] +)?/;

/**
 * What Enter does at {@code cursor} on a list item: a new item with the same indent and marker
 * (next number, unchecked box), or, on an item with nothing after its marker, removing the marker
 * to end the list. Null when Enter should insert a plain line break: not a list item, or the caret
 * is inside the marker.
 */
export function continueList(text: string, cursor: number): TextEdit | null {
  const lineStart = text.lastIndexOf("\n", cursor - 1) + 1;
  const newline = text.indexOf("\n", cursor);
  const lineEnd = newline < 0 ? text.length : newline;
  const line = text.slice(lineStart, lineEnd);
  const match = LIST_ITEM.exec(line);
  if (!match) return null;
  const [, indent, bullet, number, delimiter, spaces, checkbox] = match;
  if (line.slice(match[0].length).trim() === "") {
    // Anywhere past the bullet or number: on an empty item, the spaces after it don't matter.
    const afterBullet = lineStart + indent.length + (bullet ?? number + delimiter).length;
    return cursor >= afterBullet ? { from: lineStart, to: lineEnd, insert: "" } : null;
  }
  if (cursor < lineStart + match[0].length) return null;
  const marker = bullet ?? String(Number(number) + 1).padStart(number.length, "0") + delimiter;
  return { from: cursor, to: cursor, insert: "\n" + indent + marker + spaces + (checkbox ? "[ ]" + checkbox.slice(3) : "") };
}
