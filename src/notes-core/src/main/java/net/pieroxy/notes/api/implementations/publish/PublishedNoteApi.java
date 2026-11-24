package net.pieroxy.notes.api.implementations.publish;

import net.pieroxy.notes.api.metadata.AbstractApiEndpoint;
import net.pieroxy.notes.api.metadata.ApiContext;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.NotFoundException;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.NoteService;

/**
 * A published note, for anyone with its link. Unknown, unpublished, deleted, or its user removed:
 * the same 404, which tells nothing of what the link was.
 */
@Endpoint(method = ApiMethod.GET)
public class PublishedNoteApi extends AbstractApiEndpoint<PublishedNoteApiInput, PublishedNoteApiOutput> {
  private final IServiceProvider serviceProvider;

  public PublishedNoteApi(IServiceProvider serviceProvider) {
    this.serviceProvider = serviceProvider;
  }

  @Override
  public PublishedNoteApiOutput process(PublishedNoteApiInput input, ApiContext context) throws Exception {
    NoteService.PublishedNote note = serviceProvider.getNoteService().findPublished(input.getToken());
    if (note == null || serviceProvider.getSettingsService().getUser(note.username()) == null) {
      throw new NotFoundException("This note isn't published, or its link has changed.");
    }
    return new PublishedNoteApiOutput(note.content(), note.meta().modifiedAt(), note.markdown(), note.monospace(), note.nowrap());
  }
}

@TypeScriptType
class PublishedNoteApiInput {
  @Required
  private String token;

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }
}

@TypeScriptType
class PublishedNoteApiOutput {
  @Required
  private String text;
  /** When its text last changed, in epoch milliseconds. */
  @Required
  private long modifiedAt;
  @Required
  private boolean markdown;
  @Required
  private boolean monospace;
  @Required
  private boolean nowrap;

  public PublishedNoteApiOutput() {
  }

  public PublishedNoteApiOutput(String text, long modifiedAt, boolean markdown, boolean monospace, boolean nowrap) {
    this.text = text;
    this.modifiedAt = modifiedAt;
    this.markdown = markdown;
    this.monospace = monospace;
    this.nowrap = nowrap;
  }

  public String getText() {
    return text;
  }

  public long getModifiedAt() {
    return modifiedAt;
  }

  public boolean isMarkdown() {
    return markdown;
  }

  public boolean isMonospace() {
    return monospace;
  }

  public boolean isNowrap() {
    return nowrap;
  }
}
