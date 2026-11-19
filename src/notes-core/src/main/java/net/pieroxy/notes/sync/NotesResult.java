package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

import java.util.List;

/** @param missing the requested IDs that match no note. */
@TypeScriptType
public record NotesResult(@Required List<NoteContent> notes, @Required List<String> missing) {
}
