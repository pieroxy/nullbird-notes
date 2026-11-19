import { ChangesPage, NoteChange, NoteContent, NoteRequest, PushResult } from "../auto/api-types";
import { ApiError, ApiErrorKind } from "../utils/api/Api";
import { LocalNote } from "./LocalNote";
import { LocalStore, SyncMeta } from "./LocalStore";
import {
  applyPushResult, applyRemote, changeOf, created, deletedLocally, edited, forResync, haveRevision, isPushable, needsFetch,
  prepareChange, remoteText, sortKey,
} from "./Outcomes";
import { sha256 } from "./Sha256";
import { SyncApi } from "./SyncApi";
import { uuid } from "./Uuid";

export type SyncState = "starting" | "synced" | "saving" | "offline" | "error" | "signedOut";

/** What the status indicator shows. Derived from what actually happened, never assumed. */
export interface SyncStatus {
  state: SyncState;
  /** Notes with changes the server doesn't have yet. */
  pending: number;
  /** Last successful exchange with the server. */
  lastSyncAt?: number;
  /** Since when the server can't be reached. */
  offlineSince?: number;
  /** When the next attempt is due, while offline or in error. */
  nextRetryAt?: number;
  /** The server's answer, when it answered with an error. */
  message?: string;
  /** Notes the server refused (too large…): see LocalNote.error. */
  noteErrors: number;
  /** Unresolved conflict copies. */
  conflicts: number;
}

/** Timings, in milliseconds. */
export const TIMINGS = {
  /** Above the server's 25 s long poll: past it, the connection is taken for lost. */
  longPollTimeout: 35_000,
  requestTimeout: 30_000,
  /** Edits reach IndexedDB this long after the last keystroke. */
  saveDelay: 300,
  /** A push follows this long after the last keystroke… */
  pushIdleDelay: 1_000,
  /** …or this long after the first one, while typing goes on. */
  pushMaxDelay: 5_000,
  retryMin: 1_000,
  retryMax: 60_000,
};

const MAX_CHANGES_PER_PUSH = 100;
const MAX_NOTES_PER_FETCH = 200;

/**
 * Keeps one user's local notes and the server in sync. The UI reads and edits notes only through
 * it, and gets told of every change via {@link onChange}. Two loops run while started:
 * <ul>
 *   <li>pulling: long polls for changes, fetches the notes that need it (as splices of what's held);</li>
 *   <li>pushing: sends dirty notes shortly after the last keystroke, each change saved as pending
 *   before it's sent, so that a retry (after a network error, or a restart) resends the very same one.</li>
 * </ul>
 * Failures back off from {@link TIMINGS.retryMin} to {@link TIMINGS.retryMax}; a 401 stops both
 * loops (signed out), keeping everything local.
 */
export class SyncEngine {
  private readonly notes = new Map<string, LocalNote>();
  private meta: SyncMeta = { deviceId: "", cursor: 0 };
  private maxNoteSize?: number;
  private running = false;
  private readonly aborts = new Set<AbortController>();
  private readonly listeners = new Set<() => void>();
  private readonly moveListeners = new Set<(from: string, to: string) => void>();

  private connected = false;
  private signedOut = false;
  private lastSyncAt?: number;
  private offlineSince?: number;
  private errorMessage?: string;
  private failures = 0;
  private nextRetryAt?: number;
  /** One per loop waiting out a backoff: wakes it up early. */
  private readonly retryWaiters = new Set<() => void>();

  private pushing = false;
  private pushTimer?: ReturnType<typeof setTimeout>;
  private pushDueAt?: number;
  private saveTimer?: ReturnType<typeof setTimeout>;
  private readonly unsaved = new Set<string>();

  constructor(private readonly store: LocalStore, private readonly api: SyncApi) {}

  async start(): Promise<void> {
    for (const note of await this.store.getAllNotes()) this.notes.set(note.id, note);
    this.meta = (await this.store.getMeta()) ?? { deviceId: uuid(), cursor: 0 };
    await this.store.putMeta(this.meta);
    this.running = true;
    this.notify();
    void this.pullLoop();
    this.schedulePush(0);
  }

  /** Stops both loops and saves unsaved edits. */
  async stop(): Promise<void> {
    this.running = false;
    for (const controller of this.aborts) controller.abort();
    clearTimeout(this.pushTimer);
    this.wakeRetryWaiters();
    await this.save();
  }

