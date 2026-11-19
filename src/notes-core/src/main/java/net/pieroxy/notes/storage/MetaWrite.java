package net.pieroxy.notes.storage;

import java.util.List;
import java.util.Map;

/**
 * A change to a note's tags and options, as operations on their current values. Lists and maps
 * may be null for none.
 *
 * @param changeId  client-generated, unique per change: see {@link UserNoteStore#findMetaChange}.
 */
public record MetaWrite(String changeId, String deviceId, List<String> addTags, List<String> removeTags,
                        Map<String, String> setOptions, List<String> unsetOptions) {
}
