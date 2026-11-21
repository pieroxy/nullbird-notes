package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

/**
 * What became of a {@link TagOrderChange}.
 *
 * @param message why it was rejected; absent otherwise.
 * @param order   the order now, whatever the status; absent if there's none.
 */
@TypeScriptType
public record TagOrderResult(@Required TagOrderStatus status, String message, TagOrder order) {
}