  onChange(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /** The user's edits to {@code from} continue on {@code to} (a conflict copy): an editor showing it should follow. */
  onMove(listener: (from: string, to: string) => void): () => void {
    this.moveListeners.add(listener);
    return () => this.moveListeners.delete(listener);
  }

  /** Notes not deleted, newest first. */
  list(): LocalNote[] {
    return [...this.notes.values()].filter((note) => !note.deleted).sort((a, b) => sortKey(b) - sortKey(a));
  }

  get(id: string): LocalNote | undefined {
    return this.notes.get(id);
  }

  status(): SyncStatus {
    const all = [...this.notes.values()];
    const pending = all.filter((note) => note.dirty || note.pending).length;
    const noteErrors = all.filter((note) => note.error).length;
    const conflicts = all.filter((note) => !note.deleted && note.info?.conflictOf).length;
    const base = { pending, noteErrors, conflicts, lastSyncAt: this.lastSyncAt };
    if (this.signedOut) return { ...base, state: "signedOut" };
    if (this.errorMessage) return { ...base, state: "error", message: this.errorMessage, nextRetryAt: this.nextRetryAt };
    if (this.offlineSince !== undefined) return { ...base, state: "offline", offlineSince: this.offlineSince, nextRetryAt: this.nextRetryAt };
    if (!this.connected) return { ...base, state: "starting" };
    if (this.pushing || all.some((note) => isPushable(note) || note.pending)) return { ...base, state: "saving" };
    return { ...base, state: "synced" };
  }

  /** A new, empty note: kept only once edited (see {@link discardIfEmpty}). */
  create(): string {
    const note = created(uuid(), Date.now());
    this.notes.set(note.id, note);
    this.notify();
    return note.id;
  }

  edit(id: string, text: string) {
    const note = this.notes.get(id);
    if (!note || note.deleted || note.text === text) return;
    this.notes.set(id, edited(note, text, Date.now()));
    this.unsaved.add(id);
    this.scheduleSave();
    this.schedulePush(TIMINGS.pushIdleDelay);
    this.notify();
  }

  delete(id: string) {
    const note = this.notes.get(id);
    if (!note) return;
    const after = deletedLocally(note, Date.now());
    if (after) {
      this.notes.set(id, after);
      this.unsaved.add(id);
      this.scheduleSave();
      this.schedulePush(0);
    } else {
      this.notes.delete(id);
      void this.store.removeNote(id);
    }
    this.notify();
  }

  /** Forgets a note created but never edited. */
  discardIfEmpty(id: string) {
    const note = this.notes.get(id);
    if (note && !note.dirty && !note.info && !note.pending) {
      this.notes.delete(id);
      this.notify();
    }
  }

  /** Saves and pushes right away (the page is being hidden or closed). */
  async flush(): Promise<void> {
    await this.save();
    this.schedulePush(0);
  }

  /** Retries now instead of waiting for the backoff. */
  retryNow() {
    this.failures = 0;
    this.wakeRetryWaiters();
    this.schedulePush(0);
  }

  // ---- Pulling ----

  private async pullLoop() {
    let wait = false;
    while (this.running) {
      try {
        const page = await this.withTimeout(TIMINGS.longPollTimeout, (signal) => this.api.changes(this.meta.cursor, wait, signal));
        if (!this.running) return;
        if ((this.meta.storeId && page.storeId !== this.meta.storeId) || page.seq < this.meta.cursor) {
          await this.resync();
          wait = false;
          continue;
        }
        await this.applyChanges(page);
        this.succeeded();
        wait = !page.more;
      } catch (e) {
        if (!(await this.failed(e))) return;
        wait = false;
      }
    }
  }

  private async applyChanges(page: ChangesPage) {
    const toFetch = page.notes.filter((info) => needsFetch(this.notes.get(info.id), info));
    const updated: LocalNote[] = [];
    for (const info of page.notes) {
      const note = this.notes.get(info.id);
      if (note && !toFetch.includes(info)) updated.push(applyRemote(note, { note: info }, undefined, Date.now()));
    }
    for (let i = 0; i < toFetch.length; i += MAX_NOTES_PER_FETCH) {
      updated.push(...await this.fetch(toFetch.slice(i, i + MAX_NOTES_PER_FETCH).map((info) => info.id)));
    }
    if (!this.running) return;
    for (const note of updated) this.notes.set(note.id, note);
    await this.store.putNotes(updated);
    this.meta = { ...this.meta, storeId: page.storeId, cursor: page.seq };
    await this.store.putMeta(this.meta);
    this.notify();
  }

  /** The notes' current server state, each checked against its hash. */
  private async fetch(ids: string[]): Promise<LocalNote[]> {
    // The bases the splices are asked for, as they are now: edits may land while waiting.
    const asked = new Map(ids.map((id) => [id, this.notes.get(id)]));
    const requests: NoteRequest[] = ids.map((id) => ({ id, haveRevision: haveRevision(asked.get(id)) }));
    const result = await this.withTimeout(TIMINGS.requestTimeout, (signal) => this.api.getNotes(requests, signal));
    const fetched: LocalNote[] = [];
    for (const content of result.notes) {
      const text = await this.verifiedText(asked.get(content.note.id), content);
      fetched.push(applyRemote(this.notes.get(content.note.id), content, text, Date.now()));
    }
    return fetched;
  }

  private async verifiedText(asked: LocalNote | undefined, content: NoteContent): Promise<string | undefined> {
    const text = remoteText(asked, content);
    if (text !== undefined && (await sha256(text)) !== content.note.contentHash) {
      throw new Error("Note " + content.note.id + " doesn't match its hash: not applied");
    }
    return text;
  }

  /** The server's data was replaced: start over from scratch, keeping what isn't synced. */
  private async resync() {
    const all = [...this.notes.values()].map(forResync);
    for (const note of all) this.notes.set(note.id, note);
    await this.store.putNotes(all);
    this.meta = { deviceId: this.meta.deviceId, cursor: 0 };
    await this.store.putMeta(this.meta);
    this.notify();
  }

  // ---- Pushing ----

  /** A push in {@code delay} ms at the latest, but no later than {@link TIMINGS.pushMaxDelay} after the first request. */
  private schedulePush(delay: number) {
    if (!this.running) return;
    const now = Date.now();
    if (this.pushDueAt === undefined) this.pushDueAt = now + TIMINGS.pushMaxDelay;
    const at = Math.min(now + delay, this.pushDueAt);
    clearTimeout(this.pushTimer);
    this.pushTimer = setTimeout(() => {
      this.pushDueAt = undefined;
      void this.push();
    }, Math.max(0, at - now));
  }

  private async push() {
    if (!this.running || this.pushing || this.signedOut || this.nextRetryAt !== undefined) return;
    const candidates = [...this.notes.values()].filter((note) => note.pending || isPushable(note));
    if (candidates.length === 0) return;
    this.pushing = true;
    this.notify();
    try {
      if (this.maxNoteSize === undefined) {
        this.maxNoteSize = await this.withTimeout(TIMINGS.requestTimeout, (signal) => this.api.maxNoteSize(signal));
      }
      const batch = await this.prepareBatch(candidates);
      if (batch.changes.length > 0) {
        const results = await this.withTimeout(TIMINGS.requestTimeout, (signal) => this.api.push(this.meta.deviceId, batch.changes, signal));
        // Stopped meanwhile: another engine may own the store now. Pending changes get resent, then replayed.
        if (!this.running) return;
        await this.applyPushResults(results);
      }
      this.succeeded();
      this.pushing = false;
      if ([...this.notes.values()].some((note) => isPushable(note))) this.schedulePush(0);
    } catch (e) {
      this.pushing = false;
      await this.failed(e);
    }
    this.notify();
  }

  /**
   * The changes for one push, within the server's limits: the request cap is twice the largest
   * note, enough for any text once JSON-escaped. Their pending state is saved before they're sent.
   */
  private async prepareBatch(candidates: LocalNote[]): Promise<{ changes: NoteChange[] }> {
    const encoder = new TextEncoder();
    const changes: NoteChange[] = [];
    const saved: LocalNote[] = [];
    let budget = this.maxNoteSize!;
    for (const candidate of candidates) {
      if (changes.length >= MAX_CHANGES_PER_PUSH) break;
      let note = candidate;
      if (!note.pending) {
        if (!note.deleted && encoder.encode(note.text).length > this.maxNoteSize!) {
          note = { ...note, error: { reason: "TOO_LARGE", message: "Larger than the server's limit of " + this.maxNoteSize + " bytes." } };
          this.notes.set(note.id, note);
          saved.push(note);
          continue;
        }
        note = prepareChange(note, uuid()).note;
      }
      const change = changeOf(note);
      const size = encoder.encode(change.content ?? change.splice?.middle ?? "").length;
      if (changes.length > 0 && size > budget) break;
      budget -= size;
      const contentHash = note.pending!.deleted ? undefined : await sha256(note.pending!.submitted);
      changes.push({ ...change, contentHash });
      this.notes.set(note.id, note);
      saved.push(note);
    }
    await this.store.putNotes(saved);
    return { changes };
  }

  private async applyPushResults(results: PushResult[]) {
    const changed: LocalNote[] = [];
    const removed: string[] = [];
    for (const result of results) {
      const note = [...this.notes.values()].find((n) => n.pending?.changeId === result.changeId);
      if (!note) continue;
      const outcome = applyPushResult(note, result);
      if (outcome.removed) {
        this.notes.delete(note.id);
        removed.push(note.id);
      }
      for (const updated of outcome.notes) {
        this.notes.set(updated.id, updated);
        changed.push(updated);
      }
      if (outcome.movedTo) for (const listener of this.moveListeners) listener(note.id, outcome.movedTo);
    }
    await this.store.putNotes(changed);
    for (const id of removed) await this.store.removeNote(id);
  }

  // ---- Saving ----

  private scheduleSave() {
    clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => void this.save(), TIMINGS.saveDelay);
  }

