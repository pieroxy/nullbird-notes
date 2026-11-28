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
import net.pieroxy.notes.services.NoteService;
import net.pieroxy.notes.storage.UserNoteStore;

/**
 * A version of a note from its history (see NoteHistoryApi), and the one it's compared with: the
 * client shows what changed between them.
 */
@Endpoint(method = ApiMethod.POST)
public class NoteVersionApi extends AbstractAuthenticatedEndpoint<NoteVersionApiInput, NoteVersionApiOutput> {
  public NoteVersionApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public NoteVersionApiOutput processAuthenticated(NoteVersionApiInput input) throws Exception {
    if (!UserNoteStore.NOTE_ID_PATTERN.matcher(input.getNoteId()).matches()) throw new InvalidInputException("Invalid noteId.");
    NoteService notes = serviceProvider.getNoteService();
    String text = notes.revisionText(input.getUsername(), input.getNoteId(), input.getRevision());
    if (text == null) throw new NotFoundException("This version isn't kept anymore.");
    String baseText = null;
    if (input.getBase() != null) {
      baseText = notes.revisionText(input.getUsername(), input.getNoteId(), input.getBase());
      if (baseText == null) throw new NotFoundException("The version before isn't kept anymore.");
    }
    return new NoteVersionApiOutput(text, baseText);
  }
}

@TypeScriptType
class NoteVersionApiInput extends AuthenticatedApiInput {
  @Required
  private String noteId;
  @Required
  private int revision;
  /** The version to compare it with; none: compared with nothing. */
  private Integer base;

  public String getNoteId() {
    return noteId;
  }

  public void setNoteId(String noteId) {
    this.noteId = noteId;
  }

  public int getRevision() {
    return revision;
  }

  public void setRevision(int revision) {
    this.revision = revision;
  }

  public Integer getBase() {
    return base;
  }

  public void setBase(Integer base) {
    this.base = base;
  }
}

/** @param baseText the text of the version asked as base; absent if none was. */
@TypeScriptType
record NoteVersionApiOutput(@Required String text, String baseText) {
}
