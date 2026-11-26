import m from "mithril";
import { TagOptions } from "../auto/api-types";
import { withAncestors } from "./TagTree";

/**
 * How a tag looks, as set in its settings (see TagOptions on the server): its dot, stripe,
 * background and text wherever it shows (the tags panel, the chips under a note, the filter strip),
 * and the stripe on its notes in the list. Colors of a fixed palette, each with a strong shade (text,
 * dots, stripes) and a pale one (backgrounds) per theme, in style.scss: any combination stays legible.
 */
export const TAG_COLORS: { id: string; label: string }[] = [
  { id: "red", label: "Red" },
  { id: "orange", label: "Orange" },
  { id: "yellow", label: "Yellow" },
  { id: "green", label: "Green" },
  { id: "teal", label: "Teal" },
  { id: "blue", label: "Blue" },
  { id: "purple", label: "Purple" },
  { id: "pink", label: "Pink" },
];

const KNOWN = new Set(TAG_COLORS.map((c) => c.id));

/** A color this build knows: another's may have more. */
function known(color: string | undefined): string | undefined {
  return color && KNOWN.has(color) ? color : undefined;
}

/** The classes for a tag's element, as a selector suffix: "" for none. */
export function tagClasses(options: TagOptions | undefined): string {
  if (!options) return "";
  const background = known(options.background);
  const text = known(options.text);
  const stripe = known(options.stripe);
  return (background ? ".tagbg-" + background : "") + (text ? ".tagtext-" + text : "") + (stripe ? ".tagstripe-" + stripe : "")
    + (background || stripe ? ".tagstyled" : "");
}

/** Its dot, before its name; null for none. */
export function tagDot(options: TagOptions | undefined): m.Children {
  const dot = known(options?.dot);
  return dot ? m("span.tagdot.tagdot-" + dot, { "aria-hidden": "true" }) : null;
}

export const MAX_NOTE_STRIPES = 3;

/**
 * The colors of a note's stripe in the list: those of its tags that have one, in the user's tag
 * order, three at most. A nested tag's stripes stack (see TagTree): "work" blue and "work/clients"
 * red make blue then red on a note of "work/clients", each tag once, those above first.
 *
 * @param rank each tag's place as the tags panel lists them (see TagTree.treeOrder); tags missing from it go last.
 */
export function noteStripes(tags: string[], options: { [tag: string]: TagOptions }, rank: Map<string, number>): string[] {
  return [...withAncestors(tags)].filter((tag) => known(options[tag]?.notes))
    .sort((a, b) => (rank.get(a) ?? Infinity) - (rank.get(b) ?? Infinity))
    .slice(0, MAX_NOTE_STRIPES)
    .map((tag) => options[tag].notes!);
}

/** The stripe on a note in the list; null without colors. */
export function noteStripe(colors: string[]): m.Children {
  return colors.length ? m("span.notestripe", { "aria-hidden": "true" }, colors.map((color) => m("i.tagfill-" + color))) : null;
}
