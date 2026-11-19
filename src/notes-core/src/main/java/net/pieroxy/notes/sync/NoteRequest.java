package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

/** @param haveRevision the revision the client holds, if any: the content then comes as a splice of it. */
@TypeScriptType
public record NoteRequest(@Required String id, Integer haveRevision) {
}
