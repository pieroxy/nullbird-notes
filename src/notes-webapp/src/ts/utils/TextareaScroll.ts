import { Range } from "./Search";

/** Copied from the textarea, so that the copy wraps its text the same way. */
const COPIED = [
  "boxSizing", "paddingTop", "paddingRight", "paddingBottom", "paddingLeft", "borderTopWidth", "borderRightWidth", "borderBottomWidth",
  "borderLeftWidth", "borderStyle", "fontFamily", "fontSize", "fontWeight", "fontStyle", "lineHeight", "letterSpacing", "wordSpacing",
  "tabSize", "whiteSpace", "overflowWrap", "wordBreak", "textTransform", "textIndent",
] as const;

/**
 * Scrolls {@code textarea} so that {@code range} is a third of the way down, and in view
 * horizontally. Browsers don't scroll a textarea to a selection set from code: the position is
 * measured in an invisible copy holding the text before the range.
 */
export function scrollToRange(textarea: HTMLTextAreaElement, range: Range) {
  const style = getComputedStyle(textarea);
  const copy = document.createElement("div");
  for (const property of COPIED) copy.style[property] = style[property];
  // A textarea always wraps long words; its white-space alone doesn't say so.
  if (style.whiteSpace !== "pre") copy.style.overflowWrap = "break-word";
  copy.style.position = "absolute";
  copy.style.visibility = "hidden";
  copy.style.top = "0";
  copy.style.left = "-100000px";
  copy.style.width = textarea.clientWidth + "px";
  copy.textContent = textarea.value.slice(0, range.start);
  const marker = document.createElement("span");
  marker.textContent = textarea.value.slice(range.start, range.end) || "​";
  copy.appendChild(marker);
  document.body.appendChild(copy);
  const top = marker.offsetTop;
  const left = marker.offsetLeft;
  copy.remove();
  textarea.scrollTop = Math.max(0, top - textarea.clientHeight / 3);
  if (left < textarea.scrollLeft || left > textarea.scrollLeft + textarea.clientWidth - 40) {
    textarea.scrollLeft = Math.max(0, left - textarea.clientWidth / 2);
  }
}
