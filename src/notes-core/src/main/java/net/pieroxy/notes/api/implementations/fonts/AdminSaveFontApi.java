package net.pieroxy.notes.api.implementations.fonts;

import net.pieroxy.notes.api.metadata.AbstractAdminEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.InvalidInputException;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.fonts.FontStyle;
import net.pieroxy.notes.services.IServiceProvider;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Installs a font, or changes one: its name, whether it's fixed-width, and its files, uploaded to
 * /fonts/ before (see FontServlet). Answers with every font, as FontsApi does.
 */
@Endpoint(method = ApiMethod.POST)
public class AdminSaveFontApi extends AbstractAdminEndpoint<AdminSaveFontApiInput, FontsApiOutput> {

  public AdminSaveFontApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public FontsApiOutput processAdmin(AdminSaveFontApiInput input) throws Exception {
    Map<FontStyle, String> files = new EnumMap<>(FontStyle.class);
    for (AdminSaveFontApiInput.StyleFile file : input.getFiles()) {
      if (file.style == null) throw new InvalidInputException("Unknown style.");
      if (files.put(file.style, file.file) != null) throw new InvalidInputException("Two files for the same style.");
    }
    return new FontsApiOutput(serviceProvider.getFontService().save(input.getUsername(), input.getClientAddress(), input.getId(),
        input.getName(), input.isMonospace(), files));
  }
}

@TypeScriptType
class AdminSaveFontApiInput extends AuthenticatedApiInput {
  /** Absent: a new font. */
  private String id;
  @Required
  private String name;
  @Required
  private boolean monospace;
  @Required
  private List<StyleFile> files;

  @TypeScriptType
  static class StyleFile {
    @Required
    FontStyle style;
    /** As /fonts/ named it when uploaded. */
    @Required
    String file;
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public boolean isMonospace() {
    return monospace;
  }

  public List<StyleFile> getFiles() {
    return files;
  }
}
