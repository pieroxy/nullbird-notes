package net.pieroxy.notes.api.implementations.trash;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.InvalidInputException;
import net.pieroxy.notes.api.metadata.NotFoundException;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.storage.UserNoteStore;

/** A deleted note's text, for the trash on a device that never had it (see NoteService#trashedText). */
@Endpoint(method = ApiMethod.POST)
public class TrashedNoteApi extends AbstractAuthenticatedEndpoint<TrashedNoteApiInput, TrashedNoteApiOutput> {
  public TrashedNoteApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public TrashedNoteApiOutput processAuthenticated(TrashedNoteApiInput input) throws Exception {
    if (!UserNoteStore.NOTE_ID_PATTERN.matcher(input.getNoteId()).matches()) throw new InvalidInputException("Invalid noteId.");
    String text = serviceProvider.getNoteService().trashedText(input.getUsername(), input.getNoteId());
    if (text == null) throw new NotFoundException("No such note in the trash.");
    return new TrashedNoteApiOutput(text);
  }
}

@TypeScriptType
class TrashedNoteApiInput extends AuthenticatedApiInput {
  @Required
  private String noteId;

  public String getNoteId() {
    return noteId;
  }

  public void setNoteId(String noteId) {
    this.noteId = noteId;
  }
}

@TypeScriptType
record TrashedNoteApiOutput(@Required String text) {
}
