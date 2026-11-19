import { Splice } from "../auto/api-types";

/**
 * The server's Splice, on the client side: lengths count UTF-16 code units (JavaScript's and Java's
 * string indices alike) and never split a surrogate pair. Must stay identical to Splice.java.
 */
export function computeSplice(from: string, to: string): Splice {
  const max = Math.min(from.length, to.length);
  let prefix = 0;
  while (prefix < max && from.charCodeAt(prefix) === to.charCodeAt(prefix)) prefix++;
  if (prefix > 0 && isHighSurrogate(from.charCodeAt(prefix - 1))) prefix--;

  let suffix = 0;
  const maxSuffix = max - prefix;
  while (suffix < maxSuffix && from.charCodeAt(from.length - 1 - suffix) === to.charCodeAt(to.length - 1 - suffix)) suffix++;
  if (suffix > 0 && isLowSurrogate(from.charCodeAt(from.length - suffix))) suffix--;

  return { prefixLength: prefix, suffixLength: suffix, middle: to.substring(prefix, to.length - suffix) };
}

/** @throws Error if the splice doesn't fit {@code base}. */
export function applySplice(splice: Splice, base: string): string {
  if (splice.prefixLength < 0 || splice.suffixLength < 0 || splice.prefixLength + splice.suffixLength > base.length) {
    throw new Error("Splice (" + splice.prefixLength + ", " + splice.suffixLength + ") doesn't fit a base of length " + base.length);
  }
  return base.substring(0, splice.prefixLength) + splice.middle + base.substring(base.length - splice.suffixLength);
}

/** The whole text as a splice: what the server sends when there's no usable base. */
export function fullSplice(text: string): Splice {
  return { prefixLength: 0, suffixLength: 0, middle: text };
}

function isHighSurrogate(code: number): boolean {
  return code >= 0xd800 && code <= 0xdbff;
}

function isLowSurrogate(code: number): boolean {
  return code >= 0xdc00 && code <= 0xdfff;
}
