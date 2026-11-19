import { NoteInfo } from "../auto/api-types";

/** A note as this device holds it: what the user sees, and what sync needs to reconcile it. */
export interface LocalNote {
  id: string;
  /** The server's metadata as last known; absent until the note first reaches the server. */
  info?: NoteInfo;
  /** The server revision {@link baseText} comes from; 0 for a note the server doesn't know yet. */
  baseRevision: number;
  /** The text {@link text} derives from: a push sends a splice of it. */
  baseText: string;
  /** Set when {@link baseText} is what this device sent in that change, not a server revision's text. */
  basedOnChangeId?: string;
  /** What the user sees and edits. */
  text: string;
  deleted: boolean;
  /** {@link text} or {@link deleted} hasn't reached the server yet. */
  dirty: boolean;
  /** Local time of the last edit: sorts the note until the server's modifiedAt reflects it. */
  editedAt: number;
  /** A push in flight. Saved before sending, so that a retry reuses the same changeId. */
  pending?: PendingChange;
  /** Why the server refused the last push: not retried until the note is edited again. */
  error?: NoteError;
  /** The server couldn't rebuild a splice: send the full text next time. */
  forceFull?: boolean;
}

export interface PendingChange {
  changeId: string;
  /** The text sent; for a delete, {@link LocalNote.baseText}. */
  submitted: string;
  deleted: boolean;
}

export interface NoteError {
  reason: string;
  message: string;
}
