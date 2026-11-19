import "fake-indexeddb/auto";
import { IDBFactory } from "fake-indexeddb";
import { LocalNote } from "../../../ts/sync/LocalNote";
import { LocalStore } from "../../../ts/sync/LocalStore";

function note(id: string, text: string, extra: Partial<LocalNote> = {}): LocalNote {
  return { id, baseRevision: 0, baseText: "", text, deleted: false, dirty: true, editedAt: 1, ...extra };
}

const byId = (notes: LocalNote[]) => [...notes].sort((a, b) => a.id.localeCompare(b.id));

describe("LocalStore", () => {
  let store: LocalStore;

  beforeEach(async () => {
    globalThis.indexedDB = new IDBFactory();
    store = await LocalStore.open("alice");
  });

  afterEach(() => store.close());

  test("starts empty", async () => {
    expect(await store.getAllNotes()).toEqual([]);
    expect(await store.getMeta()).toBeUndefined();
  });

  test("saves notes and replaces them by id", async () => {
    await store.putNotes([note("a", "one"), note("b", "two")]);
    await store.putNotes([note("a", "one, edited")]);

    expect(byId(await store.getAllNotes())).toEqual([note("a", "one, edited"), note("b", "two")]);
  });

  test("keeps every field, nested ones included, and any text", async () => {
    const full = note("a", "Café ☕ " + String.fromCharCode(0xd83d, 0xde00) + "\n".repeat(3) + "x".repeat(1_000_000), {
      info: {
        id: "a", revision: 3, seq: 7, createdAt: 10, modifiedAt: 20, deleted: false, title: "Café", size: 42, contentHash: "h",
        conflictOf: { noteId: "b" },
      },
      baseRevision: 3, baseText: "base", basedOnChangeId: "c0",
      pending: { changeId: "c1", submitted: "sent", deleted: false },
      error: { reason: "TOO_LARGE", message: "too big" },
      forceFull: true,
    });
    await store.putNotes([full]);

    expect(await store.getAllNotes()).toEqual([full]);
  });

  test("saves a batch atomically: nothing when one note is invalid", async () => {
    await store.putNotes([note("a", "before")]);
    const invalid = { text: "no id" } as unknown as LocalNote;

    await expect(store.putNotes([note("a", "after"), note("b", "new"), invalid])).rejects.toBeDefined();
    expect(await store.getAllNotes()).toEqual([note("a", "before")]);
  });

  test("removes a note", async () => {
    await store.putNotes([note("a", "one"), note("b", "two")]);
    await store.removeNote("a");

    expect(await store.getAllNotes()).toEqual([note("b", "two")]);
  });

  test("saves the sync state", async () => {
    await store.putMeta({ deviceId: "d1", cursor: 0 });
    await store.putMeta({ deviceId: "d1", storeId: "s1", cursor: 42 });

    expect(await store.getMeta()).toEqual({ deviceId: "d1", storeId: "s1", cursor: 42 });
  });

  test("keeps everything across a reopen", async () => {
    await store.putNotes([note("a", "kept")]);
    await store.putMeta({ deviceId: "d1", cursor: 5 });
    store.close();

    store = await LocalStore.open("alice");
    expect(await store.getAllNotes()).toEqual([note("a", "kept")]);
    expect(await store.getMeta()).toEqual({ deviceId: "d1", cursor: 5 });
  });

  test("keeps users apart", async () => {
    await store.putNotes([note("a", "alice's")]);
    const bob = await LocalStore.open("bob");
    try {
      expect(await bob.getAllNotes()).toEqual([]);
      await bob.putNotes([note("a", "bob's")]);
      expect(await store.getAllNotes()).toEqual([note("a", "alice's")]);
    } finally {
      bob.close();
    }
  });

  test("destroy wipes the user's data, even while it is still open", async () => {
    await store.putNotes([note("a", "secret")]);
    await store.putMeta({ deviceId: "d1", cursor: 5 });
    const bob = await LocalStore.open("bob");
    await bob.putNotes([note("b", "bob's")]);

    await LocalStore.destroy("alice");

    store = await LocalStore.open("alice");
    expect(await store.getAllNotes()).toEqual([]);
    expect(await store.getMeta()).toBeUndefined();
    expect(await bob.getAllNotes()).toEqual([note("b", "bob's")]);
    bob.close();
  });
});
