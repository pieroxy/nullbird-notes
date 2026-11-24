package net.pieroxy.notes.fonts;

import net.pieroxy.notes.api.metadata.TypeScriptType;

/** Which of a family's four styles a file is. */
@TypeScriptType
public enum FontStyle {
  REGULAR(false, false), BOLD(true, false), ITALIC(false, true), BOLD_ITALIC(true, true);

  public final boolean bold;
  public final boolean italic;

  FontStyle(boolean bold, boolean italic) {
    this.bold = bold;
    this.italic = italic;
  }

  /** What a file says of itself, when it does: bold from a weight of 600 up. */
  public static FontStyle of(Integer weight, Boolean italic) {
    boolean bold = weight != null && weight >= 600;
    boolean slanted = Boolean.TRUE.equals(italic);
    return bold ? (slanted ? BOLD_ITALIC : BOLD) : (slanted ? ITALIC : REGULAR);
  }
}
