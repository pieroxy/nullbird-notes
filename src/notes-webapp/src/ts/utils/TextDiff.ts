/**
 * What changed between two versions of a note, line by line, as shown in its history: removed
 * lines, then the lines added in their place, the words that changed in them marked. Unchanged
 * stretches fold, but for a few lines around each change.
 */

/** A run of text in a line; {@code changed}: removed (in a removed line) or added (in an added one). */
export interface Piece {
  text: string;
  changed: boolean;
}

export type LineKind = "same" | "removed" | "added";

export interface DiffLine {
  kind: LineKind;
  pieces: Piece[];
}

/** Unchanged lines folded away. */
export interface Fold {
  kind: "fold";
  lines: DiffLine[];
}

/** Past this many differences, lines are shown as all removed then all added: no slow diff on huge rewrites. */
const MAX_LINE_EDITS = 2000;
const MAX_WORD_EDITS = 200;
/** Lines this much alike (their unchanged share) get their changed words marked; others, the whole line. */
const MIN_ALIKE = 0.4;
/** Unchanged lines shown around a change. */
export const CONTEXT = 3;

const SAME = 0;
const REMOVED = 1;
const ADDED = 2;
type Op = typeof SAME | typeof REMOVED | typeof ADDED;

/**
 * Myers' O(ND) diff.
 * @return the edits turning {@code a} into {@code b}; undefined past {@code maxEdits} of them.
 */
function myers(a: string[], b: string[], maxEdits: number): Op[] | undefined {
  const n = a.length;
  const m = b.length;
  const max = Math.min(n + m, maxEdits);
  const offset = max + 1;
  const v = new Int32Array(2 * max + 3);
  // trace[d]: v before step d, for k in [-d-1, d+1].
  const trace: Int32Array[] = [];
  for (let d = 0; d <= max; d++) {
    trace.push(v.slice(offset - d - 1, offset + d + 2));
    for (let k = -d; k <= d; k += 2) {
      let x = k === -d || (k !== d && v[offset + k - 1] < v[offset + k + 1]) ? v[offset + k + 1] : v[offset + k - 1] + 1;
      let y = x - k;
      while (x < n && y < m && a[x] === b[y]) {
        x++;
        y++;
      }
      v[offset + k] = x;
      if (x >= n && y >= m) return backtrack(trace, n, m);
    }
  }
  return undefined;
}

function backtrack(trace: Int32Array[], n: number, m: number): Op[] {
  const ops: Op[] = [];
  let x = n;
  let y = m;
  for (let d = trace.length - 1; d >= 0; d--) {
    const v = trace[d];
    const at = (k: number) => v[k + d + 1];
    const k = x - y;
    const prevK = k === -d || (k !== d && at(k - 1) < at(k + 1)) ? k + 1 : k - 1;
    const prevX = at(prevK);
    const prevY = prevX - prevK;
    while (x > prevX && y > prevY) {
      ops.push(SAME);
      x--;
      y--;
    }
    if (d > 0) ops.push(x === prevX ? ADDED : REMOVED);
    x = prevX;
    y = prevY;
  }
  return ops.reverse();
}

/** The edits, the common start and end aside: most changes are in one place. */
function edits(a: string[], b: string[], maxEdits: number): Op[] | undefined {
  let start = 0;
  while (start < a.length && start < b.length && a[start] === b[start]) start++;
  let end = 0;
  while (end < a.length - start && end < b.length - start && a[a.length - 1 - end] === b[b.length - 1 - end]) end++;
  const middle = myers(a.slice(start, a.length - end), b.slice(start, b.length - end), maxEdits);
  if (!middle) return undefined;
  return [...Array<Op>(start).fill(SAME), ...middle, ...Array<Op>(end).fill(SAME)];
}

/** Words, runs of spaces, and each other character. */
export function tokens(line: string): string[] {
  return line.match(/[\p{L}\p{N}_]+|\s+|./gsu) ?? [];
}

/** Consecutive pieces alike merged. */
function pushPiece(pieces: Piece[], text: string, changed: boolean) {
  const last = pieces[pieces.length - 1];
  if (last && last.changed === changed) last.text += text;
  else pieces.push({ text, changed });
}

/** A changed line's pieces, before and after; undefined if they're too unlike for that to help. */
export function wordDiff(before: string, after: string): { removed: Piece[]; added: Piece[] } | undefined {
  const a = tokens(before);
  const b = tokens(after);
  const ops = edits(a, b, MAX_WORD_EDITS);
  if (!ops) return undefined;
  const removed: Piece[] = [];
  const added: Piece[] = [];
  let same = 0;
  let i = 0;
  let j = 0;
  for (const op of ops) {
    if (op === SAME) {
      same += a[i].length;
      pushPiece(removed, a[i++], false);
      pushPiece(added, b[j++], false);
    } else if (op === REMOVED) {
      pushPiece(removed, a[i++], true);
    } else {
      pushPiece(added, b[j++], true);
    }
  }
  if (same < MIN_ALIKE * Math.max(before.length, after.length)) return undefined;
  return { removed, added };
}

function whole(kind: LineKind, text: string): DiffLine {
  return { kind, pieces: [{ text, changed: kind !== "same" }] };
}

/** Every line of both, in order. */
export function diffLines(before: string, after: string): DiffLine[] {
  const a = before === "" ? [] : before.split("\n");
  const b = after === "" ? [] : after.split("\n");
  const ops = edits(a, b, MAX_LINE_EDITS) ?? [...Array<Op>(a.length).fill(REMOVED), ...Array<Op>(b.length).fill(ADDED)];
  const lines: DiffLine[] = [];
  let i = 0;
  let j = 0;
  let k = 0;
  while (k < ops.length) {
    if (ops[k] === SAME) {
      lines.push(whole("same", a[i++]));
      j++;
      k++;
      continue;
    }
    // A change: the lines removed, then those added; paired one to one where they're alike.
    const removed: string[] = [];
    const added: string[] = [];
    for (; k < ops.length && ops[k] !== SAME; k++) {
      if (ops[k] === REMOVED) removed.push(a[i++]);
      else added.push(b[j++]);
    }
    const removedLines = removed.map((text) => whole("removed", text));
    const addedLines = added.map((text) => whole("added", text));
    for (let p = 0; p < Math.min(removed.length, added.length); p++) {
      const words = wordDiff(removed[p], added[p]);
      if (!words) continue;
      removedLines[p] = { kind: "removed", pieces: words.removed };
      addedLines[p] = { kind: "added", pieces: words.added };
    }
    lines.push(...removedLines, ...addedLines);
  }
  return lines;
}

/** The unchanged lines more than {@code context} away from a change, folded. */
export function folded(lines: DiffLine[], context = CONTEXT): (DiffLine | Fold)[] {
  const near = new Array<boolean>(lines.length).fill(false);
  lines.forEach((line, i) => {
    if (line.kind === "same") return;
    for (let k = Math.max(0, i - context); k <= Math.min(lines.length - 1, i + context); k++) near[k] = true;
  });
  const out: (DiffLine | Fold)[] = [];
  for (let i = 0; i < lines.length;) {
    if (near[i]) {
      out.push(lines[i++]);
      continue;
    }
    const start = i;
    while (i < lines.length && !near[i]) i++;
    // A fold of one line would take as much room as the line.
    if (i - start === 1) out.push(lines[start]);
    else out.push({ kind: "fold", lines: lines.slice(start, i) });
  }
  return out;
}

export function hasChanges(lines: DiffLine[]): boolean {
  return lines.some((line) => line.kind !== "same");
}
