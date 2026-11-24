import { FontFamily, FontFile, FontStyle } from "../auto/api-types";
import { ApiEndpoints } from "../auto/ApiEndpoints";

/**
 * The fonts chosen in My Account among those the admin installed (see FontService): one for the
 * interface, one for the notes, one for fixed-width text. Kept per device, as the rest of the
 * appearance, and so are their files, to work offline: in the Cache Storage, from which the service
 * worker serves them (see sw.js), and only while chosen.
 * <p>
 * A choice keeps what's needed to apply it (the font's name and files), so that starting needs no
 * server; it's checked against the installed fonts when the server is reachable (see refreshFonts).
 * Each sets a CSS variable that style.scss uses: --font-ui, --font-note, --font-mono; the default
 * fonts follow in it, for a font that doesn't load.
 */
export type FontSlot = "ui" | "note" | "mono";

export const FONT_SLOTS: { slot: FontSlot; label: string; monospaceOnly: boolean }[] = [
  { slot: "ui", label: "Interface", monospaceOnly: false },
  { slot: "note", label: "Notes", monospaceOnly: false },
  { slot: "mono", label: "Fixed-width text", monospaceOnly: true },
];

export const FONT_STYLES: FontStyle[] = [FontStyle.REGULAR, FontStyle.BOLD, FontStyle.ITALIC, FontStyle.BOLD_ITALIC];

export function styleLabel(style: FontStyle): string {
  return { [FontStyle.REGULAR]: "Regular", [FontStyle.BOLD]: "Bold", [FontStyle.ITALIC]: "Italic", [FontStyle.BOLD_ITALIC]: "Bold italic" }[style];
}

/**
 * The style a file's name says, as font files are usually named: "Inter-BoldItalic.woff2",
 * "UbuntuMono-BI.ttf". Undefined if it doesn't say: the file may, then (see UploadedFont).
 */
export function styleFromFileName(name: string): FontStyle | undefined {
  const base = name.replace(/\.[a-z0-9]+$/i, "");
  const words = base.toLowerCase();
  const suffix = /[-_ ](r|b|i|ri|bi)$/i.exec(base)?.[1].toLowerCase();
  const bold = /bold|black|heavy/.test(words) || suffix === "b" || suffix === "bi";
  const italic = /italic|oblique/.test(words) || suffix === "i" || suffix === "ri" || suffix === "bi";
  const regular = /regular|book|normal/.test(words) || suffix === "r";
  if (bold) return italic ? FontStyle.BOLD_ITALIC : FontStyle.BOLD;
  if (italic) return FontStyle.ITALIC;
  return regular ? FontStyle.REGULAR : undefined;
}

/** A FontFace's source and descriptors. */
export interface Face {
  url: string;
  weight: string;
  style: "normal" | "italic";
}

export interface FontChoice {
  id: string;
  name: string;
  faces: Face[];
}

/** As sw.js names it. */
export const FONT_CACHE = "notes-fonts";
const STORAGE_PREFIX = "notes.font.";
/** What follows a chosen font in its variable: style.scss's defaults. */
const FALLBACK: Record<FontSlot, string> = { ui: "var(--font-ui-default)", note: "var(--font-ui)", mono: "var(--font-mono-default)" };

/**
 * A file's descriptors. A static file covers its style's weight; a variable one, the weights it
 * varies over: all of them, as far as the browser goes, when they aren't known (a WOFF2's).
 */
export function face(file: FontFile): Face {
  const bold = file.style === FontStyle.BOLD || file.style === FontStyle.BOLD_ITALIC;
  const italic = file.style === FontStyle.ITALIC || file.style === FontStyle.BOLD_ITALIC;
  const weight = !file.variable ? (bold ? "700" : "400")
    : file.weightMin !== undefined && file.weightMax !== undefined ? file.weightMin + " " + file.weightMax : "1 1000";
  return { url: "/fonts/" + file.file, weight, style: italic ? "italic" : "normal" };
}

export function choiceOf(family: FontFamily): FontChoice {
  return { id: family.id, name: family.name, faces: family.files.map(face) };
}

/** The name the font is registered under: its ID, never its name, which another font may take. */
export function familyName(id: string): string {
  return "nbfont-" + id;
}

/** The CSS variable's value with this choice: the font, then the defaults. */
export function cssValue(slot: FontSlot, choice: FontChoice | undefined): string | undefined {
  return choice ? '"' + familyName(choice.id) + '", ' + FALLBACK[slot] : undefined;
}

function isFace(value: unknown): value is Face {
  const f = value as Face;
  return typeof f?.url === "string" && f.url.startsWith("/fonts/") && typeof f.weight === "string" && (f.style === "normal" || f.style === "italic");
}

