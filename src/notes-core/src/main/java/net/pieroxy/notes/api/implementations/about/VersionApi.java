package net.pieroxy.notes.api.implementations.about;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.utils.BuildInfo;

/** Authenticated, so that a page reachable without logging in never tells which release is running. */
@Endpoint(method = ApiMethod.GET)
public class VersionApi extends AbstractAuthenticatedEndpoint<VersionApiInput, VersionApiOutput> {
  public VersionApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public VersionApiOutput processAuthenticated(VersionApiInput input) {
    return new VersionApiOutput(BuildInfo.VERSION, BuildInfo.REVISION);
  }
}

@TypeScriptType
class VersionApiInput extends AuthenticatedApiInput {
}

@TypeScriptType
class VersionApiOutput {
  @Required
  private String version;
  /** Short git revision of the build. */
  @Required
  private String revision;

  public VersionApiOutput() {
  }

  public VersionApiOutput(String version, String revision) {
    this.version = version;
    this.revision = revision;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public String getRevision() {
    return revision;
  }

  public void setRevision(String revision) {
    this.revision = revision;
  }
}
