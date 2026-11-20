import "fake-indexeddb/auto";
import { IDBFactory } from "fake-indexeddb";
import { PushStatus } from "../../../ts/auto/api-types";
import { LocalStore } from "../../../ts/sync/LocalStore";
import { tagsOf } from "../../../ts/sync/Metadata";
import { sha256Bytes } from "../../../ts/sync/Sha256";
import { computeSplice } from "../../../ts/sync/Splice";
import { SyncEngine, TIMINGS } from "../../../ts/sync/SyncEngine";
import { FakeServer } from "./FakeServer";

const A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const COPY = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
const hash = (text: string) => sha256Bytes(new TextEncoder().encode(text));

describe("SyncEngine", () => {
  let server: FakeServer;
  let store: LocalStore;
  let engine: SyncEngine;

  /** Advances fake time in small steps until {@code condition} holds. */
  async function until(condition: () => boolean, maxMillis = 120_000) {
    for (let elapsed = 0; elapsed <= maxMillis; elapsed += 50) {
      if (condition()) return;
      await jest.advanceTimersByTimeAsync(50);
    }
    throw new Error("Condition not met within " + maxMillis + " ms");
  }

  /** Advances fake time in small steps: IndexedDB work runs on real setImmediate, between them. */
  async function advance(millis: number) {
    for (let elapsed = 0; elapsed < millis; elapsed += 50) await jest.advanceTimersByTimeAsync(50);
  }

  async function startEngine() {
    engine = new SyncEngine(store, server);
    await engine.start();
    await until(() => engine.status().state === "synced");
  }

  beforeEach(async () => {
    // fake-indexeddb schedules its work with setImmediate: real ones keep it running.
    jest.useFakeTimers({ doNotFake: ["setImmediate", "queueMicrotask", "nextTick"] });
    globalThis.indexedDB = new IDBFactory();
    server = new FakeServer();
    store = await LocalStore.open("alice");
  });

  afterEach(async () => {
    await engine?.stop();
    store.close();
    jest.useRealTimers();
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
      await jest.advanceTimersByTimeAsync(500);
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
    const id = engine.create();
    engine.edit(id, "Once");
    await until(() => engine.get(id)?.pending !== undefined);
    // The answer gets lost: the server applied it, but the engine sees a network error.
    const push = server.push.bind(server);
    let lose = true;
    server.push = async (deviceId, changes, metaChanges) => {
      const results = await push(deviceId, changes, metaChanges);
      if (lose) {
        lose = false;
        throw new (await import("../../../ts/utils/api/Api")).ApiError(0, "Cannot reach the server.");
      }
      return results;
    };

    await until(() => engine.status().state === "synced" && !engine.get(id)?.dirty);
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
    server.onPush = (change) => {
      if (change.baseRevision !== 0) throw new Error("Unsynced text must be pushed as new after a resync");
      return {
        changeId: change.changeId, status: PushStatus.CONFLICT, note: server.notes.get(A)!.info,
        content: computeSplice(change.content!, "Restored"), copy: server.write(COPY, change.content!),
      };
    };

    await until(() => engine.get(COPY)?.text === "Synced, then edited offline" && engine.get(A)?.text === "Restored");
    expect((await store.getMeta())?.storeId).toBe("store-2");
  });

  test("text typed during a conflicting push follows the user onto the copy", async () => {
    server.write(A, "I like cats.");
    await startEngine();
    let open!: () => void;
    server.gate = new Promise((resolve) => (open = resolve));
    server.onPush = (change) => {
      server.onPush = undefined;
      return {
        changeId: change.changeId, status: PushStatus.CONFLICT, note: server.write(A, "I like dogs."),
        content: computeSplice("I like birds.", "I like dogs."), copy: server.write(COPY, "I like birds."),
      };
    };
    const moves: [string, string][] = [];
    engine.onMove((from, to) => moves.push([from, to]));

    engine.edit(A, "I like birds.");
    await until(() => server.pushes.length === 1);
    engine.edit(A, "I like birds a lot.");
    open();
    server.gate = undefined;

    await until(() => moves.length === 1);
    expect(moves).toEqual([[A, COPY]]);
    expect(engine.get(A)?.text).toBe("I like dogs.");
    expect(engine.get(COPY)?.text).toBe("I like birds a lot.");
    await until(() => server.text(COPY) === "I like birds a lot.");
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
});