/** Storage can be unavailable (private browsing, blocked site data): then none. */
export function loadFont(slot: FontSlot): FontChoice | undefined {
  try {
    const stored = JSON.parse(localStorage.getItem(STORAGE_PREFIX + slot) ?? "null");
    if (typeof stored?.id === "string" && typeof stored.name === "string" && Array.isArray(stored.faces) && stored.faces.every(isFace)) {
      return { id: stored.id, name: stored.name, faces: stored.faces };
    }
  } catch {
    // Unavailable or garbled: none.
  }
  return undefined;
}

function storeFont(slot: FontSlot, choice: FontChoice | undefined) {
  try {
    if (choice) localStorage.setItem(STORAGE_PREFIX + slot, JSON.stringify(choice));
    else localStorage.removeItem(STORAGE_PREFIX + slot);
  } catch {
    // See loadFont: it lasts the page.
  }
}

function sameFaces(a: FontChoice, b: FontChoice): boolean {
  return JSON.stringify(a.faces) === JSON.stringify(b.faces) && a.name === b.name;
}

/** The FontFaces added, by family name and URL: added once each, whichever slots use them. */
const added = new Map<string, FontFace>();

/** Sets every slot's variable to its choice, its faces registered with the document. */
export function applyFonts() {
  const style = document.documentElement.style;
  const wanted = new Set<string>();
  for (const { slot } of FONT_SLOTS) {
    const choice = loadFont(slot);
    for (const f of choice?.faces ?? []) {
      const key = familyName(choice!.id) + " " + f.url + " " + f.weight + " " + f.style;
      wanted.add(key);
      if (added.has(key)) continue;
      // The page's script loads fonts, not its stylesheet: the CSP refuses an inline @font-face.
      const fontFace = new FontFace(familyName(choice!.id), "url(" + f.url + ")", { weight: f.weight, style: f.style });
      document.fonts.add(fontFace);
      added.set(key, fontFace);
    }
    const value = cssValue(slot, choice);
    if (value) style.setProperty("--font-" + slot, value);
    else style.removeProperty("--font-" + slot);
  }
  // A font no longer chosen, or whose files changed: its old faces mustn't be picked.
  for (const [key, fontFace] of added) {
    if (wanted.has(key)) continue;
    document.fonts.delete(fontFace);
    added.delete(key);
  }
}

/** Every file a slot uses, in the cache; the others out of it. */
async function syncCache(fetchMissing: boolean) {
  if (typeof caches === "undefined") return; // Not a secure context: no service worker either, the HTTP cache keeps them.
  const wanted = new Set(FONT_SLOTS.flatMap(({ slot }) => loadFont(slot)?.faces.map((f) => f.url) ?? []));
  const cache = await caches.open(FONT_CACHE);
  for (const request of await cache.keys()) {
    if (!wanted.has(new URL(request.url).pathname)) await cache.delete(request);
  }
  if (!fetchMissing) return;
  for (const url of wanted) {
    if (!(await cache.match(url))) await cache.add(url);
  }
}

/**
 * Uses {@code family} for {@code slot} on this device, none for the default. Its files are fetched
 * at once, to be there offline.
 *
 * @throws Error if they can't be: the choice is left as it was.
 */
export async function chooseFont(slot: FontSlot, family: FontFamily | undefined) {
  const previous = loadFont(slot);
  storeFont(slot, family ? choiceOf(family) : undefined);
  try {
    await syncCache(true);
  } catch {
    storeFont(slot, previous);
    applyFonts();
    throw new Error("The font couldn't be downloaded: try again once online.");
  }
  applyFonts();
}

/**
 * Brings the choices up to date with the fonts installed: one the admin deleted goes back to the
 * default, one they changed takes its new files. Quietly: offline, nothing changes.
 *
 * @return the installed fonts, if the server said.
 */
export async function refreshFonts(): Promise<FontFamily[] | undefined> {
  let fonts: FontFamily[];
  try {
    fonts = (await ApiEndpoints.Fonts.call({})).fonts;
  } catch {
    return undefined;
  }
  let changed = false;
  for (const { slot } of FONT_SLOTS) {
    const choice = loadFont(slot);
    if (!choice) continue;
    const family = fonts.find((f) => f.id === choice.id);
    const updated = family ? choiceOf(family) : undefined;
    if (updated && sameFaces(updated, choice)) continue;
    storeFont(slot, updated);
    changed = true;
  }
  if (changed) applyFonts();
  await syncCache(true).catch(() => undefined);
  return fonts;
}

let refreshed = false;

/** Once per page load, once logged in. */
export function refreshFontsOnce() {
  if (refreshed) return;
  refreshed = true;
  void refreshFonts();
}

/** At startup; and when another tab of this app changes a choice, this one follows. */
export function initFonts(onChange: () => void) {
  if (typeof FontFace === "undefined" || !document.fonts) return;
  applyFonts();
  window.addEventListener("storage", (event) => {
    if (event.key !== null && !event.key.startsWith(STORAGE_PREFIX)) return;
    applyFonts();
    onChange();
  });
}
