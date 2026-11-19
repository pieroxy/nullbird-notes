import { ChangesPage, NoteChange, NoteRequest, NotesResult, PushResult } from "../auto/api-types";
import { ApiEndpoints } from "../auto/ApiEndpoints";

/** What the sync engine needs from the server: an interface, so that tests can stand in for it. */
export interface SyncApi {
  maxNoteSize(signal: AbortSignal): Promise<number>;
  changes(since: number, wait: boolean, signal: AbortSignal): Promise<ChangesPage>;
  getNotes(notes: NoteRequest[], signal: AbortSignal): Promise<NotesResult>;
  push(deviceId: string, changes: NoteChange[], signal: AbortSignal): Promise<PushResult[]>;
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

  async push(deviceId: string, changes: NoteChange[], signal: AbortSignal): Promise<PushResult[]> {
    return (await ApiEndpoints.Push.call({ deviceId, changes }, { signal })).results;
  }
}
