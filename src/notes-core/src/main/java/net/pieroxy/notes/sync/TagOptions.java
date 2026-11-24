package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.TypeScriptType;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * A tag's options, as the user set them in its settings; synced across their devices. Absent is
 * unset, always.
 * <p>
 * How the tag looks: colors, each one of {@link #COLORS}: its dot, left stripe, background and text,
 * wherever it's shown.
 * <p>
 * For its notes, which follow it live: a note's own option, when set, wins; then its tags', the
 * first in the user's tag order (see {@link #effective}); then the default.
 *
 * @param notes    the stripe on its notes, in the note list: a color.
 * @param hidden   its notes show only when it's selected (an archive): true, or absent.
 * @param markdown its notes rendered as Markdown, or not.
 * @param monospace its notes in a fixed-width font, or not.
 * @param wrap     its notes' long lines wrapped, or not.
 * @param font     the font of its notes' text: an installed font's ID (see FontService), in place of the user's.
 * @param monoFont likewise, for its notes' fixed-width text.
 * @param sort     how the list is sorted while it's selected: one of {@link #SORTS}.
 * @param rendered its Markdown notes open rendered rather than in the editor: true, or absent.
 */
@TypeScriptType
public record TagOptions(String dot, String stripe, String background, String text, String notes, Boolean hidden,
                         Boolean markdown, Boolean monospace, Boolean wrap, String font, String monoFont, String sort, Boolean rendered) {
  /** The palette: each has a strong and a pale shade per theme, in the web app's stylesheet. */
  public static final Set<String> COLORS = Set.of("red", "orange", "yellow", "green", "teal", "blue", "purple", "pink");
  /** Last modified first (the default), last created first, by title. */
  public static final Set<String> SORTS = Set.of("modified", "created", "title");
  private static final Pattern FONT_ID = Pattern.compile("[0-9a-f]{16}");

  /** Only colors. */
  public static TagOptions colors(String dot, String stripe, String background, String text, String notes) {
    return new TagOptions(dot, stripe, background, text, notes, null, null, null, null, null, null, null, null);
  }

  /** @return whether every value is one this server knows. */
  public boolean isValid() {
    for (String color : new String[]{dot, stripe, background, text, notes}) {
      if (color != null && !COLORS.contains(color)) return false;
    }
    for (String id : new String[]{font, monoFont}) {
      if (id != null && !FONT_ID.matcher(id).matches()) return false;
    }
    return sort == null || SORTS.contains(sort);
  }

  /** @return the same options, flags absent rather than false: stored one way only. */
  public TagOptions normalized() {
    return new TagOptions(dot, stripe, background, text, notes, trueOrNull(hidden), markdown, monospace, wrap, font, monoFont, sort,
        trueOrNull(rendered));
  }

  private static Boolean trueOrNull(Boolean flag) {
    return Boolean.TRUE.equals(flag) ? Boolean.TRUE : null;
  }

  /** @return whether nothing is set: the same as no options. */
  public boolean isEmpty() {
    return dot == null && stripe == null && background == null && text == null && notes == null && !Boolean.TRUE.equals(hidden)
        && markdown == null && monospace == null && wrap == null && font == null && monoFont == null && sort == null
        && !Boolean.TRUE.equals(rendered);
  }

  /**
   * A note's boolean option as it applies: its own value ({@code "true"} or {@code "false"}) when it
   * has one; otherwise the first of its tags, in the user's order, to set it; otherwise off.
   *
   * @param ownValue the note's option, null if unset.
   * @param tagValues each of the note's tags' value for it, in the user's tag order; null for unset.
   */
  public static boolean effective(String ownValue, Iterable<Boolean> tagValues) {
    if ("true".equals(ownValue)) return true;
    if ("false".equals(ownValue)) return false;
    for (Boolean value : tagValues) if (value != null) return value;
    return false;
  }
}
