package net.pieroxy.notes.api.implementations.sync;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;

/** The server's limits, for clients to check a note before pushing it. */
@Endpoint(method = ApiMethod.GET)
public class ServerInfoApi extends AbstractAuthenticatedEndpoint<ServerInfoApiInput, ServerInfoApiOutput> {
  public ServerInfoApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public ServerInfoApiOutput processAuthenticated(ServerInfoApiInput input) {
    return new ServerInfoApiOutput(serviceProvider.getSettingsService().getMaxNoteSize());
  }
}

@TypeScriptType
class ServerInfoApiInput extends AuthenticatedApiInput {
}

@TypeScriptType
class ServerInfoApiOutput {
  /** The largest note content, in UTF-8 bytes. */
  @Required
  private long maxNoteSize;

  public ServerInfoApiOutput() {
  }

  public ServerInfoApiOutput(long maxNoteSize) {
    this.maxNoteSize = maxNoteSize;
  }

  public long getMaxNoteSize() {
    return maxNoteSize;
  }

  public void setMaxNoteSize(long maxNoteSize) {
    this.maxNoteSize = maxNoteSize;
  }
}
