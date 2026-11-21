package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

import java.util.List;

/**
 * The order the user gave their tags, as clients see it. There is none until they first reorder
 * them: tags are then shown alphabetically.
 *
 * @param revision bumped at each change: a change sent with an older one is refused (see TagOrderChange).
 * @param tags     every tag in use, in order; new ones are added at the end.
 */
@TypeScriptType
public record TagOrder(@Required int revision, @Required List<String> tags) {
}
