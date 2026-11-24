import { ChangesPage, MetaChange, NoteChange, NoteContent, NoteRequest, PushResult, TagEvent, TagOptions, TagOptionsChange, TagOrderChange, TagOrderResult, TagOrderStatus } from "../auto/api-types";
import { ApiError, ApiErrorKind } from "../utils/api/Api";
import { LocalNote } from "./LocalNote";
import { LocalStore, SyncMeta } from "./LocalStore";
import {
  addTag, applyMetaResult, hasMetaToPush, isValidTag, metaChangeOf, noOps, prepareMeta, removeTag, retaggedNote, setOption, tagsOf,
} from "./Metadata";
import {
  applyPushResult, applyRemote, changeOf, created, deletedLocally, edited, forResync, haveRevision, isPushable, needsFetch,
  compareNotes, prepareChange, remoteText,
} from "./Outcomes";
import { sha256 } from "./Sha256";
import { SyncApi } from "./SyncApi";
import { displayOrder, orderToSend, TagMove } from "./TagOrder";
import { uuid } from "./Uuid";

export type SyncState = "starting" | "synced" | "saving" | "offline" | "error" | "signedOut" | "outdated";

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
  /** Notes the server refused (too large…): see LocalNote.error and LocalNote.metaError. */
  noteErrors: number;
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
 * Failures back off from {@link TIMINGS.retryMin} to {@link TIMINGS.retryMax}; a 401 (signed out)
 * or a server running another build (outdated) stops both loops, keeping everything local.
 */
export class SyncEngine {
  private readonly notes = new Map<string, LocalNote>();
  private meta: SyncMeta = { deviceId: "", cursor: 0 };
  private maxNoteSize?: number;
  private running = false;
  private readonly aborts = new Set<AbortController>();
  private readonly listeners = new Set<() => void>();
  private readonly tagEventListeners = new Set<(events: TagEvent[]) => void>();

  private connected = false;
  private signedOut = false;
  private outdated = false;
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


  /** Notes not deleted, pinned ones first, then newest first (see compareNotes). */
  list(): LocalNote[] {
    return [...this.notes.values()].filter((note) => !note.deleted).sort(compareNotes);
  }

  get(id: string): LocalNote | undefined {
    return this.notes.get(id);
  }

  status(): SyncStatus {
    const all = [...this.notes.values()];
    const pending = all.filter((note) => !isDraft(note) && (note.dirty || note.pending || note.metaOps || note.pendingMeta)).length;
    const noteErrors = all.filter((note) => note.error || note.metaError).length;
    const base = { pending, noteErrors, lastSyncAt: this.lastSyncAt };
    if (this.outdated) return { ...base, state: "outdated" };
    if (this.signedOut) return { ...base, state: "signedOut" };
    if (this.errorMessage) return { ...base, state: "error", message: this.errorMessage, nextRetryAt: this.nextRetryAt };
    if (this.offlineSince !== undefined) return { ...base, state: "offline", offlineSince: this.offlineSince, nextRetryAt: this.nextRetryAt };
    if (!this.connected) return { ...base, state: "starting" };
    if (this.pushing || all.some((note) => hasWork(note)) || this.tagMoves().length > 0) return { ...base, state: "saving" };
    return { ...base, state: "synced" };
  }

