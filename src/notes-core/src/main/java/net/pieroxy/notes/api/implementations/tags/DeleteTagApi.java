package net.pieroxy.notes.api.implementations.tags;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;

/** Removes a tag from every note; the notes stay (see NoteService#deleteTag). Online only. */
@Endpoint(method = ApiMethod.POST)
public class DeleteTagApi extends AbstractAuthenticatedEndpoint<DeleteTagApiInput, TagEditOutput> {
  public DeleteTagApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public TagEditOutput processAuthenticated(DeleteTagApiInput input) throws Exception {
    return new TagEditOutput(serviceProvider.getNoteService().deleteTag(input.getUsername(), input.getDeviceId(), input.getTag()));
  }
}

@TypeScriptType
class DeleteTagApiInput extends AuthenticatedApiInput {
  /** Recorded with each note's change, as a push's. */
  private String deviceId;
  @Required
  private String tag;

  public String getDeviceId() {
    return deviceId;
  }

  public void setDeviceId(String deviceId) {
    this.deviceId = deviceId;
  }

  public String getTag() {
    return tag;
  }

  public void setTag(String tag) {
    this.tag = tag;
  }
}
