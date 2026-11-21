package net.pieroxy.notes.storage;

import java.util.List;
import java.util.Map;

/**
 * The note options the app knows (see {@link NoteMeta#options}), all of them boolean: on when set
 * to {@link #ON}, off when absent. The web app has the same names (Metadata.ts). Other options are
 * stored as they come, without the server knowing them.
 * <p>
 * The export writes each as a field of the note, as Simplenote's {@code pinned} and
 * {@code markdown}; the import reads them back (see SimplenoteExport, NotesFile).
 */
public final class NoteOptions {
  /** Shown at the top of the list. */
  public static final String PINNED = "pinned";
  /** Its text rendered as Markdown. */
  public static final String MARKDOWN = "markdown";
  public static final String MONOSPACE = "monospace";
  public static final String NOWRAP = "nowrap";
  /** In the order the export writes them. */
  public static final List<String> BOOLEANS = List.of(PINNED, MARKDOWN, MONOSPACE, NOWRAP);
  public static final String ON = "true";

  private NoteOptions() {}

  public static boolean isOn(Map<String, String> options, String key) {
    return ON.equals(options.get(key));
  }
}
