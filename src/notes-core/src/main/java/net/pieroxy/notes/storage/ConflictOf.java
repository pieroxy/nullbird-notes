package net.pieroxy.notes.storage;

/**
 * Set on a conflict copy: the note it was split from, and the revisions a 3-way resolution needs —
 * the common ancestor ({@code baseRevision}) and the original's head when the copy was made.
 */
public record ConflictOf(String noteId, int baseRevision, int headRevision) {
}
