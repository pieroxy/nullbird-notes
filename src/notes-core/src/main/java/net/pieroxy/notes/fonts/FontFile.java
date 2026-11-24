package net.pieroxy.notes.fonts;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

/**
 * One of a family's files, served at /fonts/{file}.
 *
 * @param file      named after its content's SHA-256, with its format's extension: a new content is a new name.
 * @param format    as in CSS: woff2, woff, truetype, opentype.
 * @param variable  its glyphs vary along axes; over weights from weightMin to weightMax when known
 *                  (not in a WOFF2: see {@link FontInspector}).
 */
@TypeScriptType
public record FontFile(@Required FontStyle style, @Required String file, @Required String format, @Required long size,
                       @Required boolean variable, Integer weightMin, Integer weightMax) {}
