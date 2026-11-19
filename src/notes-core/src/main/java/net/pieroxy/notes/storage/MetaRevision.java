package net.pieroxy.notes.storage;

import java.util.List;
import java.util.Map;

/** One accepted metadata change, immutable once written: what it did, not the resulting state. */
public record MetaRevision(int metaRevision, long seq, long at, String changeId, String deviceId, List<String> addTags,
                           List<String> removeTags, Map<String, String> setOptions, List<String> unsetOptions) {
}
