import {
  ChangesPage, MetaChange, NoteChange, NoteInfo, NoteRequest, NotesResult, PushApiOutput, PushResult, PushStatus, RejectReason,
  TagEditOutput, TagEvent, TagOptions, TagOrder, TagOrderChange, TagOrderResult, TagOrderStatus,
} from "../../../ts/auto/api-types";
import { ApiError, ApiErrorKind } from "../../../ts/utils/api/Api";
import { sha256Bytes } from "../../../ts/sync/Sha256";
import { applySplice, computeSplice, fullSplice } from "../../../ts/sync/Splice";
import { retagged } from "../../../ts/sync/Metadata";
import { PushBatch, SyncApi } from "../../../ts/sync/SyncApi";

interface ServerNote {
  info: NoteInfo;
  revisions: string[];
}

const hash = (text: string) => sha256Bytes(new TextEncoder().encode(text));

/**
 * The server, in memory, for SyncEngine tests: revisions, seq, replay by changeId, a 25 s long
 * poll. Pushes fast-forward; anything else is up to {@link onPush}. Calls can be made to fail.
 */
export class FakeServer implements SyncApi {
  storeId = "store-1";
  seq = 0;
  readonly notes = new Map<string, ServerNote>();
  /** Every push received, in order (text changes only). */
  readonly pushes: NoteChange[][] = [];
  /** The metadata changes of every push that had some, in order. */
  readonly metaPushes: MetaChange[][] = [];
  /** The next calls throw UNREACHABLE. */
  failNext = 0;
  /** Calls throw a 401. */
  unauthorized = false;
  /** Calls are refused as coming from another build. */
  outdated = false;
  /** Long polls never answer (a dead connection). */
  silent = false;
  /** Answers a change instead of the default fast-forward, when it returns something. */
  onPush?: (change: NoteChange) => PushResult | undefined;
  /** Holds pushes until resolved, to act while one is in flight. */
  gate?: Promise<void>;
  /** Holds note fetches likewise. */
  fetchGate?: Promise<void>;
  /** Every fetch received, in order. */
  readonly fetches: NoteRequest[][] = [];
  /** The user's tag order, as the real server keeps it (see UserNoteStore): none until first sent. */
  tagOrder?: TagOrder & { seq: number };
  /** Every tag order received, in order. */
  readonly tagOrderPushes: TagOrderChange[] = [];
  /** The cursor each push came with. */
  readonly pushCursors: number[] = [];
  /** Tags renamed or removed (see renameTag), at their seq. */
  readonly tagEvents: (TagEvent & { seq: number })[] = [];
  /** Each tag's options, as the real server keeps them, at their seq. */
  tagOptions?: { tags: { [tag: string]: TagOptions }; seq: number };
  private readonly replays = new Map<string, PushResult>();
  private waiters: (() => void)[] = [];

  async maxNoteSize(): Promise<number> {
    this.check();
    return 1000;
  }

  async changes(since: number, wait: boolean, signal: AbortSignal): Promise<ChangesPage> {
    this.check();
    if (wait && since === this.seq) {
      await new Promise<void>((resolve, reject) => {
        const timer = this.silent ? undefined : setTimeout(resolve, 25_000);
        this.waiters.push(resolve);
        signal.addEventListener("abort", () => {
          clearTimeout(timer);
          reject(new ApiError(ApiErrorKind.UNREACHABLE, "The server took too long to answer."));
        });
      });
    }
    const notes = [...this.notes.values()].map((n) => n.info).filter((info) => info.seq > since).sort((a, b) => a.seq - b.seq);
    const order = this.tagOrder && this.tagOrder.seq > since ? { revision: this.tagOrder.revision, tags: this.tagOrder.tags } : undefined;
    const events = this.tagEvents.filter((e) => e.seq > since).map(({ from, to }) => (to === undefined ? { from } : { from, to }));
    const tagOptions = this.tagOptions && this.tagOptions.seq > since ? this.tagOptions.tags : undefined;
    const purged = this.purges.filter((p) => p.seq > since).map((p) => p.id);
    return { storeId: this.storeId, seq: this.seq, notes, more: false, tagOrder: order, tagEvents: events.length ? events : undefined, tagOptions,
      purged: purged.length ? purged : undefined };
  }

