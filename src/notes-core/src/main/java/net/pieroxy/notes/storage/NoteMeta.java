package net.pieroxy.notes.storage;

/**
 * A note's current state without its content: note.json, and the in-memory index. Times are the
 * server's, in epoch milliseconds.
 *
 * @param seq           the user-wide sequence number of the note's last change: the sync cursor.
 * @param title         the first non-blank line, truncated.
 * @param size          content size in UTF-8 bytes.
 * @param contentHash   SHA-256 of the content, hex: detects a content.txt that doesn't match.
 * @param lastChangeId  the client-generated ID of the push that made the head revision.
 * @param conflictOf    null unless this note is an unresolved conflict copy.
 */
public record NoteMeta(String id, int revision, long seq, long createdAt, long modifiedAt, boolean deleted, String title,
                       long size, String contentHash, String lastChangeId, ConflictOf conflictOf) {
}
