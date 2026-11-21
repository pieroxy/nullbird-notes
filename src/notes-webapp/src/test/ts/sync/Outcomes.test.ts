import { NoteInfo, PushResult, PushStatus, RejectReason } from "../../../ts/auto/api-types";
import { LocalNote } from "../../../ts/sync/LocalNote";
import {
  applyPushResult, applyRemote, changeOf, created, deletedLocally, edited, forResync, haveRevision, isPushable, needsFetch,
  prepareChange, remoteText, sortKey, compareNotes,
} from "../../../ts/sync/Outcomes";
import { noOps, setOption } from "../../../ts/sync/Metadata";
import { computeSplice, fullSplice } from "../../../ts/sync/Splice";

const ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const BASE = "Title\n\nFirst paragraph.\n\nSecond paragraph.\n";

function info(revision: number, extra: Partial<NoteInfo> = {}): NoteInfo {
  return {
    id: ID, revision, seq: revision, createdAt: 1000, modifiedAt: 1000 + revision, deleted: false, title: "Title", size: 0,
    contentHash: "hash", tags: [], options: {}, ...extra,
  };
}

/** A note in sync with the server at {@code revision}. */
function synced(text: string, revision: number): LocalNote {
  return { id: ID, info: info(revision), baseRevision: revision, baseText: text, text, deleted: false, dirty: false, editedAt: 500 };
}

function result(status: PushStatus, extra: Partial<PushResult> = {}): PushResult {
  return { changeId: "c1", status, ...extra };
}

/** The note as sent: pending, with {@code textAfter} typed meanwhile when given. */
function inFlight(note: LocalNote, textAfter?: string): LocalNote {
  const sent = prepareChange(note, "c1").note;
  return textAfter === undefined ? sent : edited(sent, textAfter, 900);
}

describe("prepareChange", () => {
  test("sends a splice of the base when smaller than the text", () => {
    const note = edited(synced(BASE, 3), BASE.replace("First", "Better first"), 600);
    const { change, note: pending } = prepareChange(note, "c1");

    expect(change).toEqual({
      changeId: "c1", noteId: ID, baseRevision: 3, basedOnChangeId: undefined, deleted: false,
      splice: computeSplice(BASE, note.text),
    });
    expect(pending.pending).toEqual({ changeId: "c1", submitted: note.text, deleted: false });
  });

  test("sends the full text for a new note, or when asked to", () => {
    const fresh = edited(created(ID, 100), "Hello", 100);
    expect(prepareChange(fresh, "c1").change.content).toBe("Hello");
    expect(prepareChange(fresh, "c1").change.splice).toBeUndefined();

    const forced = { ...edited(synced(BASE, 3), BASE + "x", 600), forceFull: true };
    expect(prepareChange(forced, "c1").change.content).toBe(BASE + "x");
  });

  test("a delete carries no text, and remembers the base as what was sent", () => {
    const { change, note } = prepareChange(deletedLocally(synced(BASE, 3), 600)!, "c1");

    expect(change.deleted).toBe(true);
    expect(change.content).toBeUndefined();
    expect(change.splice).toBeUndefined();
    expect(note.pending).toEqual({ changeId: "c1", submitted: BASE, deleted: true });
  });

  test("a retry sends the very same change, whatever was typed since", () => {
    const { change, note } = prepareChange(edited(synced(BASE, 3), BASE + "first", 600), "c1");
    const typedMore = edited(note, BASE + "first and more", 700);

    expect(changeOf(typedMore)).toEqual(change);
  });

  test("carries basedOnChangeId", () => {
    const note = { ...edited(synced(BASE, 3), BASE + "x", 600), basedOnChangeId: "earlier" };
    expect(prepareChange(note, "c1").change.basedOnChangeId).toBe("earlier");
  });
});

