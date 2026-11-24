import { ChangesPage, MetaChange, NoteChange, NoteRequest, NotesResult, PushApiOutput, TagEditOutput, TagOptionsChange, TagOrderChange } from "../auto/api-types";

/** What a push sends. */
export interface PushBatch {
  changes: NoteChange[];
  metaChanges: MetaChange[];
  tagOrder?: TagOrderChange;
  tagOptions?: TagOptionsChange[];
  /** The cursor, for the server to rewrite changes made before tags were renamed (see TagEvent). */
  since: number;
}
import { ApiEndpoints } from "../auto/ApiEndpoints";

/** What the sync engine needs from the server: an interface, so that tests can stand in for it. */
export interface SyncApi {
  maxNoteSize(signal: AbortSignal): Promise<number>;
  changes(since: number, wait: boolean, signal: AbortSignal): Promise<ChangesPage>;
  getNotes(notes: NoteRequest[], signal: AbortSignal): Promise<NotesResult>;
  push(deviceId: string, batch: PushBatch, signal: AbortSignal): Promise<PushApiOutput>;
  renameTag(deviceId: string, from: string, to: string, signal: AbortSignal): Promise<TagEditOutput>;
  deleteTag(deviceId: string, tag: string, signal: AbortSignal): Promise<TagEditOutput>;
}

export class HttpSyncApi implements SyncApi {
  async maxNoteSize(signal: AbortSignal): Promise<number> {
    return (await ApiEndpoints.ServerInfo.call({}, { signal })).maxNoteSize;
  }

  changes(since: number, wait: boolean, signal: AbortSignal): Promise<ChangesPage> {
    return ApiEndpoints.Changes.call({ since, wait }, { signal });
  }

  getNotes(notes: NoteRequest[], signal: AbortSignal): Promise<NotesResult> {
    return ApiEndpoints.GetNotes.call({ notes }, { signal });
  }

  push(deviceId: string, batch: PushBatch, signal: AbortSignal): Promise<PushApiOutput> {
    return ApiEndpoints.Push.call({ deviceId, ...batch }, { signal });
  }

  renameTag(deviceId: string, from: string, to: string, signal: AbortSignal): Promise<TagEditOutput> {
    return ApiEndpoints.RenameTag.call({ deviceId, from, to }, { signal });
  }

  deleteTag(deviceId: string, tag: string, signal: AbortSignal): Promise<TagEditOutput> {
    return ApiEndpoints.DeleteTag.call({ deviceId, tag }, { signal });
  }
}
