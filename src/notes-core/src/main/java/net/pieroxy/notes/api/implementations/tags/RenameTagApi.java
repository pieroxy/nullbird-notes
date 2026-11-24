package net.pieroxy.notes.api.implementations.tags;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;

/**
 * Renames a tag on every note, merging it into {@code to} if that's in use already (see
 * NoteService#renameTag). Online only: the notes changed reach every device with the next changes.
 */
@Endpoint(method = ApiMethod.POST)
public class RenameTagApi extends AbstractAuthenticatedEndpoint<RenameTagApiInput, TagEditOutput> {
  public RenameTagApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public TagEditOutput processAuthenticated(RenameTagApiInput input) throws Exception {
    return new TagEditOutput(serviceProvider.getNoteService().renameTag(input.getUsername(), input.getDeviceId(), input.getFrom(), input.getTo()));
  }
}

@TypeScriptType
class RenameTagApiInput extends AuthenticatedApiInput {
  /** Recorded with each note's change, as a push's. */
  private String deviceId;
  @Required
  private String from;
  @Required
  private String to;

  public String getDeviceId() {
    return deviceId;
  }

  public void setDeviceId(String deviceId) {
    this.deviceId = deviceId;
  }

  public String getFrom() {
    return from;
  }

  public void setFrom(String from) {
    this.from = from;
  }

  public String getTo() {
    return to;
  }

  public void setTo(String to) {
    this.to = to;
  }
}
