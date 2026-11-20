package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.TypeScriptType;

@TypeScriptType
public enum RejectReason {
  INVALID,
  TOO_LARGE,
  /** The splice doesn't fit the base, or the hash doesn't match: resend the full content. */
  BASE_MISMATCH,
  /** The base revision was thinned out: resend with {@link NoteChange#baseContent()}, to merge with. */
  BASE_GONE,
  /** A metadata change to a note the server doesn't have. */
  UNKNOWN_NOTE,
}