  /**
   * A new, empty note: kept only once edited (see {@link discardIfEmpty}). Its tags are sent once
   * the server has the note.
   */
  create(tags: string[] = []): string {
    const note = created(uuid(), Date.now());
    if (tags.length > 0) note.metaOps = tags.reduce(addTag, noOps());
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

  /** @return false if {@code tag} isn't a valid tag (see isValidTag). */
  addTag(id: string, tag: string): boolean {
    if (!isValidTag(tag)) return false;
    const note = this.notes.get(id);
    if (note && !tagsOf(note).includes(tag)) this.changeMeta(note, addTag(note.metaOps ?? noOps(), tag));
    return true;
  }

  removeTag(id: string, tag: string) {
    const note = this.notes.get(id);
    if (note && tagsOf(note).includes(tag)) this.changeMeta(note, removeTag(note.metaOps ?? noOps(), tag));
  }

  /**
   * The order to list {@code shown} (tags) in, as the user ordered them: undefined while they
   * never did (alphabetical, then). See TagOrder.ts.
   */
  tagOrder(shown: string[]): string[] | undefined {
    return displayOrder(this.meta.tagOrder, this.tagMoves(), this.allTags(), shown);
  }

  /** Moves a tag in the order: shown at once, sent to the server with the next push. */
  moveTag(move: TagMove) {
    this.meta = { ...this.meta, tagMoves: [...this.tagMoves(), move] };
    void this.store.putMeta(this.meta);
    this.schedulePush(0);
    this.notify();
  }

  private tagMoves(): TagMove[] {
    return this.meta.tagMoves ?? [];
  }

  /** Every tag of every note on the device, deleted ones included, as the server counts them. */
  private allTags(): Set<string> {
    const tags = new Set<string>();
    for (const note of this.notes.values()) for (const tag of tagsOf(note)) tags.add(tag);
    return tags;
  }

  /** Each tag's options: the server's, with the changes it hasn't confirmed yet. Tags with none aren't in it. */
  allTagOptions(): { [tag: string]: TagOptions } {
    const all = { ...(this.meta.tagOptions ?? {}) };
    for (const change of this.tagOptionsChanges()) {
      if (change.options && hasOptions(change.options)) all[change.tag] = change.options;
      else delete all[change.tag];
    }
    return all;
  }

  /** Replaces {@code tag}'s options, none for undefined: shown at once, sent with the next push. */
  setTagOptions(tag: string, options: TagOptions | undefined) {
    const change: TagOptionsChange = options && hasOptions(options) ? { tag, options } : { tag };
    // Only the last change for a tag matters: earlier ones not sent yet go.
    this.meta = { ...this.meta, tagOptionsChanges: [...this.tagOptionsChanges().filter((c) => c.tag !== tag), change] };
    void this.store.putMeta(this.meta);
    this.schedulePush(0);
    this.notify();
  }

  private tagOptionsChanges(): TagOptionsChange[] {
    return this.meta.tagOptionsChanges ?? [];
  }

  /** Every tag of every note, deleted ones included: renaming to one of them merges (see renameTag). */
  allTagNames(): string[] {
    return [...this.allTags()];
  }

  /** How many notes have {@code tag}, deleted ones included: as many as renaming or removing it changes. */
  tagNoteCount(tag: string): number {
    let count = 0;
    for (const note of this.notes.values()) if (tagsOf(note).includes(tag)) count++;
    return count;
  }

  /**
   * Renames {@code from} to {@code to} on every note, merging it into {@code to} if in use (see
   * NoteService#renameTag). Needs the server: thrown its error if it can't be reached.
   *
   * @return the IDs of the notes the server changed.
   */
  async renameTag(from: string, to: string): Promise<string[]> {
    if (!isValidTag(to)) throw new Error("A tag is 1 to 64 characters, without spaces.");
    const output = await this.withTimeout(TIMINGS.requestTimeout, (signal) => this.api.renameTag(this.meta.deviceId, from, to, signal));
    await this.applyTagEvents([{ from, to }]);
    return output.noteIds;
  }

  /** Removes {@code tag} from every note, likewise. */
  async deleteTag(tag: string): Promise<string[]> {
    const output = await this.withTimeout(TIMINGS.requestTimeout, (signal) => this.api.deleteTag(this.meta.deviceId, tag, signal));
    await this.applyTagEvents([{ from: tag }]);
    return output.noteIds;
  }

  /** Told of tags renamed or removed, here or on another device: what refers to them by name follows (the tag filter). */
  onTagEvents(listener: (events: TagEvent[]) => void): () => void {
    this.tagEventListeners.add(listener);
    return () => this.tagEventListeners.delete(listener);
  }

  /**
   * The server renamed or removed tags on every note: the changes not confirmed yet follow, and the
   * tags as known, without waiting for the notes' changes to come. Applied twice (here, then with the
   * changes), the second time finds nothing to do.
   */
  private async applyTagEvents(events: TagEvent[]) {
    const changed: LocalNote[] = [];
    for (const note of this.notes.values()) {
      let after = note;
      for (const event of events) after = retaggedNote(after, event.from, event.to);
      if (after !== note) changed.push(after);
    }
    for (const note of changed) this.notes.set(note.id, note);
    if (changed.length > 0) await this.store.putNotes(changed);
    let moves = this.tagMoves();
    for (const event of events) {
      moves = moves.filter((move) => event.to !== undefined || move.tag !== event.from)
        .map((move) => ({ tag: move.tag === event.from ? event.to! : move.tag, after: move.after === event.from ? event.to ?? null : move.after }));
    }
    // Options follow, as on the server: merged, the target keeps its own; removed, they go.
    let options = this.meta.tagOptions;
    let optionsChanges = this.tagOptionsChanges();
    for (const event of events) {
      if (options && event.from in options) {
        options = { ...options };
        const moved = options[event.from];
        delete options[event.from];
        if (event.to !== undefined && !(event.to in options)) options[event.to] = moved;
      }
      optionsChanges = optionsChanges.filter((c) => c.tag !== event.from || event.to !== undefined)
        .map((c) => (c.tag === event.from ? { ...c, tag: event.to! } : c));
    }
    if (JSON.stringify(moves) !== JSON.stringify(this.tagMoves()) || options !== this.meta.tagOptions
      || JSON.stringify(optionsChanges) !== JSON.stringify(this.tagOptionsChanges())) {
      this.meta = { ...this.meta, tagMoves: moves, tagOptions: options, tagOptionsChanges: optionsChanges };
      await this.store.putMeta(this.meta);
    }
    for (const listener of this.tagEventListeners) listener(events);
    this.notify();
  }

  /** An option's value, none to unset it: a note's own, over its tags' (see NoteOptions). */
  setOptionValue(id: string, key: string, value: string | undefined) {
    const note = this.notes.get(id);
    if (note) this.changeMeta(note, setOption(note.metaOps ?? noOps(), key, value));
  }

  /** A boolean option: off is its default, so it's unset rather than set to "false". */
  setOption(id: string, key: string, on: boolean) {
    const note = this.notes.get(id);
    if (note) this.changeMeta(note, setOption(note.metaOps ?? noOps(), key, on ? "true" : undefined));
  }

  /** A note never edited becomes dirty: the server must have it before it can take its metadata. */
  private changeMeta(note: LocalNote, metaOps: LocalNote["metaOps"]) {
    if (note.deleted) return;
    const neverSent = note.baseRevision === 0 && !note.info && !note.dirty && !note.pending;
    this.notes.set(note.id, { ...note, metaOps, metaError: undefined, dirty: note.dirty || neverSent });
    this.unsaved.add(note.id);
    this.scheduleSave();
    this.schedulePush(0);
    this.notify();
  }

  /** Forgets a note created but never edited. */
  discardIfEmpty(id: string) {
    const note = this.notes.get(id);
    if (note && isDraft(note)) {
      this.notes.delete(id);
      this.notify();
    }
  }

  /** Saves and pushes right away (the page is being hidden or closed). */
  async flush(): Promise<void> {
    await this.save();
    this.schedulePush(0);
  }

  /**
   * {@link retryNow}, but only while waiting to retry: for hints that the server may be reachable
   * again (the network came back, the app came back to the foreground). Otherwise nothing.
   */
  retryIfWaiting() {
    if (this.nextRetryAt !== undefined) this.retryNow();
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
    const fetched: { content: NoteContent; text?: string }[] = [];
    for (let i = 0; i < toFetch.length; i += MAX_NOTES_PER_FETCH) {
      fetched.push(...await this.fetch(toFetch.slice(i, i + MAX_NOTES_PER_FETCH).map((info) => info.id)));
    }
    if (!this.running) return;
    // Once nothing is awaited anymore: from the notes as they are now, edits made meanwhile included.
    const now = Date.now();
    const updated = fetched.map(({ content, text }) => applyRemote(this.notes.get(content.note.id), content, text, now));
    for (const info of page.notes) {
      const note = this.notes.get(info.id);
      if (note && !toFetch.includes(info)) updated.push(applyRemote(note, { note: info }, undefined, now));
    }
    for (const note of updated) this.notes.set(note.id, note);
    await this.store.putNotes(updated);
    if (page.tagEvents?.length) await this.applyTagEvents(page.tagEvents);
    this.meta = { ...this.meta, storeId: page.storeId, cursor: page.seq };
    if (page.tagOrder && page.tagOrder.revision > (this.meta.tagOrder?.revision ?? 0)) this.meta = { ...this.meta, tagOrder: page.tagOrder };
    if (page.tagOptions) this.meta = { ...this.meta, tagOptions: page.tagOptions };
    await this.store.putMeta(this.meta);
    this.notify();
  }

  /** The notes' current server state, each text rebuilt and checked against its hash. */
  private async fetch(ids: string[]): Promise<{ content: NoteContent; text?: string }[]> {
    // The bases the splices are asked for, as they are now: edits may land while waiting.
    const asked = new Map(ids.map((id) => [id, this.notes.get(id)]));
    const requests: NoteRequest[] = ids.map((id) => ({ id, haveRevision: haveRevision(asked.get(id)) }));
    const result = await this.withTimeout(TIMINGS.requestTimeout, (signal) => this.api.getNotes(requests, signal));
    const fetched = [];
    for (const content of result.notes) fetched.push({ content, text: await this.verifiedText(asked.get(content.note.id), content) });
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
    // The new data's tag order comes with it; the moves not sent yet are replayed on it.
    this.meta = { deviceId: this.meta.deviceId, cursor: 0, tagMoves: this.meta.tagMoves };
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
    if (!this.running || this.pushing || this.signedOut || this.outdated || this.nextRetryAt !== undefined) return;
    const candidates = [...this.notes.values()].filter(hasWork);
    if (candidates.length === 0 && this.tagMoves().length === 0 && this.tagOptionsChanges().length === 0) return;
    this.pushing = true;
    this.notify();
    try {
      if (this.maxNoteSize === undefined) {
        this.maxNoteSize = await this.withTimeout(TIMINGS.requestTimeout, (signal) => this.api.maxNoteSize(signal));
      }
      const batch = await this.prepareBatch(candidates);
      // The moves made so far: those made while the push is out stay for the next one.
      const sentMoves = this.tagMoves().length;
      const tagOrder: TagOrderChange | undefined = sentMoves > 0
        ? { baseRevision: this.meta.tagOrder?.revision ?? 0, tags: orderToSend(this.meta.tagOrder, this.tagMoves(), this.allTags()) }
        : undefined;
      // Those made while the push is out stay for the next one, as moves.
      const sentOptions = this.tagOptionsChanges();
      const tagOptions = sentOptions.length > 0 ? sentOptions : undefined;
      if (batch.changes.length > 0 || batch.metaChanges.length > 0 || tagOrder || tagOptions) {
        const output = await this.withTimeout(TIMINGS.requestTimeout,
          (signal) => this.api.push(this.meta.deviceId, { ...batch, tagOrder, tagOptions, since: this.meta.cursor }, signal));
        // Stopped meanwhile: another engine may own the store now. Pending changes get resent, then replayed.
        if (!this.running) return;
        await this.applyPushResults(output.results, output.metaResults);
        if (output.tagOrder) await this.applyTagOrderResult(output.tagOrder, sentMoves);
        if (output.tagOptions) {
          this.meta = { ...this.meta, tagOptions: output.tagOptions, tagOptionsChanges: this.tagOptionsChanges().filter((c) => !sentOptions.includes(c)) };
          await this.store.putMeta(this.meta);
        }
      }
      this.succeeded();
      this.pushing = false;
      if ([...this.notes.values()].some((note) => isPushable(note) || hasMetaToPush(note)) || this.tagMoves().length > 0
        || this.tagOptionsChanges().length > 0) this.schedulePush(0);
    } catch (e) {
      this.pushing = false;
      await this.failed(e);
    }
    this.notify();
  }

  /**
   * Applied, the moves sent are done; stale (the order changed since), they stay, to be replayed on
   * the order the server has now; rejected, they're dropped: they'd be rejected again.
   */
  private async applyTagOrderResult(result: TagOrderResult, sentMoves: number) {
    const known = this.meta.tagOrder?.revision ?? 0;
    let meta: SyncMeta = this.meta;
    if (result.order && result.order.revision >= known) meta = { ...meta, tagOrder: result.order };
    else if (!result.order && result.status === TagOrderStatus.STALE) meta = { ...meta, tagOrder: undefined };
    if (result.status !== TagOrderStatus.STALE) meta = { ...meta, tagMoves: this.tagMoves().slice(sentMoves) };
    if (result.status === TagOrderStatus.REJECTED) console.warn("Tag order refused by the server: " + result.message);
    this.meta = meta;
    await this.store.putMeta(meta);
  }

  /**
   * The changes for one push, within the server's limits: the request cap is twice the largest
   * note, enough for any text once JSON-escaped. Their pending state is saved before they're sent.
   */
  private async prepareBatch(candidates: LocalNote[]): Promise<{ changes: NoteChange[]; metaChanges: MetaChange[] }> {
    const encoder = new TextEncoder();
    const changes: NoteChange[] = [];
    const saved = new Map<string, LocalNote>();
    let budget = this.maxNoteSize!;
    for (const candidate of candidates.filter((note) => note.pending || isPushable(note))) {
      if (changes.length >= MAX_CHANGES_PER_PUSH) break;
      let note = candidate;
      if (!note.pending) {
        if (!note.deleted && encoder.encode(note.text).length > this.maxNoteSize!) {
          note = { ...note, error: { reason: "TOO_LARGE", message: "Larger than the server's limit of " + this.maxNoteSize + " bytes." } };
          this.notes.set(note.id, note);
          saved.set(note.id, note);
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
      saved.set(note.id, note);
    }
    // Small: no budget, only the count limit.
    const metaChanges: MetaChange[] = [];
    for (const candidate of candidates.filter((note) => note.pendingMeta || hasMetaToPush(note))) {
      if (metaChanges.length >= MAX_CHANGES_PER_PUSH) break;
      const current = this.notes.get(candidate.id)!;
      const note = current.pendingMeta ? current : prepareMeta(current, uuid());
      metaChanges.push(metaChangeOf(note));
      this.notes.set(note.id, note);
      saved.set(note.id, note);
    }
    await this.store.putNotes([...saved.values()]);
    return { changes, metaChanges };
  }

  private async applyPushResults(results: PushResult[], metaResults: PushResult[]) {
    const changed = new Map<string, LocalNote>();
    const removed: string[] = [];
    for (const result of results) {
      const note = [...this.notes.values()].find((n) => n.pending?.changeId === result.changeId);
      if (!note) continue;
      const outcome = applyPushResult(note, result);
      if (outcome.removed) {
        this.notes.delete(note.id);
        removed.push(note.id);
      }
      if (outcome.note) {
        this.notes.set(note.id, outcome.note);
        changed.set(note.id, outcome.note);
      }
    }
    for (const result of metaResults) {
      const note = [...this.notes.values()].find((n) => n.pendingMeta?.changeId === result.changeId);
      if (!note) continue;
      const updated = applyMetaResult(note, result);
      this.notes.set(updated.id, updated);
      changed.set(updated.id, updated);
    }
    await this.store.putNotes([...changed.values()]);
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
   * @return false when the loops must stop: signed out, outdated, or stopped meanwhile.
   */
  private async failed(error: unknown): Promise<boolean> {
    if (!this.running) return false;
    if (error instanceof ApiError && error.status === 401) {
      this.signedOut = true;
      this.notify();
      return false;
    }
    if (error instanceof ApiError && error.kind === ApiErrorKind.OUTDATED) {
      this.outdated = true;
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

/** Something to push, or a push to resend. */
/** Created but never edited: the server doesn't know it, nothing to sync yet. */
function isDraft(note: LocalNote): boolean {
  return !note.dirty && !note.info && !note.pending;
}

function hasWork(note: LocalNote): boolean {
  return isPushable(note) || note.pending !== undefined || hasMetaToPush(note) || note.pendingMeta !== undefined;
}

/** Anything set: one with nothing is the same as no options. */
export function hasOptions(options: TagOptions): boolean {
  // Flags that only mean something on: off is the same as unset.
  return Object.entries(options).some(([key, value]) => value !== undefined && value !== null
    && !((key === "hidden" || key === "rendered") && value === false));
}
