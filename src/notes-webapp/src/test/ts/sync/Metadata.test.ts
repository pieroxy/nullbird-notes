import { NoteInfo, PushStatus, RejectReason } from "../../../ts/auto/api-types";
import { LocalNote } from "../../../ts/sync/LocalNote";
import {
  addTag, applyMetaResult, hasMetaToPush, isOn, isValidTag, mergeOps, metaChangeOf, noOps, optionsOf, prepareMeta, removeTag,
  setOption, tagsOf,
} from "../../../ts/sync/Metadata";
import { applyRemote, forResync } from "../../../ts/sync/Outcomes";

const ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

function info(seq: number, tags: string[] = [], options: { [key: string]: string } = {}): NoteInfo {
  return { id: ID, revision: 1, seq, createdAt: 1000, modifiedAt: 1001, deleted: false, title: "T", size: 1, contentHash: "h", tags, options };
}

function synced(extra: Partial<LocalNote> = {}): LocalNote {
  return { id: ID, info: info(1, ["a", "b"], { monospace: "true" }), baseRevision: 1, baseText: "T", text: "T", deleted: false, dirty: false, editedAt: 0, ...extra };
}

describe("Metadata", () => {
  test("what's shown is the server's values, then the operations in flight, then those not sent", () => {
    const note = synced({
      pendingMeta: { changeId: "m1", ops: addTag(removeTag(noOps(), "a"), "c") },
      metaOps: setOption(addTag(noOps(), "d"), "monospace", undefined),
    });

    expect(tagsOf(note)).toEqual(["b", "c", "d"]);
    expect(optionsOf(note)).toEqual({});
    expect(isOn(synced(), "monospace")).toBe(true);
  });

  test("adding and removing the same tag, or setting and unsetting an option, keep the last one", () => {
    expect(removeTag(addTag(noOps(), "x"), "x")).toEqual({ ...noOps(), removeTags: ["x"] });
    expect(addTag(removeTag(noOps(), "x"), "x")).toEqual({ ...noOps(), addTags: ["x"] });
    expect(setOption(setOption(noOps(), "k", undefined), "k", "v")).toEqual({ ...noOps(), setOptions: { k: "v" } });
    expect(setOption(setOption(noOps(), "k", "v"), "k", undefined)).toEqual({ ...noOps(), unsetOptions: ["k"] });
  });

  test("merged operations apply the later ones last", () => {
    const merged = mergeOps(addTag(setOption(noOps(), "k", "v"), "x"), removeTag(setOption(noOps(), "k", undefined), "x"));

    expect(merged).toEqual({ addTags: [], removeTags: ["x"], setOptions: {}, unsetOptions: ["k"] });
  });

  test("validates tags as the server does", () => {
    expect(isValidTag("work")).toBe(true);
    expect(isValidTag("Émoji🙂")).toBe(true);
    expect(isValidTag("🙂".repeat(64))).toBe(true);
    expect(isValidTag("🙂".repeat(65))).toBe(false);
    expect(isValidTag("")).toBe(false);
    expect(isValidTag("two words")).toBe(false);
    expect(isValidTag("tab\there")).toBe(false);
    expect(isValidTag("nbsp here")).toBe(false);
  });

  test("operations wait for the server to have the note, and for the previous push to be answered", () => {
    const ops = addTag(noOps(), "x");

    expect(hasMetaToPush(synced({ metaOps: ops }))).toBe(true);
    expect(hasMetaToPush(synced())).toBe(false);
    expect(hasMetaToPush(synced({ metaOps: ops, baseRevision: 0, info: undefined }))).toBe(false);
    expect(hasMetaToPush(synced({ metaOps: ops, pendingMeta: { changeId: "m1", ops } }))).toBe(false);
  });

  test("a prepared change carries the operations, and moves them in flight", () => {
    const note = prepareMeta(synced({ metaOps: addTag(noOps(), "x") }), "m1");

    expect(note.metaOps).toBeUndefined();
    expect(metaChangeOf(note)).toEqual({ changeId: "m1", noteId: ID, addTags: ["x"], removeTags: [], setOptions: {}, unsetOptions: [] });
  });

  test("an applied change takes the server's values, unless they're older than what's known", () => {
    const note = prepareMeta(synced({ metaOps: addTag(noOps(), "x") }), "m1");
    const applied = applyMetaResult(note, { changeId: "m1", status: PushStatus.APPLIED, note: info(5, ["a", "b", "x"]) });

    expect(applied.pendingMeta).toBeUndefined();
    expect(tagsOf(applied)).toEqual(["a", "b", "x"]);
    const newer = { ...note, info: info(9, ["z"]) };
    expect(applyMetaResult(newer, { changeId: "m1", status: PushStatus.APPLIED, note: info(5) }).info?.seq).toBe(9);
  });

  test("a refused change drops its operations and says why", () => {
    const note = prepareMeta(synced({ metaOps: addTag(noOps(), "x") }), "m1");
    const refused = applyMetaResult(note, { changeId: "m1", status: PushStatus.REJECTED, reason: RejectReason.INVALID, message: "No." });

    expect(tagsOf(refused)).toEqual(["a", "b"]);
    expect(refused.metaError).toEqual({ reason: "INVALID", message: "No." });
  });

  test("a note rebuilt from the server keeps its metadata changes", () => {
    const ops = addTag(noOps(), "x");
    const rebuilt = applyRemote(synced({ metaOps: ops }), { note: { ...info(3), revision: 2 } }, "new text", 0);

    expect(rebuilt.metaOps).toEqual(ops);
    expect(tagsOf(rebuilt)).toEqual(["x"]);
  });

  test("after the server's data was replaced, operations in flight are sent again with the rest", () => {
    const note = synced({ pendingMeta: { changeId: "m1", ops: addTag(noOps(), "x") }, metaOps: setOption(noOps(), "nowrap", "true") });
    const resynced = forResync(note);

    expect(resynced.pendingMeta).toBeUndefined();
    expect(resynced.metaOps).toEqual({ addTags: ["x"], removeTags: [], setOptions: { nowrap: "true" }, unsetOptions: [] });
  });
});