  async getNotes(requests: NoteRequest[]): Promise<NotesResult> {
    this.check();
    this.fetches.push(requests);
    if (this.fetchGate) await this.fetchGate;
    const notes = [];
    const missing = [];
    for (const request of requests) {
      const note = this.notes.get(request.id);
      if (!note) {
        missing.push(request.id);
        continue;
      }
      const text = note.revisions[note.revisions.length - 1];
      const have = request.haveRevision ? note.revisions[request.haveRevision - 1] : undefined;
      const content = note.info.deleted ? undefined : have !== undefined ? computeSplice(have, text) : fullSplice(text);
      notes.push({ note: note.info, content });
    }
    return { notes, missing };
  }

  async push(_deviceId: string, { changes, metaChanges, tagOrder, tagOptions, since }: PushBatch): Promise<PushApiOutput> {
    this.check();
    this.pushCursors.push(since);
    if (changes.length > 0) this.pushes.push(changes);
    if (metaChanges.length > 0) this.metaPushes.push(metaChanges);
    if (tagOrder) this.tagOrderPushes.push(tagOrder);
    if (this.gate) await this.gate;
    const results = changes.map((change) => this.once(change.changeId, () => this.onPush?.(change) ?? this.fastForward(change)));
    // As the real server: made before the client knew of tags renamed or removed, they follow them.
    const rewritten = metaChanges.map((change) => this.tagEvents.filter((e) => e.seq > since).reduce((c, e) => ({
      ...c, addTags: retagged(c.addTags ?? [], e.from, e.to), removeTags: retagged(c.removeTags ?? [], e.from, e.to),
    }), change));
    const metaResults = rewritten.map((change) => this.once(change.changeId, () => this.applyMeta(change)));
    let options: { [tag: string]: TagOptions } | undefined;
    if (tagOptions) {
      const tags = { ...(this.tagOptions?.tags ?? {}) };
      for (const change of tagOptions) {
        const tag = this.tagEvents.filter((e) => e.seq > since).reduce<string | undefined>((t, e) => (t === e.from ? e.to : t), change.tag);
        if (tag === undefined) continue;
        if (change.options) tags[tag] = change.options;
        else delete tags[tag];
      }
      this.writeTagOptions(tags);
      options = tags;
    }
    return { results, metaResults, tagOrder: tagOrder ? this.applyTagOrder(tagOrder) : undefined, tagOptions: options };
  }

  /** Another device's tag options. */
  writeTagOptions(tags: { [tag: string]: TagOptions }) {
    this.seq++;
    this.tagOptions = { tags, seq: this.seq };
    this.wake();
  }

  /** As the real server: on every note, deleted ones included; then recorded, for the clients. */
  async renameTag(_deviceId: string, from: string, to: string): Promise<TagEditOutput> {
    this.check();
    return this.retag(from, to);
  }

  async deleteTag(_deviceId: string, tag: string): Promise<TagEditOutput> {
    this.check();
    return this.retag(tag, undefined);
  }

  private retag(from: string, to: string | undefined): TagEditOutput {
    const noteIds: string[] = [];
    for (const note of this.notes.values()) {
      if (!note.info.tags.includes(from)) continue;
      const tags = note.info.tags.filter((t) => t !== from);
      if (to !== undefined && !tags.includes(to)) tags.push(to);
      this.seq++;
      note.info = { ...note.info, seq: this.seq, tags };
      noteIds.push(note.info.id);
    }
    this.seq++;
    this.tagEvents.push({ from, to, seq: this.seq });
    this.wake();
    return { noteIds };
  }

  /** Notes deleted for good, at their seq. */
  readonly purges: { id: string; seq: number }[] = [];

  async trashedText(noteId: string): Promise<string> {
    this.check();
    const note = this.notes.get(noteId);
    if (!note?.info.deleted) throw new ApiError(ApiErrorKind.API, "No such note in the trash.", 404);
    return note.revisions[note.revisions.length - 1];
  }

  /** As the real server: deleted notes only. */
  async purge(noteIds: string[]): Promise<string[]> {
    this.check();
    const gone = noteIds.filter((id) => this.notes.get(id)?.info.deleted);
    if (gone.length) {
      this.seq++;
      for (const id of gone) {
        this.notes.delete(id);
        this.purges.push({ id, seq: this.seq });
      }
      this.wake();
    }
    return gone;
  }

