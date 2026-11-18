package net.pieroxy.notes.storage;

/** Set on a conflict copy: the note it was split from, which a resolution commits to. */
public record ConflictOf(String noteId) {
}
