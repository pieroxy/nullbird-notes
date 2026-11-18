package net.pieroxy.notes.storage;

/** Where a push's change landed. */
public record ChangeRecord(String noteId, int revision) {
}
