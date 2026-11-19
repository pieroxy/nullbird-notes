import {
  ChangesPage, MetaChange, NoteChange, NoteInfo, NoteRequest, NotesResult, PushApiOutput, PushResult, PushStatus, RejectReason,
} from "../../../ts/auto/api-types";
import { ApiError, ApiErrorKind } from "../../../ts/utils/api/Api";
import { sha256Bytes } from "../../../ts/sync/Sha256";
import { applySplice, computeSplice, fullSplice } from "../../../ts/sync/Splice";
import { SyncApi } from "../../../ts/sync/SyncApi";

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
    return { storeId: this.storeId, seq: this.seq, notes, more: false };
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

  async push(_deviceId: string, changes: NoteChange[], metaChanges: MetaChange[]): Promise<PushApiOutput> {
    this.check();
    if (changes.length > 0) this.pushes.push(changes);
    if (metaChanges.length > 0) this.metaPushes.push(metaChanges);
    if (this.gate) await this.gate;
    const results = changes.map((change) => this.once(change.changeId, () => this.onPush?.(change) ?? this.fastForward(change)));
    const metaResults = metaChanges.map((change) => this.once(change.changeId, () => this.applyMeta(change)));
    return { results, metaResults };
  }

  /** Another device's tag and option change. */
  writeMeta(id: string, tags: string[], options: { [key: string]: string }): NoteInfo {
    const note = this.notes.get(id)!;
    this.seq++;
    note.info = { ...note.info, seq: this.seq, tags, options };
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