describe("applyPushResult", () => {
  test("APPLIED: the note is in sync", () => {
    const note = inFlight(edited(synced(BASE, 3), BASE + "More.\n", 600));
    const { note: after } = applyPushResult(note, result(PushStatus.APPLIED, { note: info(4) }));

    expect(after).toEqual(expect.objectContaining({
      baseRevision: 4, baseText: BASE + "More.\n", text: BASE + "More.\n", dirty: false, pending: undefined,
    }));
  });

  test("APPLIED with text typed meanwhile: the typing stays, based on what was sent", () => {
    const note = inFlight(edited(synced(BASE, 3), BASE + "More.\n", 600), BASE + "More. And more.\n");
    const after = applyPushResult(note, result(PushStatus.APPLIED, { note: info(4) })).note!;

    expect(after).toEqual(expect.objectContaining({
      baseRevision: 4, baseText: BASE + "More.\n", text: BASE + "More. And more.\n", dirty: true, basedOnChangeId: undefined,
    }));
  });

  test("MERGED: the note takes the merged text", () => {
    const sent = BASE.replace("Second", "SECOND");
    const merged = sent.replace("First", "FIRST");
    const note = inFlight(edited(synced(BASE, 3), sent, 600));
    const after = applyPushResult(note, result(PushStatus.MERGED, { note: info(5), content: computeSplice(sent, merged) })).note!;

    expect(after).toEqual(expect.objectContaining({ baseRevision: 5, baseText: merged, text: merged, dirty: false }));
  });

  test("MERGED with text typed meanwhile: the typing stays, based on the change the server merged", () => {
    const sent = BASE.replace("Second", "SECOND");
    const merged = sent.replace("First", "FIRST");
    const typed = sent + "Typed during the push.\n";
    const note = inFlight(edited(synced(BASE, 3), sent, 600), typed);
    const after = applyPushResult(note, result(PushStatus.MERGED, { note: info(5), content: computeSplice(sent, merged) })).note!;

    expect(after).toEqual(expect.objectContaining({ baseRevision: 5, baseText: sent, basedOnChangeId: "c1", text: typed, dirty: true }));
    expect(prepareChange(after, "c2").change).toEqual(expect.objectContaining({ baseRevision: 5, basedOnChangeId: "c1" }));
  });

  test("BASE_GONE: resent once with the base text, then reported", () => {
    const note = inFlight(edited(synced(BASE, 3), BASE + "x", 600));
    const { note: retry } = applyPushResult(note, result(PushStatus.REJECTED, { reason: RejectReason.BASE_GONE, message: "gone" }));
    expect(retry!.sendBase).toBe(true);
    expect(isPushable(retry!)).toBe(true);
    const change = prepareChange(retry!, "c2").change;
    expect(change).toEqual(expect.objectContaining({ content: BASE + "x", baseContent: BASE }));
    expect(change.splice).toBeUndefined();

    const { note: failed } = applyPushResult(prepareChange(retry!, "c2").note,
      { ...result(PushStatus.REJECTED, { reason: RejectReason.BASE_GONE, message: "still gone" }), changeId: "c2" });
    expect(failed!.error).toEqual({ reason: RejectReason.BASE_GONE, message: "still gone" });
    expect(failed!.sendBase).toBe(false);
  });

  test("KEPT: a refused delete brings the note back with the server's text", () => {
    const note = inFlight(deletedLocally(synced(BASE, 3), 600)!);
    const after = applyPushResult(note, result(PushStatus.KEPT, { note: info(4), content: computeSplice(BASE, BASE + "Edited.\n") })).note!;

    expect(after).toEqual(expect.objectContaining({ deleted: false, dirty: false, baseRevision: 4, text: BASE + "Edited.\n" }));
  });

  test("a delete APPLIED leaves a clean tombstone; one of a note the server never had removes it", () => {
    const note = inFlight(deletedLocally(synced(BASE, 3), 600)!);
    const after = applyPushResult(note, result(PushStatus.APPLIED, { note: info(4, { deleted: true }) })).note!;
    expect(after).toEqual(expect.objectContaining({ deleted: true, dirty: false, baseRevision: 4 }));

    expect(applyPushResult(note, result(PushStatus.APPLIED)).removed).toBe(true);
  });

  test("BASE_MISMATCH: resent once in full, then reported", () => {
    const note = inFlight(edited(synced(BASE, 3), BASE + "x", 600));
    const retry = applyPushResult(note, result(PushStatus.REJECTED, { reason: RejectReason.BASE_MISMATCH, message: "no" })).note!;
    expect(retry.forceFull).toBe(true);
    expect(retry.error).toBeUndefined();
    expect(isPushable(retry)).toBe(true);
    expect(prepareChange(retry, "c2").change.content).toBe(BASE + "x");

    const failed = applyPushResult(prepareChange(retry, "c2").note,
      { ...result(PushStatus.REJECTED, { reason: RejectReason.BASE_MISMATCH, message: "still no" }), changeId: "c2" }).note!;
    expect(failed.error).toEqual({ reason: RejectReason.BASE_MISMATCH, message: "still no" });
  });

  test("TOO_LARGE: reported and not retried until edited", () => {
    const note = inFlight(edited(synced(BASE, 3), "x".repeat(100), 600));
    const after = applyPushResult(note, result(PushStatus.REJECTED, { reason: RejectReason.TOO_LARGE, message: "too big" })).note!;

    expect(after.error).toEqual({ reason: RejectReason.TOO_LARGE, message: "too big" });
    expect(after.dirty).toBe(true);
    expect(isPushable(after)).toBe(false);
    expect(isPushable(edited(after, "x", 700))).toBe(true);
  });
});

