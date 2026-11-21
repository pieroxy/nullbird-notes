package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

import java.util.List;

/**
 * A new tag order, replacing the one at {@code baseRevision} (0 for none yet). Refused as stale
 * if the order changed since: the client then replays its moves on the current one and sends again.
 */
@TypeScriptType
public record TagOrderChange(@Required int baseRevision, @Required List<String> tags) {
}
