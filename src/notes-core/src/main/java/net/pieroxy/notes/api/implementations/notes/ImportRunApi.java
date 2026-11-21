package net.pieroxy.notes.api.implementations.notes;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.export.ImportSummary;
import net.pieroxy.notes.services.IServiceProvider;

/** Runs the import the caller uploaded to /import (see ImportServlet), as its token says. */
@Endpoint(method = ApiMethod.POST)
public class ImportRunApi extends AbstractAuthenticatedEndpoint<ImportRunApiInput, ImportSummary> {

  public ImportRunApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public ImportSummary processAuthenticated(ImportRunApiInput input) throws Exception {
    return serviceProvider.getImportService().run(input.getUsername(), input.getClientAddress(), input.getToken());
  }
}

@TypeScriptType
class ImportRunApiInput extends AuthenticatedApiInput {
  @Required
  private String token;

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }
}
