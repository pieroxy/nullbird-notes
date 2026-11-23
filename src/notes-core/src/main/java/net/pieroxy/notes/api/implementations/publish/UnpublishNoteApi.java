package net.pieroxy.notes.api.implementations.publish;

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

/** The note's public link stops working. */
@Endpoint(method = ApiMethod.POST)
public class UnpublishNoteApi extends AbstractAuthenticatedEndpoint<UnpublishNoteApiInput, UnpublishNoteApiOutput> {
  public UnpublishNoteApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public UnpublishNoteApiOutput processAuthenticated(UnpublishNoteApiInput input) throws Exception {
    if (!UserNoteStore.NOTE_ID_PATTERN.matcher(input.getNoteId()).matches()) throw new InvalidInputException("Invalid noteId.");
    if (!serviceProvider.getNoteService().unpublish(input.getUsername(), input.getNoteId())) throw new NotFoundException("No such note.");
    return new UnpublishNoteApiOutput();
  }
}

@TypeScriptType
class UnpublishNoteApiInput extends AuthenticatedApiInput {
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
class UnpublishNoteApiOutput {
}
