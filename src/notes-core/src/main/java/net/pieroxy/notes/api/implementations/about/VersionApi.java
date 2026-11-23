package net.pieroxy.notes.api.implementations.about;

import net.pieroxy.notes.api.metadata.AbstractApiEndpoint;
import net.pieroxy.notes.api.metadata.ApiContext;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.utils.BuildInfo;

/** Anonymous: the login page shows it, so an admin sees which release a deployment runs. */
@Endpoint(method = ApiMethod.GET)
public class VersionApi extends AbstractApiEndpoint<VersionApiInput, VersionApiOutput> {
  @Override
  public VersionApiOutput process(VersionApiInput input, ApiContext context) {
    return new VersionApiOutput(BuildInfo.VERSION, BuildInfo.REVISION);
  }
}

@TypeScriptType
class VersionApiInput {
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
