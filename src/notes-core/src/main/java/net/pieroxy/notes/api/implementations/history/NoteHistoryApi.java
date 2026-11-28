package net.pieroxy.notes.api.implementations.history;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.InvalidInputException;
import net.pieroxy.notes.api.metadata.NotFoundException;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.storage.NoteHistory;
import net.pieroxy.notes.storage.UserNoteStore;

import java.util.List;

/** A note's history, as editing sessions (see NoteHistory), newest first. Each one's text: NoteVersionApi. */
@Endpoint(method = ApiMethod.POST)
public class NoteHistoryApi extends AbstractAuthenticatedEndpoint<NoteHistoryApiInput, NoteHistoryApiOutput> {
  public NoteHistoryApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public NoteHistoryApiOutput processAuthenticated(NoteHistoryApiInput input) throws Exception {
    if (!UserNoteStore.NOTE_ID_PATTERN.matcher(input.getNoteId()).matches()) throw new InvalidInputException("Invalid noteId.");
    List<NoteHistory.Entry> entries = serviceProvider.getNoteService().history(input.getUsername(), input.getNoteId());
    if (entries == null) throw new NotFoundException("No history yet: this note isn't on the server yet.");
    return new NoteHistoryApiOutput(entries.stream().map(e -> new HistoryEntry(e.revision(), e.from(), e.to(), e.saves(), e.kind())).toList());
  }
}

@TypeScriptType
class NoteHistoryApiInput extends AuthenticatedApiInput {
  @Required
  private String noteId;

  public String getNoteId() {
    return noteId;
  }

  public void setNoteId(String noteId) {
    this.noteId = noteId;
  }
}

/**
 * An editing session.
 *
 * @param revision the revision it left, for NoteVersionApi.
 * @param from     its first save, in epoch milliseconds.
 * @param to       its last save.
 * @param saves    how many saves are left of it.
 */
@TypeScriptType
record HistoryEntry(@Required int revision, @Required long from, @Required long to, @Required int saves,
                    @Required NoteHistory.HistoryKind kind) {
}

@TypeScriptType
record NoteHistoryApiOutput(@Required List<HistoryEntry> entries) {
}
