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

/** Gives a note a public link, or keeps the one it has unless {@code newLink}. */
@Endpoint(method = ApiMethod.POST)
public class PublishNoteApi extends AbstractAuthenticatedEndpoint<PublishNoteApiInput, PublishNoteApiOutput> {
  public PublishNoteApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public PublishNoteApiOutput processAuthenticated(PublishNoteApiInput input) throws Exception {
    if (!UserNoteStore.NOTE_ID_PATTERN.matcher(input.getNoteId()).matches()) throw new InvalidInputException("Invalid noteId.");
    String token = serviceProvider.getNoteService().publish(input.getUsername(), input.getNoteId(), input.isNewLink());
    if (token == null) throw new NotFoundException("No such note: it may not be synced yet.");
    return new PublishNoteApiOutput(token);
  }
}

@TypeScriptType
class PublishNoteApiInput extends AuthenticatedApiInput {
  @Required
  private String noteId;
  /** Replaces its link: the one given out stops working. */
  @Required
  private boolean newLink;

  public String getNoteId() {
    return noteId;
  }

  public void setNoteId(String noteId) {
    this.noteId = noteId;
  }

  public boolean isNewLink() {
    return newLink;
  }

  public void setNewLink(boolean newLink) {
    this.newLink = newLink;
  }
}

@TypeScriptType
class PublishNoteApiOutput {
  /** The link is /p/{token}. */
  @Required
  private String token;

  public PublishNoteApiOutput() {
  }

  public PublishNoteApiOutput(String token) {
    this.token = token;
  }

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }
}
