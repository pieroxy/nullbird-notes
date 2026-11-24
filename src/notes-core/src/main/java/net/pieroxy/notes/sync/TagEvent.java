package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

/**
 * A tag renamed (merged, when {@code to} was in use already) or removed from every note at once
 * (see NoteService#renameTag). Clients rewrite the changes they haven't sent yet that use
 * {@code from}; the server does the same with those sent by a client that didn't know of it yet.
 *
 * @param to absent: removed.
 */
@TypeScriptType
public record TagEvent(@Required String from, String to) {
}
