package net.pieroxy.notes.api.implementations.tags;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

import java.util.List;

/** @param noteIds the notes changed, deleted ones included. */
@TypeScriptType
public record TagEditOutput(@Required List<String> noteIds) {
}