  private async save() {
    clearTimeout(this.saveTimer);
    const notes = [...this.unsaved].map((id) => this.notes.get(id)).filter((note): note is LocalNote => note !== undefined);
    this.unsaved.clear();
    if (notes.length > 0) await this.store.putNotes(notes);
  }

  // ---- Connection state ----

  private succeeded() {
    this.connected = true;
    this.failures = 0;
    this.offlineSince = undefined;
    this.errorMessage = undefined;
    this.nextRetryAt = undefined;
    this.lastSyncAt = Date.now();
  }

  /**
   * Records a failure, then waits out the backoff (a retry can cut it short), then lets pushes
   * resume: edits made meanwhile found the engine backing off.
   *
   * @return false when the loops must stop: signed out, or stopped meanwhile.
   */
  private async failed(error: unknown): Promise<boolean> {
    if (!this.running) return false;
    if (error instanceof ApiError && error.status === 401) {
      this.signedOut = true;
      this.notify();
      return false;
    }
    this.connected = false;
    if (error instanceof ApiError && error.kind === ApiErrorKind.UNREACHABLE) {
      this.offlineSince ??= Date.now();
      this.errorMessage = undefined;
    } else {
      this.errorMessage = error instanceof Error ? error.message : String(error);
    }
    this.failures++;
    const delay = this.retryDelay();
    this.nextRetryAt = Date.now() + delay;
    this.notify();
    await new Promise<void>((resolve) => {
      const wake = () => {
        clearTimeout(timer);
        this.retryWaiters.delete(wake);
        resolve();
      };
      const timer = setTimeout(wake, delay);
      this.retryWaiters.add(wake);
    });
    this.nextRetryAt = undefined;
    this.schedulePush(0);
    return this.running;
  }

  private wakeRetryWaiters() {
    const waiters = [...this.retryWaiters];
    this.retryWaiters.clear();
    for (const wake of waiters) wake();
  }

  private retryDelay(): number {
    return Math.min(TIMINGS.retryMax, TIMINGS.retryMin * 2 ** Math.max(0, this.failures - 1));
  }

  private async withTimeout<T>(millis: number, call: (signal: AbortSignal) => Promise<T>): Promise<T> {
    const controller = new AbortController();
    this.aborts.add(controller);
    const timer = setTimeout(() => controller.abort(), millis);
    try {
      return await call(controller.signal);
    } finally {
      clearTimeout(timer);
      this.aborts.delete(controller);
    }
  }

  private notify() {
    for (const listener of this.listeners) listener();
  }
}
