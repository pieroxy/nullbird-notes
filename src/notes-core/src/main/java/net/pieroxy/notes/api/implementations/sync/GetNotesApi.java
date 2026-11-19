package net.pieroxy.notes.api.implementations.sync;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.sync.NoteRequest;
import net.pieroxy.notes.sync.NotesResult;

import java.util.List;

/** Notes' contents, as splices of the revisions the client already has. POST: the list can outgrow a URL. */
@Endpoint(method = ApiMethod.POST)
public class GetNotesApi extends AbstractAuthenticatedEndpoint<GetNotesApiInput, NotesResult> {
  public GetNotesApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public NotesResult processAuthenticated(GetNotesApiInput input) throws Exception {
    return serviceProvider.getNoteService().getNotes(input.getUsername(), input.getNotes());
  }
}

@TypeScriptType
class GetNotesApiInput extends AuthenticatedApiInput {
  @Required
  private List<NoteRequest> notes;

  public List<NoteRequest> getNotes() {
    return notes;
  }

  public void setNotes(List<NoteRequest> notes) {
    this.notes = notes;
  }
}
