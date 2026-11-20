import "fake-indexeddb/auto";
import { IDBFactory } from "fake-indexeddb";
import { LocalStore } from "../../../ts/sync/LocalStore";
import { NotesSession } from "../../../ts/sync/NotesSession";
import { FakeServer } from "./FakeServer";
import { until, useInstantDigest } from "./FakeTime";

describe("NotesSession", () => {
  let server: FakeServer;
  const sessions: NotesSession[] = [];

  /** A tab's session. */
  function tab(): NotesSession {
    const session = new NotesSession(() => server, () => undefined);
    sessions.push(session);
    return session;
  }

  /** Lets fake time run while {@code promise} settles: claiming waits for other tabs to answer. */
  async function settle(promise: Promise<void>) {
    let done = false;
    const watched = promise.finally(() => (done = true));
    await until(() => done, 10_000);
    await watched;
  }

  async function storedTexts(username: string): Promise<string[]> {
    const store = await LocalStore.open(username);
    try {
      return (await store.getAllNotes()).map((note) => note.text);
    } finally {
      store.close();
    }
  }

  beforeEach(() => {
    jest.useFakeTimers({ doNotFake: ["setImmediate", "queueMicrotask", "nextTick"] });
    useInstantDigest();
    globalThis.indexedDB = new IDBFactory();
    server = new FakeServer();
  });

  afterEach(async () => {
    for (const session of sessions.splice(0)) await settle(session.destroy("cleanup"));
    jest.useRealTimers();
    jest.restoreAllMocks();
  });

  test("opens the notes and syncs them", async () => {
    const session = tab();
    await settle(session.open("alice"));

    expect(session.phase).toBe("active");
    session.engine!.edit(session.engine!.create(), "Hello\n");
    await until(() => session.engine!.status().state === "synced" && session.engine!.status().pending === 0);
  });

  test("opening them again keeps the same engine", async () => {
    const session = tab();
    await settle(session.open("alice"));
    const engine = session.engine;

    await settle(session.open("alice"));

    expect(session.engine).toBe(engine);
  });

  test("simultaneous opens open them once", async () => {
    const session = tab();
    const first = session.open("alice");
    const second = session.open("alice");
    await settle(Promise.all([first, second]).then(() => undefined));

    expect(session.phase).toBe("active");
  });

  test("another user's notes are closed before opening", async () => {
    const session = tab();
    await settle(session.open("alice"));
    const alice = session.engine!;
    alice.edit(alice.create(), "Alice's\n");

    await settle(session.open("bob"));

    expect(session.phase).toBe("active");
    expect(session.engine).not.toBe(alice);
    expect(session.engine!.list()).toEqual([]);
    expect(await storedTexts("alice")).toEqual(["Alice's\n"]);
  });

  test("another tab takes them over, and they can be taken back", async () => {
    const first = tab();
    await settle(first.open("alice"));
    const second = tab();

    await settle(second.open("alice"));

    expect(second.phase).toBe("active");
    expect(first.phase).toBe("elsewhere");
    expect(first.engine).toBeUndefined();

    await settle(first.open("alice"));
    expect(first.phase).toBe("elsewhere");

    await settle(first.takeOver());
    expect(first.phase).toBe("active");
    expect(second.phase).toBe("elsewhere");
  });

  test("destroy closes them and deletes this device's copy", async () => {
    const session = tab();
    await settle(session.open("alice"));
    session.engine!.edit(session.engine!.create(), "Local\n");

    await settle(session.destroy("alice"));

    expect(session.phase).toBe("idle");
    expect(session.engine).toBeUndefined();
    expect(await storedTexts("alice")).toEqual([]);
  });

  test("they can be opened again after being destroyed", async () => {
    const session = tab();
    await settle(session.open("alice"));
    await settle(session.destroy("alice"));

    await settle(session.open("alice"));

    expect(session.phase).toBe("active");
  });
});
