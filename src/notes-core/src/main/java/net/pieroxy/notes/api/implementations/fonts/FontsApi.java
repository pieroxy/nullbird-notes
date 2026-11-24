package net.pieroxy.notes.api.implementations.fonts;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.fonts.FontFamily;
import net.pieroxy.notes.services.IServiceProvider;

import java.util.List;

/** The fonts the admin installed, for any user to choose from (see FontService). */
@Endpoint(method = ApiMethod.POST)
public class FontsApi extends AbstractAuthenticatedEndpoint<FontsApiInput, FontsApiOutput> {

  public FontsApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public FontsApiOutput processAuthenticated(FontsApiInput input) {
    return new FontsApiOutput(serviceProvider.getFontService().list());
  }
}

@TypeScriptType
class FontsApiInput extends AuthenticatedApiInput {
}

/** @param fonts  by name. */
@TypeScriptType
record FontsApiOutput(@Required List<FontFamily> fonts) {}
