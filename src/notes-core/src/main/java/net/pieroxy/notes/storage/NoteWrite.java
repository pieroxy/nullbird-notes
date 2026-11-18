package net.pieroxy.notes.storage;

/**
 * What a commit writes as the note's next revision.
 *
 * @param changeId    client-generated, unique per push: see {@link UserNoteStore#findChange}.
 * @param conflictOf  replaces the note's current one: pass it on to keep it.
 */
public record NoteWrite(String content, boolean deleted, String changeId, String deviceId, ConflictOf conflictOf) {
}
