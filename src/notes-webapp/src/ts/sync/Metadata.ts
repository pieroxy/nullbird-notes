import { MetaChange, NoteInfo, PushResult, PushStatus } from "../auto/api-types";
import { LocalNote, MetaOps } from "./LocalNote";

/**
 * A note's tags and options, as pure functions. What the user sees is the server's values with
 * the operations not acknowledged yet applied on top: in flight first, then not sent.
 */

// The boolean options (see isOn), as the server names them (NoteOptions.java).
/** Shown at the top of the list. */
export const PINNED = "pinned";
/** Its text rendered as Markdown. */
export const MARKDOWN = "markdown";
export const MONOSPACE = "monospace";
export const NOWRAP = "nowrap";
export const MAX_TAG_LENGTH = 64;

/** As the server: 1 to {@link MAX_TAG_LENGTH} characters, no whitespace. */
export function isValidTag(tag: string): boolean {
  const length = [...tag].length;
  return length > 0 && length <= MAX_TAG_LENGTH && !/[\s\p{Zs}]/u.test(tag);
}

export function tagsOf(note: LocalNote): string[] {
  let tags = note.info?.tags ?? [];
  for (const ops of opsOf(note)) {
    tags = tags.filter((tag) => !ops.removeTags.includes(tag));
    for (const tag of ops.addTags) if (!tags.includes(tag)) tags = [...tags, tag];
  }
  return tags;
}

export function optionsOf(note: LocalNote): { [key: string]: string } {
  const options = { ...note.info?.options };
  for (const ops of opsOf(note)) {
    for (const key of ops.unsetOptions) delete options[key];
    Object.assign(options, ops.setOptions);
  }
  return options;
}

/** A boolean option: on when set to "true". */
export function isOn(note: LocalNote, key: string): boolean {
  return optionsOf(note)[key] === "true";
}

function opsOf(note: LocalNote): MetaOps[] {
  return [note.pendingMeta?.ops, note.metaOps].filter((ops): ops is MetaOps => ops !== undefined);
}

export function noOps(): MetaOps {
  return { addTags: [], removeTags: [], setOptions: {}, unsetOptions: [] };
}

export function addTag(ops: MetaOps, tag: string): MetaOps {
  return { ...ops, addTags: ops.addTags.includes(tag) ? ops.addTags : [...ops.addTags, tag], removeTags: ops.removeTags.filter((t) => t !== tag) };
}

export function removeTag(ops: MetaOps, tag: string): MetaOps {
  return { ...ops, addTags: ops.addTags.filter((t) => t !== tag), removeTags: ops.removeTags.includes(tag) ? ops.removeTags : [...ops.removeTags, tag] };
}

/** {@code value} undefined resets the option to its default. */
export function setOption(ops: MetaOps, key: string, value: string | undefined): MetaOps {
  const setOptions = { ...ops.setOptions };
  delete setOptions[key];
  const unsetOptions = ops.unsetOptions.filter((k) => k !== key);
  if (value === undefined) return { ...ops, setOptions, unsetOptions: [...unsetOptions, key] };
  return { ...ops, setOptions: { ...setOptions, [key]: value }, unsetOptions };
}

/** {@code later} applied after {@code earlier}, as one set of operations. */
export function mergeOps(earlier: MetaOps, later: MetaOps): MetaOps {
  let ops = earlier;
  for (const tag of later.removeTags) ops = removeTag(ops, tag);
  for (const tag of later.addTags) ops = addTag(ops, tag);
  for (const key of later.unsetOptions) ops = setOption(ops, key, undefined);
  for (const [key, value] of Object.entries(later.setOptions)) ops = setOption(ops, key, value);
  return ops;
}

/** Operations wait for the server to have the note: until then, a change to it would be refused. */
export function hasMetaToPush(note: LocalNote): boolean {
  return note.metaOps !== undefined && !note.pendingMeta && note.baseRevision > 0;
}

/** {@code note} with its metadata push in flight: saved before sending, so that a retry reuses the changeId. */
export function prepareMeta(note: LocalNote, changeId: string): LocalNote {
  return { ...note, pendingMeta: { changeId, ops: note.metaOps! }, metaOps: undefined };
}

export function metaChangeOf(note: LocalNote): MetaChange {
  const { changeId, ops } = note.pendingMeta!;
  return { changeId, noteId: note.id, ...ops };
}

/** {@code note} after the server's answer to its metadata push. A refusal drops the operations. */
export function applyMetaResult(note: LocalNote, result: PushResult): LocalNote {
  const cleared = { ...note, pendingMeta: undefined };
  if (result.status === PushStatus.REJECTED || !result.note) {
    return { ...cleared, metaError: { reason: result.reason ?? "UNKNOWN", message: result.message ?? "Refused by the server." } };
  }
  return { ...cleared, metaError: undefined, info: newer(note.info, result.note) };
}

function newer(current: NoteInfo | undefined, candidate: NoteInfo): NoteInfo {
  return current && current.seq > candidate.seq ? current : candidate;
}

/** The metadata state of {@code from}, carried over onto a rebuilt note. */
export function metaStateOf(from: LocalNote | undefined): Pick<LocalNote, "metaOps" | "pendingMeta" | "metaError"> {
  return from ? { metaOps: from.metaOps, pendingMeta: from.pendingMeta, metaError: from.metaError } : {};
}
