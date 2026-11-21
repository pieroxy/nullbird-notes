package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.TypeScriptType;

@TypeScriptType
public enum TagOrderStatus {
  /** Stored, or already the order. */
  APPLIED,
  /** The order changed since the change's base: nothing stored. */
  STALE,
  /** Nothing stored: see the message. */
  REJECTED,
}
