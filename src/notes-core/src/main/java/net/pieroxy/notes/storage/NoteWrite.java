package net.pieroxy.notes.storage;

import java.util.List;
import java.util.Map;

/**
 * What a commit writes as the note's next revision.
 *
 * @param changeId          client-generated, unique per push: see {@link UserNoteStore#findChange}.
 * @param submittedContent  see {@link Revision#submittedContent}; ignored when equal to {@code content}.
 * @param tags              for a new note; an existing one keeps its own. Null for none.
 * @param options           as {@code tags}.
 */
public record NoteWrite(String content, boolean deleted, String changeId, String deviceId, String submittedContent,
                        List<String> tags, Map<String, String> options) {
  public NoteWrite(String content, boolean deleted, String changeId, String deviceId, String submittedContent) {
    this(content, deleted, changeId, deviceId, submittedContent, null, null);
  }
}
