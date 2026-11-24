package net.pieroxy.notes.api.implementations.fonts;

import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;

/**
 * Uninstalls a font. Devices that use it keep their copy until they're next online, then go back
 * to the default font. Answers with every font left, as FontsApi does.
 */
@Endpoint(method = ApiMethod.POST)
public class AdminDeleteFontApi extends AbstractAdminEndpoint<AdminDeleteFontApiInput, FontsApiOutput> {

  public AdminDeleteFontApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public FontsApiOutput processAdmin(AdminDeleteFontApiInput input) throws Exception {
    return new FontsApiOutput(serviceProvider.getFontService().delete(input.getUsername(), input.getClientAddress(), input.getId()));
  }
}

@TypeScriptType
class AdminDeleteFontApiInput extends AuthenticatedApiInput {
  @Required
  private String id;

  public String getId() {
    return id;
  }
}
