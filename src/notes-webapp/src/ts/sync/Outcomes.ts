import { NoteChange, NoteContent, NoteInfo, PushResult, PushStatus, RejectReason } from "../auto/api-types";
import { LocalNote } from "./LocalNote";
import { mergeOps, metaStateOf, noOps } from "./Metadata";
import { applySplice, computeSplice } from "./Splice";

/**
 * Sync's decisions about one note, as pure functions: what to push, and how the server's answers
 * change the local note. The rule throughout: what the user typed is never overwritten. Text typed
 * while a push was in flight stays local, derived from what was sent ({@link LocalNote.basedOnChangeId}).
 */

/** Notes the server can take: dirty, not already in flight, not stuck on an error. */
export function isPushable(note: LocalNote): boolean {
  return note.dirty && !note.pending && !note.error;
}

/** {@code note} with its push in flight: the pending change is saved before sending. */
export function prepareChange(note: LocalNote, changeId: string): { change: Omit<NoteChange, "contentHash">; note: LocalNote } {
  const pendingNote = { ...note, pending: { changeId, submitted: note.deleted ? note.baseText : note.text, deleted: note.deleted } };
  return { change: changeOf(pendingNote), note: pendingNote };
}

/**
 * The change {@code note}'s pending push sends, without its contentHash (computing it is
 * asynchronous). Rebuilt identically for a retry, whatever was typed since: same changeId, same text.
 */
export function changeOf(note: LocalNote): Omit<NoteChange, "contentHash"> {
  const pending = note.pending!;
  const change: Omit<NoteChange, "contentHash"> = {
    changeId: pending.changeId,
    noteId: note.id,
    baseRevision: note.baseRevision,
    basedOnChangeId: note.basedOnChangeId,
    deleted: pending.deleted,
  };
  if (!pending.deleted) {
    const splice = computeSplice(note.baseText, pending.submitted);
    if (!note.forceFull && splice.middle.length < pending.submitted.length) change.splice = splice;
    else change.content = pending.submitted;
  }
  return change;
}

/**
 * {@code note} after the server's answer to its pending push, plus the conflict copy when there
 * is one. {@code movedTo} is the copy, which holds this device's text: an editor showing the note
 * should switch to it. {@code removed}: the note is gone (a delete of a note the server never had).
 */
export function applyPushResult(note: LocalNote, result: PushResult):
    { notes: LocalNote[]; movedTo?: string; removed?: boolean } {
  const pending = note.pending!;
  const sent = pending.submitted;
  const typedSince = !pending.deleted && (note.deleted || note.text !== sent);
  const cleared: LocalNote = { ...note, pending: undefined, error: undefined, forceFull: false };

  switch (result.status) {
    case PushStatus.APPLIED:
    case PushStatus.MERGED: {
      if (!result.note) return { notes: [], removed: true };
      const stored = result.content ? applySplice(result.content, sent) : sent;
      if (pending.deleted) {
        return { notes: [{ ...cleared, info: result.note, baseRevision: result.note.revision, baseText: stored, basedOnChangeId: undefined, dirty: false }] };
      }
      return { notes: [settle(cleared, result.note, stored, typedSince, sent, pending.changeId)] };
    }
    case PushStatus.CONFLICT: {
      const original = result.content ? applySplice(result.content, sent) : sent;
      // Like content: absent when the copy holds exactly what was sent.
      const copyText = result.copyContent ? applySplice(result.copyContent, sent) : sent;
      const updated: LocalNote = {
        ...cleared, info: result.note, baseRevision: result.note!.revision, baseText: original, basedOnChangeId: undefined,
        text: original, deleted: false, dirty: false,
      };
      const copyBase: LocalNote = {
        id: result.copy!.id, baseRevision: 0, baseText: "", text: "", deleted: false, dirty: false, editedAt: note.editedAt,
      };
      const copy = settle(copyBase, result.copy!, copyText, typedSince, sent, pending.changeId, note.text);
      // The copy holds what this device wrote: an editor showing the note follows it, so that the
      // user keeps seeing their own text instead of it being replaced under their eyes.
      return { notes: [updated, copy], movedTo: copy.id };
    }
    case PushStatus.KEPT: {
      const current = result.content ? applySplice(result.content, sent) : sent;
      return {
        notes: [{
          ...cleared, info: result.note, baseRevision: result.note!.revision, baseText: current, basedOnChangeId: undefined,
          text: current, deleted: false, dirty: false,
        }],
      };
    }
    case PushStatus.REJECTED:
    default: {
      if (result.reason === RejectReason.BASE_MISMATCH && !note.forceFull) return { notes: [{ ...cleared, forceFull: true }] };
      return { notes: [{ ...cleared, error: { reason: result.reason ?? "UNKNOWN", message: result.message ?? "Refused by the server." } }] };
    }
  }
}

