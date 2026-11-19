import { ChangesPage, MetaChange, NoteChange, NoteRequest, NotesResult, PushApiOutput } from "../auto/api-types";
import { ApiEndpoints } from "../auto/ApiEndpoints";

/** What the sync engine needs from the server: an interface, so that tests can stand in for it. */
export interface SyncApi {
  maxNoteSize(signal: AbortSignal): Promise<number>;
  changes(since: number, wait: boolean, signal: AbortSignal): Promise<ChangesPage>;
  getNotes(notes: NoteRequest[], signal: AbortSignal): Promise<NotesResult>;
  push(deviceId: string, changes: NoteChange[], metaChanges: MetaChange[], signal: AbortSignal): Promise<PushApiOutput>;
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

  push(deviceId: string, changes: NoteChange[], metaChanges: MetaChange[], signal: AbortSignal): Promise<PushApiOutput> {
    return ApiEndpoints.Push.call({ deviceId, changes, metaChanges }, { signal });
  }
}
