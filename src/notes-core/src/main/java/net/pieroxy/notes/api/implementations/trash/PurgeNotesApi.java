package net.pieroxy.notes.api.implementations.trash;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;

import java.util.List;

/** Deletes notes in the trash for good, with their history (see NoteService#purge). Online only. */
@Endpoint(method = ApiMethod.POST)
public class PurgeNotesApi extends AbstractAuthenticatedEndpoint<PurgeNotesApiInput, PurgeNotesApiOutput> {
  public PurgeNotesApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public PurgeNotesApiOutput processAuthenticated(PurgeNotesApiInput input) throws Exception {
    return new PurgeNotesApiOutput(serviceProvider.getNoteService().purge(input.getUsername(), input.getNoteIds()));
  }
}

@TypeScriptType
class PurgeNotesApiInput extends AuthenticatedApiInput {
  @Required
  private List<String> noteIds;

  public List<String> getNoteIds() {
    return noteIds;
  }

  public void setNoteIds(List<String> noteIds) {
    this.noteIds = noteIds;
  }
}

/** @param noteIds those deleted for good: the others weren't deleted, or not yet. */
@TypeScriptType
record PurgeNotesApiOutput(@Required List<String> noteIds) {
}