  /** Another device's tag order. */
  writeTagOrder(tags: string[]) {
    this.seq++;
    this.tagOrder = { revision: (this.tagOrder?.revision ?? 0) + 1, tags, seq: this.seq };
    this.wake();
  }

  private applyTagOrder(change: TagOrderChange): TagOrderResult {
    const current = () => (this.tagOrder ? { revision: this.tagOrder.revision, tags: this.tagOrder.tags } : undefined);
    if (change.baseRevision !== (this.tagOrder?.revision ?? 0)) return { status: TagOrderStatus.STALE, order: current() };
    this.writeTagOrder([...new Set(change.tags)]);
    return { status: TagOrderStatus.APPLIED, order: current() };
  }

  /** Another device's tag and option change. */
  writeMeta(id: string, tags: string[], options: { [key: string]: string }): NoteInfo {
    const note = this.notes.get(id)!;
    this.seq++;
    note.info = { ...note.info, seq: this.seq, tags, options };
    // As the real server: once there's an order, a tag new to the user goes at its end.
    if (this.tagOrder) {
      const added = tags.filter((tag) => !this.tagOrder!.tags.includes(tag));
      if (added.length > 0) this.tagOrder = { revision: this.tagOrder.revision + 1, tags: [...this.tagOrder.tags, ...added], seq: this.seq };
    }
    this.wake();
    return note.info;
  }

  /** Another device's change. */
  write(id: string, text: string, deleted = false): NoteInfo {
    const note = this.notes.get(id);
    const revision = (note?.revisions.length ?? 0) + 1;
    this.seq++;
    const info: NoteInfo = {
      id, revision, seq: this.seq, createdAt: note?.info.createdAt ?? 1000, modifiedAt: 1000 + this.seq, deleted,
      title: text.split("\n")[0], size: text.length, contentHash: hash(text), tags: note?.info.tags ?? [], options: note?.info.options ?? {},
    };
    this.notes.set(id, { info, revisions: [...(note?.revisions ?? []), text] });
    this.wake();
    return info;
  }

  text(id: string): string | undefined {
    const note = this.notes.get(id);
    return note?.revisions[note.revisions.length - 1];
  }

  private once(changeId: string, apply: () => PushResult): PushResult {
    const replay = this.replays.get(changeId);
    if (replay) return replay;
    const result = apply();
    this.replays.set(changeId, result);
    return result;
  }

  private applyMeta(change: MetaChange): PushResult {
    const note = this.notes.get(change.noteId);
    if (!note) return { changeId: change.changeId, status: PushStatus.REJECTED, reason: RejectReason.UNKNOWN_NOTE, message: "No such note." };
    const tags = note.info.tags.filter((tag) => !change.removeTags?.includes(tag));
    for (const tag of change.addTags ?? []) if (!tags.includes(tag)) tags.push(tag);
    const options = { ...note.info.options };
    for (const key of change.unsetOptions ?? []) delete options[key];
    Object.assign(options, change.setOptions);
    return { changeId: change.changeId, status: PushStatus.APPLIED, note: this.writeMeta(change.noteId, tags, options) };
  }

  private wake() {
    const waiters = this.waiters;
    this.waiters = [];
    for (const wake of waiters) wake();
  }

  private fastForward(change: NoteChange): PushResult {
    const note = this.notes.get(change.noteId);
    const head = note?.info.revision ?? 0;
    if (change.baseRevision !== head) throw new Error("FakeServer only fast-forwards: base " + change.baseRevision + ", head " + head);
    const current = note ? note.revisions[head - 1] : "";
    if (change.deleted) return { changeId: change.changeId, status: PushStatus.APPLIED, note: this.write(change.noteId, current, true) };
    const text = change.content ?? applySplice(change.splice!, current);
    if (hash(text) !== change.contentHash) throw new Error("Hash mismatch for " + change.changeId);
    return { changeId: change.changeId, status: PushStatus.APPLIED, note: this.write(change.noteId, text) };
  }

  private check() {
    if (this.unauthorized) throw new ApiError(ApiErrorKind.API, "Session expired.", 401);
    if (this.outdated) throw new ApiError(ApiErrorKind.OUTDATED, "This page is out of date: reload it.", 409);
    if (this.failNext > 0) {
      this.failNext--;
      throw new ApiError(ApiErrorKind.UNREACHABLE, "Cannot reach the server.");
    }
  }
}
