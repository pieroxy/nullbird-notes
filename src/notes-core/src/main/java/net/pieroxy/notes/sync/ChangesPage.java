package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

import java.util.List;

/**
 * Notes changed after a cursor, in seq order.
 *
 * @param storeId  a client whose cursor came with another storeId must resync from scratch.
 * @param seq      the cursor for the next call.
 * @param more     another page follows right away.
 * @param tagOrder the tag order, if it changed after the cursor; absent otherwise.
 */
@TypeScriptType
public record ChangesPage(@Required String storeId, @Required long seq, @Required List<NoteInfo> notes, @Required boolean more,
                          TagOrder tagOrder) {
}
