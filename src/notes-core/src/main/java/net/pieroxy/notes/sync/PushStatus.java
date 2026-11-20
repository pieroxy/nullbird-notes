package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.TypeScriptType;

@TypeScriptType
public enum PushStatus {
  /** Stored as sent (or as a no-op: the note already had that text). */
  APPLIED,
  /** Merged with changes made elsewhere since the client's base (see Merge3 for clashes). */
  MERGED,
  /** A delete refused: the note was edited elsewhere since, and edits beat deletes. */
  KEPT,
  /** Nothing stored: see the reason. */
  REJECTED,
}
