package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

import java.util.List;
import java.util.Map;

/**
 * A change to a note's tags and options a client pushes, as operations: concurrent changes from
 * other devices all apply, the last one winning for the same tag or option. Removals apply before
 * additions. Never merged with, nor conflicting with, text changes.
 *
 * @param changeId  client-generated UUID, unique per change: a retry sends the same one.
 */
@TypeScriptType
public record MetaChange(@Required String changeId, @Required String noteId, List<String> addTags, List<String> removeTags,
                         Map<String, String> setOptions, List<String> unsetOptions) {
}