/**
 * The note once the server holds {@code stored} at {@code info}'s revision. Without edits since the
 * push, the note simply becomes that. With some, they stay: derived from what was sent, which the
 * server can find back by {@code changeId} when it didn't store it as is.
 */
function settle(note: LocalNote, info: NoteInfo, stored: string, typedSince: boolean, sent: string, changeId: string,
                localText: string = note.text): LocalNote {
  if (!typedSince) {
    return { ...note, info, baseRevision: info.revision, baseText: stored, basedOnChangeId: undefined, text: stored, dirty: false };
  }
  return {
    ...note, info, baseRevision: info.revision, baseText: sent, basedOnChangeId: stored === sent ? undefined : changeId,
    text: localText, dirty: true,
  };
}

/** The revision to ask the server a splice of, if the note's base can serve as one. */
export function haveRevision(note: LocalNote | undefined): number | undefined {
  return note && note.baseRevision > 0 && !note.basedOnChangeId ? note.baseRevision : undefined;
}

/** Whether {@code info} (from a page of changes) needs the note's content fetched. */
export function needsFetch(note: LocalNote | undefined, info: NoteInfo): boolean {
  if (!note) return true;
  if (note.dirty || note.pending) return false;
  return info.revision > note.baseRevision;
}

/**
 * {@code note} once the server's {@code content} arrived. The caller has checked the rebuilt text
 * against {@code content.note.contentHash}. A note with local changes keeps them: its next push merges.
 */
export function applyRemote(note: LocalNote | undefined, content: NoteContent, text: string | undefined, now: number): LocalNote {
  const info = content.note;
  if (note && (note.dirty || note.pending)) return { ...note, info };
  const kept = text ?? note?.text ?? "";
  return {
    id: info.id, info, baseRevision: info.revision, baseText: kept, text: kept, deleted: info.deleted, dirty: false,
    editedAt: note?.editedAt ?? now, ...metaStateOf(note),
  };
}

/** The text a NoteContent holds, given the note it was requested for; undefined for a deleted note. */
export function remoteText(note: LocalNote | undefined, content: NoteContent): string | undefined {
  if (!content.content) return undefined;
  return applySplice(content.content, haveRevision(note) !== undefined ? note!.baseText : "");
}

/**
 * {@code note} after the server's data was replaced (another storeId): its base means nothing
 * there anymore. Unsynced text is pushed as a new note, which can only end in a conflict copy if
 * the server has the note: never lost. Synced notes are fetched again. Tag and option changes not
 * acknowledged are sent again, in one change.
 */
export function forResync(note: LocalNote): LocalNote {
  const metaOps = note.pendingMeta ? mergeOps(note.pendingMeta.ops, note.metaOps ?? noOps()) : note.metaOps;
  const meta = { metaOps, pendingMeta: undefined };
  if (note.dirty || note.pending) {
    return { ...note, ...meta, baseRevision: 0, baseText: "", basedOnChangeId: undefined, pending: undefined, forceFull: false };
  }
  return { ...note, ...meta, baseRevision: 0, basedOnChangeId: undefined };
}

/** The user edited the note: it needs pushing, and any earlier refusal is moot. */
export function edited(note: LocalNote, text: string, now: number): LocalNote {
  return { ...note, text: text.toWellFormed(), dirty: true, editedAt: now, error: undefined };
}

/** A new, empty note: not dirty until its first edit, so that opening and leaving it leaves nothing behind. */
export function created(id: string, now: number): LocalNote {
  return { id, baseRevision: 0, baseText: "", text: "", deleted: false, dirty: false, editedAt: now };
}

/** The user deleted the note. One the server never had is simply forgotten. */
export function deletedLocally(note: LocalNote, now: number): LocalNote | undefined {
  if (note.baseRevision === 0 && !note.pending && !note.info) return undefined;
  return { ...note, deleted: true, dirty: true, editedAt: now, error: undefined };
}

/** For the note list: newest first, unsynced edits by their local time until the server's catches up. */
export function sortKey(note: LocalNote): number {
  return note.dirty || !note.info ? Math.max(note.editedAt, note.info?.modifiedAt ?? 0) : note.info.modifiedAt;
}
