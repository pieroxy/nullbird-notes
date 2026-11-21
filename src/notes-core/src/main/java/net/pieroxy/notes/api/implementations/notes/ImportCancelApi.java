package net.pieroxy.notes.api.implementations.notes;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;

/** Drops the import the caller uploaded to /import, unless another file replaced it since. */
@Endpoint(method = ApiMethod.POST)
public class ImportCancelApi extends AbstractAuthenticatedEndpoint<ImportCancelApiInput, ImportCancelApiOutput> {

  public ImportCancelApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public ImportCancelApiOutput processAuthenticated(ImportCancelApiInput input) {
    serviceProvider.getImportService().cancel(input.getUsername(), input.getToken());
    return new ImportCancelApiOutput();
  }
}

@TypeScriptType
class ImportCancelApiInput extends AuthenticatedApiInput {
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
class ImportCancelApiOutput {
}
