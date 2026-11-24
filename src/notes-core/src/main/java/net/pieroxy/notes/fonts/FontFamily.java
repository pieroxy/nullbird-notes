package net.pieroxy.notes.fonts;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

import java.util.List;

/**
 * A font the admin installed, which users choose for the interface, their notes, or fixed-width text
 * (the monospace ones only). Its ID never changes: devices keep it, not its name or files.
 *
 * @param files  one to four, each a different style; the browser makes up the styles missing.
 */
@TypeScriptType
public record FontFamily(@Required String id, @Required String name, @Required boolean monospace, @Required List<FontFile> files) {}
