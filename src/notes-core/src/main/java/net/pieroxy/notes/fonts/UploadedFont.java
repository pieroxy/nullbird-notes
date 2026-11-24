package net.pieroxy.notes.fonts;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

/**
 * A font file just uploaded, not yet part of a family: what it says of itself, for the admin to
 * place it. Kept an hour unless a family takes it.
 *
 * @param weight          usWeightClass, when readable.
 * @param familyName      its family's name, when readable.
 * @param monospace       whether it says it's fixed-width, when readable.
 * @param suggestedStyle  from its weight and italic flag, or REGULAR when it doesn't say.
 */
@TypeScriptType
public record UploadedFont(@Required String file, @Required String format, @Required long size, @Required boolean variable,
                           Integer weightMin, Integer weightMax, Integer weight, Boolean italic, String familyName,
                           Boolean monospace, @Required FontStyle suggestedStyle) {}
