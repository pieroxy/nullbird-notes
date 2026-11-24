package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

/**
 * A tag's options replaced: the last change for a tag wins, whichever device it came from.
 *
 * @param options absent: back to none.
 */
@TypeScriptType
public record TagOptionsChange(@Required String tag, TagOptions options) {
}
