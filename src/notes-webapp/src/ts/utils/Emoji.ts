import { EMOJI_DATA } from "./EmojiData";
import { fold } from "./Search";

/**
 * The emoji helper: a button by the note's, opening a picker (see EmojiPicker). Phones and tablets
 * have emojis on their keyboard: in "auto", it shows only where the main pointer isn't a finger.
 * The setting and the recent emojis are kept per device, as the rest of the appearance.
 */
export interface Emoji {
  char: string;
  /** Its Emoji version: an older system may not draw the newer ones. */
  version: number;
  name: string;
  keywords: string;
}

export interface EmojiGroup {
  id: string;
  label: string;
  icon: string;
  emojis: Emoji[];
}

/** As style.scss's touch screens. {@code choice}: EMOJI_HELPER's (see Appearance). */
export const TOUCH = "(pointer: coarse)";

export function emojiHelperShown(choice: string, touch: boolean): boolean {
  return choice === "shown" || (choice === "auto" && !touch);
}

let groups: EmojiGroup[] | undefined;

/** Parsed when first needed: the page starts without it. */
export function emojiGroups(): EmojiGroup[] {
  groups ??= EMOJI_DATA.map(([id, label, icon, lines]) => ({
    id, label, icon,
    emojis: lines.split("\n").map((line) => {
      const [char, version, name, keywords] = line.split("\t");
      return { char, version: Number(version), name, keywords };
    }),
  }));
  return groups;
}

/**
 * The emojis whose name or keywords have words starting with each word of {@code query}: those
 * whose name has them all first, the name starting with the first one before.
 */
export function searchEmojis(all: Emoji[], query: string): Emoji[] {
  const terms = fold(query).split(/[\s:,]+/).filter(Boolean);
  if (terms.length === 0) return [];
  const words = (text: string) => fold(text).split(/[^\p{L}\p{N}+]+/u);
  const has = (list: string[], term: string) => list.some((w) => w.startsWith(term));
  const ranked: { emoji: Emoji; rank: number; index: number }[] = [];
  all.forEach((emoji, index) => {
    const name = words(emoji.name);
    const both = name.concat(words(emoji.keywords));
    if (!terms.every((t) => has(both, t))) return;
    const rank = name[0].startsWith(terms[0]) ? 0 : terms.every((t) => has(name, t)) ? 1 : 2;
    ranked.push({ emoji, rank, index });
  });
  return ranked.sort((a, b) => a.rank - b.rank || a.index - b.index).map((r) => r.emoji);
}

const RECENT_KEY = "notes.emoji.recent";
export const MAX_RECENT = 24;

/** Storage can be unavailable (private browsing, blocked site data): then none. */
export function loadRecent(): string[] {
  try {
    const stored = JSON.parse(localStorage.getItem(RECENT_KEY) ?? "[]");
    return Array.isArray(stored) ? stored.filter((e): e is string => typeof e === "string").slice(0, MAX_RECENT) : [];
  } catch {
    return [];
  }
}

export function addRecent(char: string) {
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify([char, ...loadRecent().filter((e) => e !== char)].slice(0, MAX_RECENT)));
  } catch {
    // See loadRecent: not remembered.
  }
}

/**
 * The Emoji versions this system draws, from a test of each: a sample in color, as wide as one
 * emoji. Newer ones come out as an empty box, or as the pieces of a sequence side by side.
 * When even the oldest fails (no color emoji font), all: whatever font there is does its best.
 */
export function drawnVersions(all: Emoji[], draws: (sample: string) => boolean): Set<number> {
  const samples = new Map<number, string>();
  for (const emoji of all) if (!samples.has(emoji.version)) samples.set(emoji.version, emoji.char);
  const versions = [...samples.keys()].sort((a, b) => a - b);
  const drawn = new Set(versions.filter((v) => draws(samples.get(v)!)));
  return drawn.has(versions[0]) ? drawn : new Set(versions);
}

/** Through a canvas: an emoji font draws in color, its fallback in the text's color, here black. */
export function canvasDraws(): (sample: string) => boolean {
  const size = 24;
  const canvas = document.createElement("canvas");
  canvas.width = size * 3;
  canvas.height = size * 1.5;
  const context = canvas.getContext("2d", { willReadFrequently: true });
  if (!context) return () => true;
  context.font = size + 'px "Apple Color Emoji", "Segoe UI Emoji", "Noto Color Emoji", sans-serif';
  context.textBaseline = "top";
  const width = context.measureText("😀").width;
  return (sample) => {
    if (context.measureText(sample).width > width * 1.5) return false;
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.fillStyle = "#000";
    context.fillText(sample, 0, 0);
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    for (let i = 0; i < pixels.length; i += 4) {
      if (pixels[i + 3] > 0 && (pixels[i] !== pixels[i + 1] || pixels[i + 1] !== pixels[i + 2])) return true;
    }
    return false;
  };
}

/** The shortcut of the system's own picker, where one works in a browser; none on Linux, where none reliably does. */
export function systemShortcut(userAgent: string): string | undefined {
  if (/Windows/.test(userAgent)) return "Win+.";
  if (/Macintosh/.test(userAgent)) return "Ctrl+Cmd+Space";
  return undefined;
}
