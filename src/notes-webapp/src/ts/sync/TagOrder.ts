import { TagOrder } from "../auto/api-types";

/**
 * The user moved {@code tag} right after {@code after}, or to the top when it's null. Kept until
 * the server has the order it led to: replayed on whatever order the server has meanwhile, so that
 * moves made on two devices both survive.
 */
export interface TagMove {
  tag: string;
  after: string | null;
}

/** As tags are listed before the user first orders them: case-insensitive first. */
export function alphabetical(tags: Iterable<string>): string[] {
  return [...new Set(tags)].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: "base" }) || (a < b ? -1 : a > b ? 1 : 0));
}

/** {@code list} with {@code move} applied. An anchor gone meanwhile: the tag goes at the end. */
export function applyMove(list: string[], move: TagMove): string[] {
  if (move.tag === move.after) return list;
  const without = list.filter((tag) => tag !== move.tag);
  if (move.after === null) return [move.tag, ...without];
  const at = without.indexOf(move.after);
  if (at < 0) return [...without, move.tag];
  return [...without.slice(0, at + 1), move.tag, ...without.slice(at + 1)];
}

export function applyMoves(list: string[], moves: TagMove[]): string[] {
  return moves.reduce(applyMove, list);
}

/**
 * The order to send the server: its own (alphabetical when it has none yet, as the user saw them)
 * with the moves not confirmed yet replayed on it.
 *
 * @param allTags every tag of every note on the device.
 */
export function orderToSend(server: TagOrder | undefined, moves: TagMove[], allTags: Iterable<string>): string[] {
  return applyMoves(server?.tags ?? alphabetical(allTags), moves);
}

/**
 * The order to show {@code shown} in: undefined while the user never ordered them (alphabetical,
 * then). Tags the server's order doesn't have yet (added on this device, not synced) go at the end.
 *
 * @param allTags every tag of every note on the device, to start from when the server has no order yet.
 */
export function displayOrder(server: TagOrder | undefined, moves: TagMove[], allTags: Iterable<string>, shown: Iterable<string>): string[] | undefined {
  if (!server && moves.length === 0) return undefined;
  const wanted = new Set(shown);
  const ordered = orderToSend(server, moves, allTags).filter((tag) => wanted.has(tag));
  const placed = new Set(ordered);
  return [...ordered, ...[...wanted].filter((tag) => !placed.has(tag))];
}

/**
 * The move that puts {@code tag} at {@code index} in {@code shown} (the tags as listed, without it
 * counted): after the one listed before that spot, or to the top.
 */
export function moveTo(shown: string[], tag: string, index: number): TagMove {
  const others = shown.filter((t) => t !== tag);
  const clamped = Math.max(0, Math.min(index, others.length));
  return { tag, after: clamped === 0 ? null : others[clamped - 1] };
}

/** The moves that put {@code order} first, as it is: each tag after the one before it. */
export function movesFor(order: string[]): TagMove[] {
  return order.map((tag, i) => ({ tag, after: i === 0 ? null : order[i - 1] }));
}
