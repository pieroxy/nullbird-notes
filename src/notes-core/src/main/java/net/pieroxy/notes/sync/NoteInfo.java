package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.storage.ConflictOf;

import java.util.List;
import java.util.Map;

/**
 * A note's state as clients see it, without its content. Times are the server's, in epoch milliseconds.
 *
 * @param seq          the user-wide sequence number of the note's last change: the sync cursor.
 * @param size         content size in UTF-8 bytes.
 * @param contentHash  SHA-256 of the content, hex: clients check every text they rebuild against it.
 * @param revision     the content's revision: metadata changes leave it, and modifiedAt, alone.
 * @param conflictOf   absent unless the note is an unresolved conflict copy.
 * @param tags         in the order they were added.
 * @param options      a key absent is the option's default.
 */
@TypeScriptType
public record NoteInfo(@Required String id, @Required int revision, @Required long seq, @Required long createdAt,
                       @Required long modifiedAt, @Required boolean deleted, @Required String title, @Required long size,
                       @Required String contentHash, ConflictOf conflictOf, @Required List<String> tags,
                       @Required Map<String, String> options) {
}
