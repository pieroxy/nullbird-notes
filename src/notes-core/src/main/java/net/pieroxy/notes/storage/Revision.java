package net.pieroxy.notes.storage;

/** One accepted change to a note, immutable once written. A deleted note's revision keeps the content it had. */
public record Revision(int revision, long seq, long at, String changeId, String deviceId, boolean deleted, String content) {
}
