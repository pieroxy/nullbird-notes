import { ChangesPage, MetaChange, NoteChange, NoteRequest, NotesResult, PushApiOutput, TagEditOutput, TagOrderChange } from "../auto/api-types";
import { ApiEndpoints } from "../auto/ApiEndpoints";

/** What the sync engine needs from the server: an interface, so that tests can stand in for it. */
export interface SyncApi {
  maxNoteSize(signal: AbortSignal): Promise<number>;
  changes(since: number, wait: boolean, signal: AbortSignal): Promise<ChangesPage>;
  getNotes(notes: NoteRequest[], signal: AbortSignal): Promise<NotesResult>;
  /** {@code since}: the cursor, for the server to rewrite metadata changes made before tags were renamed (see TagEvent). */
  push(deviceId: string, changes: NoteChange[], metaChanges: MetaChange[], tagOrder: TagOrderChange | undefined, since: number, signal: AbortSignal): Promise<PushApiOutput>;
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

  push(deviceId: string, changes: NoteChange[], metaChanges: MetaChange[], tagOrder: TagOrderChange | undefined, since: number, signal: AbortSignal): Promise<PushApiOutput> {
    return ApiEndpoints.Push.call({ deviceId, changes, metaChanges, tagOrder, since }, { signal });
  }

  renameTag(deviceId: string, from: string, to: string, signal: AbortSignal): Promise<TagEditOutput> {
    return ApiEndpoints.RenameTag.call({ deviceId, from, to }, { signal });
  }

  deleteTag(deviceId: string, tag: string, signal: AbortSignal): Promise<TagEditOutput> {
    return ApiEndpoints.DeleteTag.call({ deviceId, tag }, { signal });
  }
}
