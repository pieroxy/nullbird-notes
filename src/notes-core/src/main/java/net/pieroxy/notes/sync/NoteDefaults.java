package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.TypeScriptType;

/**
 * What a note gets when neither it nor its tags say otherwise (see {@link TagOptions#effective}), as
 * the user set it in their account; synced across their devices. Absent is the built-in default:
 * Markdown off, fixed-width font off, long lines wrapped. Notes follow it live, as they follow their
 * tags.
 *
 * @param markdown  notes rendered as Markdown, or not.
 * @param monospace notes in a fixed-width font, or not.
 * @param wrap      notes' long lines wrapped, or not.
 */
@TypeScriptType
public record NoteDefaults(Boolean markdown, Boolean monospace, Boolean wrap) {
  /** The built-in defaults. */
  public static final NoteDefaults NONE = new NoteDefaults(null, null, null);

  /** @return the same, a value equal to the built-in default absent: stored one way only. */
  public NoteDefaults normalized() {
    return new NoteDefaults(Boolean.TRUE.equals(markdown) ? Boolean.TRUE : null, Boolean.TRUE.equals(monospace) ? Boolean.TRUE : null,
        Boolean.FALSE.equals(wrap) ? Boolean.FALSE : null);
  }

  public boolean isMarkdown() {
    return Boolean.TRUE.equals(markdown);
  }

  public boolean isMonospace() {
    return Boolean.TRUE.equals(monospace);
  }

  /** The note option's way round: wrapping off is nowrap on. */
  public boolean isNowrap() {
    return Boolean.FALSE.equals(wrap);
  }
}
