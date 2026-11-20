package net.pieroxy.notes.storage;

/**
 * One accepted change to a note, immutable once written. A deleted note's revision keeps the content it had.
 *
 * @param submittedContent what the client sent, when the server stored something else (a merge);
 *                         null otherwise. The exact ancestor of edits the client made while its
 *                         push was in flight.
 */
public record Revision(int revision, long seq, long at, String changeId, String deviceId, boolean deleted, String content,
                       String submittedContent) {
}
