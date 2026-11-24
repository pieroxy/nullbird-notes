package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.TypeScriptType;

import java.util.Set;

/**
 * How a tag looks, as the user set it in its settings; synced across their devices. Each a color of
 * {@link #COLORS}, absent for none: the tag's dot, left stripe, background and text, wherever it's
 * shown; and the stripe on its notes, in the note list. More options come here (hidden…).
 */
@TypeScriptType
public record TagOptions(String dot, String stripe, String background, String text, String notes) {
  /** The palette: each has a strong and a pale shade per theme, in the web app's stylesheet. */
  public static final Set<String> COLORS = Set.of("red", "orange", "yellow", "green", "teal", "blue", "purple", "pink");

  /** @return whether every color is one of {@link #COLORS}. */
  public boolean isValid() {
    for (String color : new String[]{dot, stripe, background, text, notes}) {
      if (color != null && !COLORS.contains(color)) return false;
    }
    return true;
  }

  /** @return whether nothing is set: the same as no options. */
  public boolean isEmpty() {
    return dot == null && stripe == null && background == null && text == null && notes == null;
  }
}
