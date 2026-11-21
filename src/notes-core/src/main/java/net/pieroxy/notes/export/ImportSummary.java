package net.pieroxy.notes.export;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

import java.util.List;

/**
 * What an import would do, once the file is checked, or did, once run.
 *
 * @param token        to run or cancel it; absent once it ran.
 * @param fileName     as uploaded, for the user to recognize it.
 * @param notesInFile  every note of the file, trashed ones included.
 * @param imported     notes new to the user: to import, or imported.
 * @param trashed      among {@code imported}, those going to the trash, as in the file.
 * @param alreadyThere notes the user has already: by ID, or by identical content.
 * @param tooLarge     notes over the largest note size, skipped, in the file's order.
 * @param maxNoteSize  that size, in bytes.
 */
@TypeScriptType
public record ImportSummary(String token, @Required String fileName, @Required int notesInFile, @Required int imported,
                            @Required int trashed, @Required int alreadyThere, @Required List<TooLargeNote> tooLarge,
                            @Required long maxNoteSize) {

  /** @param title the note's first line, as the note list would show it. */
  @TypeScriptType
  public record TooLargeNote(@Required String title, @Required long size) {}
}
