package net.pieroxy.notes.storage;

import java.util.List;
import java.util.Map;

/**
 * A note's current state without its content: note.json, and the in-memory index. Times are the
 * server's, in epoch milliseconds.
 *
 * @param revision          the content's revision: metadata changes leave it, and modifiedAt, alone.
 * @param seq               the user-wide sequence number of the note's last change: the sync cursor.
 * @param title             the first non-blank line, truncated.
 * @param size              content size in UTF-8 bytes.
 * @param contentHash       SHA-256 of the content, hex: detects a content.txt that doesn't match.
 * @param lastChangeId      the client-generated ID of the push that made the head revision.
 * @param conflictOf        null unless this note is an unresolved conflict copy.
 * @param tags              in the order they were added.
 * @param options           a key absent is the option's default.
 * @param metaRevision      the number of metadata changes: see {@link MetaRevision}.
 * @param lastMetaChangeId  the client-generated ID of the last metadata change.
 */
public record NoteMeta(String id, int revision, long seq, long createdAt, long modifiedAt, boolean deleted, String title,
                       long size, String contentHash, String lastChangeId, ConflictOf conflictOf, List<String> tags,
                       Map<String, String> options, int metaRevision, String lastMetaChangeId) {
  /** Empty, never null, collections: note.json files written before tags and options lack them. */
  public NoteMeta {
    tags = tags == null ? List.of() : List.copyOf(tags);
    options = options == null ? Map.of() : Map.copyOf(options);
  }
}
