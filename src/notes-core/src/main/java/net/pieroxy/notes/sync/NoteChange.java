package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

/**
 * One change a client pushes.
 *
 * @param changeId         client-generated UUID, unique per change: a retry sends the same one.
 * @param baseRevision     the server revision the client's text derives from; 0 for a new note.
 * @param basedOnChangeId  set when the client's text derives from what it sent in that change
 *                         instead (edits made while that push was in flight).
 * @param content          the full text; exclusive with {@code splice}.
 * @param splice           the text as a splice of the base text (see {@code basedOnChangeId}).
 * @param contentHash      SHA-256 of the text, hex; required unless {@code deleted}.
 * @param baseContent      the base text, in full: sent again with it after {@link RejectReason#BASE_GONE}.
 * @param deleted          a delete: content, splice and contentHash are ignored.
 */
@TypeScriptType
public record NoteChange(@Required String changeId, @Required String noteId, @Required int baseRevision, String basedOnChangeId,
                         String content, Splice splice, String contentHash, @Required boolean deleted, String baseContent) {
}
