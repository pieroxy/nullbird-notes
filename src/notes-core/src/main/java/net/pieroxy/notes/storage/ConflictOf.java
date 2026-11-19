package net.pieroxy.notes.storage;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

/** Set on a conflict copy: the note it was split from, which a resolution commits to. */
@TypeScriptType
public record ConflictOf(@Required String noteId) {
}
