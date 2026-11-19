import { LocalNote } from "./LocalNote";

/** Sync state of the device, stored alongside the notes. */
export interface SyncMeta {
  deviceId: string;
  /** Absent until the first page of changes. */
  storeId?: string;
  cursor: number;
}

const NOTES = "notes";
const META = "meta";
const META_KEY = "sync";

/** One IndexedDB database per user, so that two accounts on one browser never mix. */
export class LocalStore {
  private constructor(private readonly db: IDBDatabase) {}

  static open(username: string): Promise<LocalStore> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(LocalStore.databaseName(username), 1);
      request.onupgradeneeded = () => {
        request.result.createObjectStore(NOTES, { keyPath: "id" });
        request.result.createObjectStore(META);
      };
      request.onsuccess = () => {
        // Lets another tab delete the database (see destroy).
        request.result.onversionchange = () => request.result.close();
        resolve(new LocalStore(request.result));
      };
      request.onerror = () => reject(request.error);
    });
  }

  /** Deletes the user's local data for good (logging out). */
  static destroy(username: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.deleteDatabase(LocalStore.databaseName(username));
      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
      // Another tab still has it open: it closes on versionchange (see open), then this succeeds.
      request.onblocked = () => undefined;
    });
  }

  close() {
    this.db.close();
  }

  getAllNotes(): Promise<LocalNote[]> {
    return this.request(NOTES, "readonly", (store) => store.getAll());
  }

  /** In one transaction: all of them are saved, or none. */
  putNotes(notes: LocalNote[]): Promise<void> {
    return new Promise((resolve, reject) => {
      const transaction = this.db.transaction(NOTES, "readwrite");
      const store = transaction.objectStore(NOTES);
      for (const note of notes) store.put(note);
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error);
    });
  }

  removeNote(id: string): Promise<void> {
    return this.request(NOTES, "readwrite", (store) => store.delete(id)).then(() => undefined);
  }

  getMeta(): Promise<SyncMeta | undefined> {
    return this.request(META, "readonly", (store) => store.get(META_KEY));
  }

  putMeta(meta: SyncMeta): Promise<void> {
    return this.request(META, "readwrite", (store) => store.put(meta, META_KEY)).then(() => undefined);
  }

  private request<T>(storeName: string, mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
    return new Promise((resolve, reject) => {
      const request = run(this.db.transaction(storeName, mode).objectStore(storeName));
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }

  private static databaseName(username: string): string {
    return "notes-" + username;
  }
}