describe("pulling", () => {
  test("fetches notes it doesn't have, or has an older revision of, unless they have local changes", () => {
    expect(needsFetch(undefined, info(1))).toBe(true);
    expect(needsFetch(synced(BASE, 1), info(2))).toBe(true);
    expect(needsFetch(synced(BASE, 2), info(2))).toBe(false);
    expect(needsFetch(edited(synced(BASE, 1), "x", 600), info(2))).toBe(false);
    expect(needsFetch(inFlight(synced(BASE, 1)), info(2))).toBe(false);
  });

  test("asks for a splice of its base revision only when the base is a server revision's text", () => {
    expect(haveRevision(undefined)).toBeUndefined();
    expect(haveRevision(synced(BASE, 3))).toBe(3);
    expect(haveRevision(edited(created(ID, 1), "x", 1))).toBeUndefined();
    expect(haveRevision({ ...synced(BASE, 3), basedOnChangeId: "c1" })).toBeUndefined();
  });

  test("rebuilds the text from a splice of its base, or from the full text", () => {
    const note = synced(BASE, 3);
    expect(remoteText(note, { note: info(4), content: computeSplice(BASE, BASE + "x") })).toBe(BASE + "x");
    expect(remoteText(undefined, { note: info(4), content: fullSplice("whole") })).toBe("whole");
    expect(remoteText(note, { note: info(4, { deleted: true }) })).toBeUndefined();
  });

  test("takes the server's text, unless it has local changes, which it keeps", () => {
    const clean = applyRemote(synced(BASE, 3), { note: info(4) }, BASE + "x", 2000);
    expect(clean).toEqual(expect.objectContaining({ baseRevision: 4, baseText: BASE + "x", text: BASE + "x", dirty: false }));

    const local = edited(synced(BASE, 3), "mine", 600);
    expect(applyRemote(local, { note: info(4) }, BASE + "x", 2000)).toEqual({ ...local, info: info(4) });

    const fresh = applyRemote(undefined, { note: info(1) }, "new", 2000);
    expect(fresh).toEqual(expect.objectContaining({ id: ID, text: "new", editedAt: 2000 }));
  });

  test("a deleted note keeps its last text", () => {
    const after = applyRemote(synced(BASE, 3), { note: info(4, { deleted: true }) }, undefined, 2000);
    expect(after).toEqual(expect.objectContaining({ deleted: true, text: BASE, dirty: false }));
  });

  test("after a resync, unsynced text is pushed as a new note, synced notes are fetched again", () => {
    const local = forResync({ ...edited(synced(BASE, 3), "mine", 600), basedOnChangeId: "c0" });
    expect(local).toEqual(expect.objectContaining({ baseRevision: 0, baseText: "", basedOnChangeId: undefined, text: "mine", dirty: true }));
    expect(prepareChange(local, "c1").change).toEqual(expect.objectContaining({ baseRevision: 0, content: "mine" }));

    const clean = forResync(synced(BASE, 3));
    expect(needsFetch(clean, info(1))).toBe(true);
    expect(haveRevision(clean)).toBeUndefined();
  });
});

describe("local edits", () => {
  test("an edit makes the note dirty, clears a refusal, and repairs a lone surrogate", () => {
    const failed = { ...synced(BASE, 3), error: { reason: "TOO_LARGE", message: "x" } };
    const after = edited(failed, "half an emoji: " + String.fromCharCode(0xd83d), 700);

    expect(after).toEqual(expect.objectContaining({ dirty: true, editedAt: 700, error: undefined }));
    expect(after.text).toBe("half an emoji: �");
  });

  test("a new note stays out of sync until edited", () => {
    expect(isPushable(created(ID, 1))).toBe(false);
    expect(isPushable(edited(created(ID, 1), "x", 2))).toBe(true);
  });

  test("deleting a note the server never had forgets it", () => {
    expect(deletedLocally(edited(created(ID, 1), "x", 2), 3)).toBeUndefined();
    expect(deletedLocally(synced(BASE, 3), 4)).toEqual(expect.objectContaining({ deleted: true, dirty: true }));
  });

  test("unsynced edits sort by their local time", () => {
    expect(sortKey(synced(BASE, 3))).toBe(1003);
    expect(sortKey(edited(synced(BASE, 3), "x", 5000))).toBe(5000);
  });
});

describe("compareNotes", () => {
  const at = (id: string, modifiedAt: number, pinned = false): LocalNote => ({
    ...synced(id, 1), id, info: info(1, { id, modifiedAt, options: pinned ? { pinned: "true" } : {} }),
  });
  const order = (notes: LocalNote[]) => [...notes].sort(compareNotes).map((note) => note.id);

  test("pinned first, then the others, each newest first", () => {
    expect(order([at("old", 1), at("pinned-old", 2, true), at("new", 5), at("pinned-new", 3, true)]))
      .toEqual(["pinned-new", "pinned-old", "new", "old"]);
  });

  test("a pinned note edited goes to the top of the pinned ones", () => {
    const edited = { ...at("pinned-old", 2, true), dirty: true, editedAt: 99 };
    expect(order([at("pinned-new", 3, true), edited, at("new", 50)])).toEqual(["pinned-old", "pinned-new", "new"]);
  });

  test("a pin or an unpin not synced yet counts", () => {
    const pinning = { ...at("old", 1), metaOps: setOption(noOps(), "pinned", "true") };
    const unpinning = { ...at("was-pinned", 2, true), pendingMeta: { changeId: "m1", ops: setOption(noOps(), "pinned", undefined) } };
    expect(order([at("new", 5), unpinning, pinning])).toEqual(["old", "new", "was-pinned"]);
  });
});
