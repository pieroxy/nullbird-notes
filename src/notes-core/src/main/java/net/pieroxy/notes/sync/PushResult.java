package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

/**
 * The outcome of one {@link NoteChange}, or of one {@link MetaChange} (APPLIED or REJECTED, and
 * only note). Contents are splices of the text the client sent (its base text for a delete),
 * present only when the server stored something else.
 *
 * @param reason       present when REJECTED.
 * @param message      present when REJECTED: for humans.
 * @param note         the note after the change; absent when REJECTED, or for a delete of an unknown note.
 * @param content      the note's stored text, when it isn't what the client sent.
 * @param copy         present when CONFLICT: the new note holding the client's side.
 * @param copyContent  present when CONFLICT.
 */
@TypeScriptType
public record PushResult(@Required String changeId, @Required PushStatus status, RejectReason reason, String message,
                         NoteInfo note, Splice content, NoteInfo copy, Splice copyContent) {
}
