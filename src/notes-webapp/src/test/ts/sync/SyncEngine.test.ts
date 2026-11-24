import "fake-indexeddb/auto";
import { IDBFactory } from "fake-indexeddb";
import { PushStatus, RejectReason } from "../../../ts/auto/api-types";
import { LocalStore } from "../../../ts/sync/LocalStore";
import { tagsOf } from "../../../ts/sync/Metadata";
import { sha256Bytes } from "../../../ts/sync/Sha256";
import { computeSplice } from "../../../ts/sync/Splice";
import { SyncEngine, TIMINGS } from "../../../ts/sync/SyncEngine";
import { FakeServer } from "./FakeServer";
import { advance, until, useInstantDigest } from "./FakeTime";

const A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const hash = (text: string) => sha256Bytes(new TextEncoder().encode(text));

describe("SyncEngine", () => {
  let server: FakeServer;
  let store: LocalStore;
  let engine: SyncEngine;

  async function startEngine() {
    engine = new SyncEngine(store, server);
    await engine.start();
    await until(() => engine.status().state === "synced");
  }

  beforeEach(async () => {
    // fake-indexeddb schedules its work with setImmediate: real ones keep it running.
    jest.useFakeTimers({ doNotFake: ["setImmediate", "queueMicrotask", "nextTick"] });
    useInstantDigest();
    globalThis.indexedDB = new IDBFactory();
    server = new FakeServer();
    store = await LocalStore.open("alice");
  });

  afterEach(async () => {
    await engine?.stop();
    store.close();
    jest.useRealTimers();
    jest.restoreAllMocks();
  });

  test("pulls the server's notes on start", async () => {
    server.write(A, "From another device\n");
    await startEngine();

    expect(engine.list().map((note) => note.text)).toEqual(["From another device\n"]);
    expect(await store.getMeta()).toEqual(expect.objectContaining({ storeId: "store-1", cursor: 1 }));
    expect((await store.getAllNotes())[0]).toEqual(expect.objectContaining({ id: A, baseRevision: 1, dirty: false }));
  });

  test("pushes an edit once typing pauses, then as splices", async () => {
    await startEngine();
    const id = engine.create();
    engine.edit(id, "Hello");
    await jest.advanceTimersByTimeAsync(TIMINGS.pushIdleDelay - 100);
    expect(server.pushes).toHaveLength(0);

    await until(() => engine.status().state === "synced" && server.text(id) === "Hello");
    expect(engine.get(id)).toEqual(expect.objectContaining({ dirty: false, baseRevision: 1 }));

    engine.edit(id, "Hello, world");
    await until(() => server.text(id) === "Hello, world");
    expect(server.pushes[1][0]).toEqual(expect.objectContaining({ baseRevision: 1, splice: computeSplice("Hello", "Hello, world") }));
  });

  test("pushes at least every few seconds while typing goes on", async () => {
    await startEngine();
    const id = engine.create();
    for (let i = 1; i <= 14; i++) {
      engine.edit(id, "x".repeat(i));
      await advance(500);
    }
    expect(server.pushes.length).toBeGreaterThanOrEqual(1);
  });

  test("a change made elsewhere arrives through the long poll, as a splice of what's held", async () => {
    server.write(A, "Line one\n");
    await startEngine();
    const before = server.pushes.length;

    server.write(A, "Line one\nLine two\n");
    await until(() => engine.get(A)?.text === "Line one\nLine two\n");
    expect(engine.get(A)).toEqual(expect.objectContaining({ baseRevision: 2, dirty: false }));
    expect(server.pushes.length).toBe(before);
  });

  test("goes offline when the server can't be reached, keeps edits, and resends the same change once back", async () => {
    await startEngine();
    const id = engine.create();
    server.failNext = 3;
    engine.edit(id, "Written offline");

    await until(() => engine.status().state === "offline");
    expect(engine.status()).toEqual(expect.objectContaining({ pending: 1, offlineSince: expect.any(Number), nextRetryAt: expect.any(Number) }));
    expect(engine.get(id)?.dirty).toBe(true);

    await until(() => engine.status().state === "synced");
    expect(server.text(id)).toBe("Written offline");
    const changeIds = server.pushes.flat().filter((c) => c.noteId === id).map((c) => c.changeId);
    expect(new Set(changeIds).size).toBe(1);
  });

  test("a push whose answer is lost is resent with the same changeId, and applied once", async () => {
    await startEngine();
    // The first answer gets lost: the server applied it, but the engine sees a network error.
    const push = server.push.bind(server);
    let lose = true;
    server.push = async (deviceId, batch) => {
      const results = await push(deviceId, batch);
      if (lose) {
        lose = false;
        throw new (await import("../../../ts/utils/api/Api")).ApiError(0, "Cannot reach the server.");
      }
      return results;
    };
    const id = engine.create();
    engine.edit(id, "Once");

    await until(() => engine.status().state === "synced" && !engine.get(id)?.dirty);
    expect(lose).toBe(false);
    expect(server.notes.get(id)?.info.revision).toBe(1);
  });

  test("retryNow cuts the backoff short", async () => {
    await startEngine();
    server.failNext = 1000;
    server.write(A, "wake the poll");
    await until(() => (engine.status().nextRetryAt ?? 0) - Date.now() > 10_000);
    server.failNext = 0;

    engine.retryNow();
    await until(() => engine.status().state === "synced", 1_000);
    expect(engine.get(A)?.text).toBe("wake the poll");
  });

  test("retryIfWaiting cuts the backoff short, and does nothing while connected", async () => {
    await startEngine();
    const pushesBefore = server.pushes.length;
    engine.retryIfWaiting();
    await advance(100);
    expect(engine.status().state).toBe("synced");
    expect(server.pushes).toHaveLength(pushesBefore);

    server.failNext = 1000;
    server.write(A, "back online");
    await until(() => (engine.status().nextRetryAt ?? 0) - Date.now() > 10_000);
    server.failNext = 0;

    engine.retryIfWaiting();
    await until(() => engine.status().state === "synced", 1_000);
    expect(engine.get(A)?.text).toBe("back online");
  });

  test("a dead connection is noticed: no answer within the timeout means offline", async () => {
    await startEngine();
    server.silent = true;

    // The poll already waiting answers within 25 s; the next one never does, and times out.
    await advance(25_000 + TIMINGS.longPollTimeout - 1_000);
    expect(engine.status().state).toBe("synced");
    await until(() => engine.status().state === "offline", 2_000);
  });

  test("an idle long poll that answers empty keeps the engine synced", async () => {
    await startEngine();
    await advance(60_000);
    expect(engine.status().state).toBe("synced");
  });

  test("a 401 stops syncing and keeps everything local", async () => {
    await startEngine();
    const id = engine.create();
    server.unauthorized = true;
    engine.edit(id, "Not lost");

    await until(() => engine.status().state === "signedOut");
    await jest.advanceTimersByTimeAsync(TIMINGS.saveDelay);
    expect((await store.getAllNotes()).find((n) => n.id === id)?.text).toBe("Not lost");
  });

  test("a server running another build stops syncing and keeps everything local", async () => {
    await startEngine();
    const id = engine.create();
    server.outdated = true;
    engine.edit(id, "Kept for the reloaded page");

    await until(() => engine.status().state === "outdated");
    const pushes = server.pushes.length;
    await advance(TIMINGS.retryMax * 2);
    expect(engine.status()).toEqual(expect.objectContaining({ state: "outdated", pending: 1 }));
    expect(server.pushes.length).toBe(pushes);
    expect((await store.getAllNotes()).find((n) => n.id === id)?.text).toBe("Kept for the reloaded page");
  });

  test("when the server's data was replaced, starts over and pushes unsynced text as new notes", async () => {
    server.write(A, "Synced");
    await startEngine();
    await engine.stop();
    const local = new SyncEngine(store, server);
    await local.start();
    engine = local;
    engine.edit(A, "Synced, then edited offline");
    server.failNext = 0;

    // The server was restored from an older backup: another store, without that note.
    server.storeId = "store-2";
    server.notes.clear();
    server.seq = 0;
    server.write(A, "Restored");
    const merged = "Restored Synced, then edited offline";
    server.onPush = (change) => {
      if (change.baseRevision !== 0) throw new Error("Unsynced text must be pushed as new after a resync");
      return { changeId: change.changeId, status: PushStatus.MERGED, note: server.write(A, merged), content: computeSplice(change.content!, merged) };
    };

    await until(() => engine.get(A)?.text === merged && engine.status().state === "synced");
    expect((await store.getMeta())?.storeId).toBe("store-2");
  });

  test("when the server no longer has the base revision, the change is sent again with its base text", async () => {
    server.write(A, "Base");
    await startEngine();
    server.onPush = (change) => {
      if (change.baseContent === undefined) return { changeId: change.changeId, status: PushStatus.REJECTED, reason: RejectReason.BASE_GONE, message: "Gone." };
      expect(change.baseContent).toBe("Base");
      expect(change.content).toBe("Base, edited");
      return { changeId: change.changeId, status: PushStatus.MERGED, note: server.write(A, "Merged"), content: computeSplice("Base, edited", "Merged") };
    };

    engine.edit(A, "Base, edited");

    await until(() => engine.get(A)?.text === "Merged" && engine.status().state === "synced");
    expect(server.pushes).toHaveLength(2);
    expect(engine.get(A)?.sendBase).toBeFalsy();
    expect(engine.get(A)?.error).toBeUndefined();
  });

  test("a push in flight when the page closes is resent as the same change, and applied once", async () => {
    await startEngine();
    const id = engine.create();
    let open!: () => void;
    server.gate = new Promise((resolve) => (open = resolve));
    engine.edit(id, "Before the restart");
    await until(() => server.pushes.length === 1);
    const changeId = engine.get(id)!.pending!.changeId;
    await engine.stop();

    server.gate = undefined;
    engine = new SyncEngine(store, server);
    await engine.start();
    await until(() => server.text(id) === "Before the restart" && engine.status().state === "synced");
    open();
    await jest.advanceTimersByTimeAsync(100);

    expect(server.pushes.flat().filter((c) => c.noteId === id).map((c) => c.changeId)).toEqual([changeId, changeId]);
    expect(server.notes.get(id)?.info.revision).toBe(1);
    expect(engine.get(id)?.dirty).toBe(false);
    expect(engine.get(id)?.pending).toBeUndefined();
  });

  test("deletes reach the server and leave the list", async () => {
    server.write(A, "Doomed");
    await startEngine();
    engine.delete(A);

    expect(engine.list()).toEqual([]);
    await until(() => server.notes.get(A)?.info.deleted === true && engine.status().state === "synced");
    expect(engine.get(A)).toEqual(expect.objectContaining({ deleted: true, dirty: false }));
  });

  test("a note larger than the server's limit is reported without being sent", async () => {
    await startEngine();
    const id = engine.create();
    engine.edit(id, "x".repeat(1001));

    await until(() => engine.get(id)?.error !== undefined);
    expect(engine.get(id)?.error?.reason).toBe("TOO_LARGE");
    expect(server.pushes).toHaveLength(0);
    expect(engine.status().noteErrors).toBe(1);
  });

  test("a note created and left empty leaves nothing behind", async () => {
    await startEngine();
    const id = engine.create();
    expect(engine.list().map((n) => n.id)).toEqual([id]);

    engine.discardIfEmpty(id);
    expect(engine.list()).toEqual([]);
    await jest.advanceTimersByTimeAsync(TIMINGS.pushMaxDelay);
    expect(server.pushes).toHaveLength(0);
  });

  test("refuses a fetched note that doesn't match its hash", async () => {
    server.write(A, "Genuine");
    server.notes.get(A)!.info.contentHash = hash("Tampered");
    engine = new SyncEngine(store, server);
    await engine.start();

    await until(() => engine.status().state === "error");
    expect(engine.status().message).toContain("doesn't match its hash");
    expect(engine.get(A)).toBeUndefined();
  });

  test("tags and options reach the server without pushing the text", async () => {
    server.write(A, "Note");
    await startEngine();
    expect(engine.addTag(A, "work")).toBe(true);
    engine.setOption(A, "monospace", true);

    await until(() => engine.status().state === "synced" && server.notes.get(A)!.info.tags.length === 1);
    expect(server.notes.get(A)!.info).toEqual(expect.objectContaining({ tags: ["work"], options: { monospace: "true" }, revision: 1 }));
    expect(server.pushes).toHaveLength(0);
    expect(engine.get(A)).toEqual(expect.objectContaining({ metaOps: undefined, pendingMeta: undefined }));
  });

  test("an invalid tag is refused locally", async () => {
    server.write(A, "Note");
    await startEngine();

    expect(engine.addTag(A, "two words")).toBe(false);
    expect(engine.get(A)?.metaOps).toBeUndefined();
  });

  test("tagging a note never edited creates it, then tags it", async () => {
    await startEngine();
    const id = engine.create();
    engine.addTag(id, "idea");

    await until(() => engine.status().state === "synced" && server.notes.get(id)?.info.tags.length === 1);
    expect(server.text(id)).toBe("");
    expect(server.notes.get(id)!.info.tags).toEqual(["idea"]);
  });

  test("a note created with tags shows them, stays a draft until edited, then gets them on the server", async () => {
    await startEngine();
    const draft = engine.create(["work", "urgent"]);
    expect(tagsOf(engine.get(draft)!)).toEqual(["work", "urgent"]);
    expect(engine.status()).toEqual(expect.objectContaining({ state: "synced", pending: 0 }));
    engine.discardIfEmpty(draft);
    expect(engine.get(draft)).toBeUndefined();

    const id = engine.create(["work", "urgent"]);
    engine.edit(id, "Kept");
    await until(() => engine.status().state === "synced" && server.notes.get(id)?.info.tags.length === 2);
    expect(server.text(id)).toBe("Kept");
    expect(server.notes.get(id)!.info.tags).toEqual(["work", "urgent"]);
  });

  test("a tag change made elsewhere arrives without fetching the text again", async () => {
    server.write(A, "Note");
    await startEngine();
    const fetches = server.fetches.length;

    server.writeMeta(A, ["shared"], { nowrap: "true" });
    await until(() => engine.get(A)?.info?.tags[0] === "shared");
    expect(engine.get(A)?.info?.options).toEqual({ nowrap: "true" });
    expect(server.fetches.length).toBe(fetches);
  });

  test("a tag added offline is resent as the same change, and applied once", async () => {
    server.write(A, "Note");
    await startEngine();
    server.failNext = 2;
    engine.addTag(A, "later");

    await until(() => engine.status().state === "offline");
    expect(engine.status().pending).toBe(1);
    await until(() => engine.status().state === "synced");
    expect(server.notes.get(A)!.info.tags).toEqual(["later"]);
    expect(new Set(server.metaPushes.flat().map((c) => c.changeId)).size).toBe(1);
  });

  test("a refused tag change is reported, and the tags go back to the server's", async () => {
    server.write(A, "Note");
    await startEngine();
    server.notes.delete(A);
    engine.addTag(A, "orphan");

    await until(() => engine.get(A)?.metaError !== undefined);
    expect(engine.get(A)?.metaError?.reason).toBe("UNKNOWN_NOTE");
    expect(engine.status().noteErrors).toBe(1);
    expect(engine.get(A)?.metaOps).toBeUndefined();
  });

  test("an edit made while other notes are being fetched isn't lost", async () => {
    server.write(A, "Mine");
    await startEngine();
    let open!: () => void;
    server.fetchGate = new Promise((resolve) => (open = resolve));
    // Another device's note to fetch, and a change to A that needs no fetch.
    server.write(B, "Theirs");
    server.writeMeta(A, ["remote"], {});
    await until(() => server.fetches.length > 0 && server.fetches[server.fetches.length - 1].some((r) => r.id === B));
    engine.edit(A, "Mine, edited during the fetch");
    open();
    server.fetchGate = undefined;

    await until(() => engine.get(B)?.text === "Theirs");
    expect(engine.get(A)?.text).toBe("Mine, edited during the fetch");
    await until(() => server.text(A) === "Mine, edited during the fetch");
  });

  describe("renaming and removing tags", () => {
    async function withTags() {
      server.write(A, "First");
      server.write(B, "Second");
      server.writeMeta(A, ["work", "home"], {});
      server.writeMeta(B, ["work"], {});
      await startEngine();
    }

    test("renamed on every note at once; the filter's listener told", async () => {
      await withTags();
      const told: unknown[] = [];
      engine.onTagEvents((events) => told.push(...events));
      expect(engine.tagNoteCount("work")).toBe(2);
      expect(await engine.renameTag("work", "job")).toEqual([A, B]);
      // At once, before the changes come.
      expect(tagsOf(engine.get(A)!)).toEqual(["home", "job"]);
      expect(told).toEqual([{ from: "work", to: "job" }]);
      await until(() => engine.status().state === "synced");
      await advance(100);
      expect(tagsOf(engine.get(B)!)).toEqual(["job"]);
    });

    test("removed from every note", async () => {
      await withTags();
      expect(await engine.deleteTag("work")).toEqual([A, B]);
      expect(tagsOf(engine.get(A)!)).toEqual(["home"]);
      expect(tagsOf(engine.get(B)!)).toEqual([]);
    });

    test("a change not sent yet follows a rename made on another device", async () => {
      await withTags();
      server.failNext = 1000;
      engine.addTag(B, "home");
      engine.removeTag(A, "work");
      await until(() => engine.status().state === "offline");
      // Another device renames the tags meanwhile: this one still waits to retry.
      server.failNext = 0;
      await server.renameTag("other", "work", "job");
      await server.renameTag("other", "home", "house");
      engine.retryNow();
      await until(() => engine.status().state === "synced" && !engine.get(B)?.metaOps && !engine.get(B)?.pendingMeta, 5_000);
      await advance(100);
      expect(server.notes.get(B)!.info.tags).toEqual(["job", "house"]);
      expect(server.notes.get(A)!.info.tags).toEqual(["house"]);
      // Sent with the cursor: a real server could rewrite them too, had they gone first.
      expect(server.pushCursors.every((c) => typeof c === "number")).toBe(true);
    });

    test("offline, renaming fails and changes nothing", async () => {
      await withTags();
      server.failNext = 1000;
      await expect(engine.renameTag("work", "job")).rejects.toThrow();
      expect(tagsOf(engine.get(A)!)).toEqual(["work", "home"]);
      await expect(engine.renameTag("work", "has space")).rejects.toThrow("without spaces");
    });
  });

  describe("tag options", () => {
    test("set here: shown at once, sent, kept once confirmed; another device's arrive with the changes", async () => {
      server.write(A, "First");
      server.writeMeta(A, ["work"], {});
      await startEngine();
      engine.setTagOptions("work", { dot: "blue" });
      engine.setTagOptions("work", { dot: "red", notes: "red" });
      expect(engine.allTagOptions()).toEqual({ work: { dot: "red", notes: "red" } });
      await until(() => engine.status().state === "synced" && server.tagOptions !== undefined);
      expect(server.tagOptions?.tags).toEqual({ work: { dot: "red", notes: "red" } });
      expect((await store.getMeta())?.tagOptionsChanges).toEqual([]);

      server.writeTagOptions({ work: { text: "green" } });
      await until(() => engine.allTagOptions().work?.text === "green");
      engine.setTagOptions("work", undefined);
      expect(engine.allTagOptions()).toEqual({});
      await until(() => engine.status().state === "synced" && JSON.stringify(server.tagOptions?.tags) === "{}");
    });

    test("options for the notes alone count: kept, not taken for none", async () => {
      await startEngine();
      engine.setTagOptions("journal", { sort: "title" });
      engine.setTagOptions("code", { monospace: false });
      engine.setTagOptions("other", { hidden: false, rendered: false });
      expect(engine.allTagOptions()).toEqual({ journal: { sort: "title" }, code: { monospace: false } });
    });

    test("they follow a rename; set offline before another device's rename, they follow it too", async () => {
      server.write(A, "First");
      server.writeMeta(A, ["work", "home"], {});
      await startEngine();
      engine.setTagOptions("work", { dot: "teal" });
      await until(() => engine.status().state === "synced" && server.tagOptions !== undefined);
      await engine.renameTag("work", "job");
      expect(engine.allTagOptions()).toEqual({ job: { dot: "teal" } });

      server.failNext = 1000;
      engine.setTagOptions("home", { background: "yellow" });
      await until(() => engine.status().state === "offline");
      server.failNext = 0;
      await server.renameTag("other", "home", "house");
      engine.retryNow();
      await until(() => engine.status().state === "synced" && server.tagOptions?.tags.house !== undefined, 5_000);
      expect(server.tagOptions?.tags.house).toEqual({ background: "yellow" });
      expect(server.tagOptions?.tags.home).toBeUndefined();
    });
  });

  describe("tag order", () => {
    async function withTags() {
      server.write(A, "First");
      server.write(B, "Second");
      server.writeMeta(A, ["work", "home"], {});
      server.writeMeta(B, ["art"], {});
      await startEngine();
    }

    test("alphabetical until the first move, which starts from it", async () => {
      await withTags();
      expect(engine.tagOrder(["work", "home", "art"])).toBeUndefined();

      engine.moveTag({ tag: "work", after: null });
      expect(engine.tagOrder(["work", "home", "art"])).toEqual(["work", "art", "home"]);
      await until(() => engine.status().state === "synced");
      expect(server.tagOrderPushes).toEqual([{ baseRevision: 0, tags: ["work", "art", "home"] }]);
      expect(server.tagOrder?.tags).toEqual(["work", "art", "home"]);
      expect((await store.getMeta())?.tagMoves).toEqual([]);
    });

    test("another device's order arrives with the changes", async () => {
      await withTags();
      server.writeTagOrder(["home", "art", "work"]);
      await until(() => engine.tagOrder(["work", "home", "art"])?.[0] === "home");
      expect(engine.tagOrder(["work", "home", "art"])).toEqual(["home", "art", "work"]);
    });

    test("moves made on two devices both survive: a stale one is replayed on the newer order", async () => {
      await withTags();
      engine.moveTag({ tag: "home", after: null });
      await until(() => server.tagOrder?.revision === 1);
      await until(() => engine.status().state === "synced");
      // Offline here, the user moves art to the top; meanwhile another device moves work to the top.
      server.failNext = 1000;
      engine.moveTag({ tag: "art", after: null });
      await until(() => engine.status().state === "offline");
      server.writeTagOrder(["work", "home", "art"]);
      server.failNext = 0;
      engine.retryNow();

      await until(() => engine.status().state === "synced" && server.tagOrder?.tags[0] === "art", 5_000);
      expect(server.tagOrder?.tags).toEqual(["art", "work", "home"]);
      // Sent on the other device's order, whether learned from the changes or from a stale refusal.
      expect(server.tagOrderPushes.at(-1)).toEqual({ baseRevision: 2, tags: ["art", "work", "home"] });
      expect(engine.tagOrder(["work", "home", "art"])).toEqual(["art", "work", "home"]);
    });

    test("moves are kept on the device until the server has them", async () => {
      await withTags();
      server.failNext = 1000;
      engine.moveTag({ tag: "work", after: null });
      await until(() => engine.status().state === "offline");
      await engine.stop();

      server.failNext = 0;
      engine = new SyncEngine(store, server);
      await engine.start();
      expect(engine.tagOrder(["work", "home", "art"])).toEqual(["work", "art", "home"]);
      await until(() => server.tagOrder?.tags[0] === "work");
    });

    test("once ordered, a new tag goes at the end: before the server has it, and after", async () => {
      await withTags();
      engine.moveTag({ tag: "work", after: null });
      await until(() => engine.status().state === "synced");

      server.failNext = 1000;
      engine.addTag(B, "aaa");
      expect(engine.tagOrder(["work", "home", "art", "aaa"])).toEqual(["work", "art", "home", "aaa"]);
      server.failNext = 0;
      engine.retryNow();
      await until(() => engine.status().state === "synced" && server.tagOrder?.tags.includes("aaa") === true, 5_000);
      await until(() => engine.tagOrder(["aaa"])?.length === 1 && (engine as unknown as { meta: { tagOrder?: { tags: string[] } } }).meta.tagOrder?.tags.includes("aaa") === true, 5_000);
      expect(engine.tagOrder(["work", "home", "art", "aaa"])).toEqual(["work", "art", "home", "aaa"]);
    });

    test("a stale order is replayed on the one the server returns", async () => {
      await withTags();
      engine.moveTag({ tag: "home", after: null });
      await until(() => engine.status().state === "synced" && server.tagOrder?.revision === 1);
      // Another device's order, which this one doesn't hear of before pushing: no wake-up.
      server.tagOrder = { revision: 2, tags: ["work", "home", "art"], seq: server.tagOrder!.seq };
      engine.moveTag({ tag: "art", after: null });

      await until(() => engine.status().state === "synced" && server.tagOrder?.revision === 3, 5_000);
      expect(server.tagOrderPushes.slice(-2)).toEqual([
        { baseRevision: 1, tags: ["art", "home", "work"] },
        { baseRevision: 2, tags: ["art", "work", "home"] },
      ]);
      expect(engine.tagOrder(["work", "home", "art"])).toEqual(["art", "work", "home"]);
    });
  });
});
