/**
 * The text size chosen in My Account: every font size in style.scss is multiplied by its scale,
 * through --text-scale. Kept per device, never synced: a phone and a desktop want their own.
 */
export interface TextSize {
  id: string;
  label: string;
  /** The body's text size, in CSS pixels, at this size: 15 at the default. */
  pixels: number;
}

export const TEXT_SIZES: TextSize[] = [
  { id: "small", label: "Small", pixels: 13 },
  { id: "normal", label: "Normal", pixels: 15 },
  { id: "large", label: "Large", pixels: 17 },
  { id: "larger", label: "Larger", pixels: 19 },
];

export const DEFAULT_TEXT_SIZE = "normal";

const STORAGE_KEY = "notes.textsize";

export function textSize(id: string): TextSize {
  return TEXT_SIZES.find((size) => size.id === id) ?? textSize(DEFAULT_TEXT_SIZE);
}

/** Storage can be unavailable (private browsing, blocked site data): then the default. */
export function loadTextSize(): string {
  try {
    return textSize(localStorage.getItem(STORAGE_KEY) ?? DEFAULT_TEXT_SIZE).id;
  } catch {
    return DEFAULT_TEXT_SIZE;
  }
}

/** Applies it at once; the default isn't stored. */
export function saveTextSize(id: string) {
  const size = textSize(id).id;
  try {
    if (size === DEFAULT_TEXT_SIZE) localStorage.removeItem(STORAGE_KEY);
    else localStorage.setItem(STORAGE_KEY, size);
  } catch {
    // See loadTextSize: it lasts the page.
  }
  applyTextSize(size);
}

/** Through the CSSOM: the CSP refuses inline styles, not this. */
export function applyTextSize(id: string) {
  const scale = textSize(id).pixels / textSize(DEFAULT_TEXT_SIZE).pixels;
  if (scale === 1) document.documentElement.style.removeProperty("--text-scale");
  else document.documentElement.style.setProperty("--text-scale", String(scale));
}

/** At startup; and when another tab of this app changes it, this one follows. */
export function initTextSize(onChange: () => void) {
  applyTextSize(loadTextSize());
  window.addEventListener("storage", (event) => {
    if (event.key !== STORAGE_KEY && event.key !== null) return;
    applyTextSize(loadTextSize());
    onChange();
  });
}
