import { ImportSummary } from "../auto/api-types";

/** 1234 as "1,234", in the user's own way. */
export function count(n: number): string {
  return n.toLocaleString();
}

/** A size in bytes as "812 KB" or "12.5 MB". */
export function size(bytes: number): string {
  if (bytes < 1024 * 1024) return Math.max(1, Math.round(bytes / 1024)) + " KB";
  const mb = bytes / (1024 * 1024);
  return (mb < 10 ? mb.toFixed(1).replace(/\.0$/, "") : Math.round(mb).toString()) + " MB";
}

function notes(n: number): string {
  return count(n) + (n === 1 ? " note" : " notes");
}

/**
 * The summary's lines: what importing would do (before running it), or did. The notes too large
 * are listed apart, by their first lines.
 */
export function importLines(summary: ImportSummary, done: boolean): string[] {
  const lines: string[] = [];
  const trash = summary.trashed > 0 ? " (" + count(summary.trashed) + " of them to the trash)" : "";
  if (summary.imported > 0) lines.push(notes(summary.imported) + (done ? " imported" : " to import") + trash);
  else lines.push(done ? "Nothing imported." : "Nothing to import.");
  if (summary.alreadyThere > 0) lines.push(count(summary.alreadyThere) + " already there, skipped");
  if (summary.tooLarge.length > 0) {
    lines.push(count(summary.tooLarge.length) + " too large (over " + size(summary.maxNoteSize) + "), skipped:");
  }
  return lines;
}
