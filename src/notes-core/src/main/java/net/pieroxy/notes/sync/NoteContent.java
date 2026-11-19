package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

/**
 * @param content absent for a deleted note. A splice of the requested {@code haveRevision}, or
 *                with no prefix nor suffix (the full text) when that's smaller or impossible.
 */
@TypeScriptType
public record NoteContent(@Required NoteInfo note, Splice content) {
}
