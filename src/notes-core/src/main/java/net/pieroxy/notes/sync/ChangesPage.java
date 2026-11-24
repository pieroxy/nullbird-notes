package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

import java.util.List;
import java.util.Map;

/**
 * Notes changed after a cursor, in seq order.
 *
 * @param storeId  a client whose cursor came with another storeId must resync from scratch.
 * @param seq      the cursor for the next call.
 * @param more     another page follows right away.
 * @param tagOrder the tag order, if it changed after the cursor; absent otherwise.
 * @param tagEvents tags renamed or removed from every note after the cursor, oldest first; absent if none.
 * @param tagOptions every tag's options, if they changed after the cursor; absent otherwise.
 * @param purged   notes deleted for good after the cursor, to forget; absent if none.
 */
@TypeScriptType
public record ChangesPage(@Required String storeId, @Required long seq, @Required List<NoteInfo> notes, @Required boolean more,
                          TagOrder tagOrder, List<TagEvent> tagEvents, Map<String, TagOptions> tagOptions,
                          List<String> purged) {
}
