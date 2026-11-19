package net.pieroxy.notes.sync;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

/**
 * A text change as one splice: keep the first {@code prefixLength} and last {@code suffixLength}
 * characters of the base, replace what's between with {@code middle}. Lengths count UTF-16 code
 * units (Java's and JavaScript's string indices) and never split a surrogate pair. Computed from
 * the common prefix and suffix: one tiny splice for any edit made in a single place.
 */
@TypeScriptType
public record Splice(@Required int prefixLength, @Required int suffixLength, @Required String middle) {

  /** @return the splice turning {@code from} into {@code to}. */
  public static Splice compute(String from, String to) {
    int max = Math.min(from.length(), to.length());
    int prefix = 0;
    while (prefix < max && from.charAt(prefix) == to.charAt(prefix)) prefix++;
    if (prefix > 0 && Character.isHighSurrogate(from.charAt(prefix - 1))) prefix--;

    int suffix = 0;
    int maxSuffix = max - prefix;
    while (suffix < maxSuffix && from.charAt(from.length() - 1 - suffix) == to.charAt(to.length() - 1 - suffix)) suffix++;
    if (suffix > 0 && Character.isLowSurrogate(from.charAt(from.length() - suffix))) suffix--;

    return new Splice(prefix, suffix, to.substring(prefix, to.length() - suffix));
  }

  /** @throws IllegalArgumentException if this splice doesn't fit {@code base}. */
  public String apply(String base) {
    if (prefixLength < 0 || suffixLength < 0 || middle == null || prefixLength + suffixLength > base.length()) {
      throw new IllegalArgumentException("Splice (" + prefixLength + ", " + suffixLength + ") doesn't fit a base of length " + base.length());
    }
    return base.substring(0, prefixLength) + middle + base.substring(base.length() - suffixLength);
  }
}
