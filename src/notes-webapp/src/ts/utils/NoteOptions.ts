import { NoteDefaults, TagOptions } from "../auto/api-types";
import { MARKDOWN, MONOSPACE, NOWRAP } from "../sync/Metadata";
import { nearest, withoutRedundant } from "./TagTree";

/**
 * A note's options as they apply: its own when set ("true" or "false"), otherwise its tags', the
 * first in the user's tag order to set one (see TagOptions on the server), otherwise the user's
 * default (see NoteDefaults on the server; absent, off, and long lines wrapped).
 * Notes follow their tags live: a tag's option changed, every note of it without its own follows.
 * A nested tag that doesn't set an option follows the nearest tag above it that does (see
 * TagTree.nearest), and a tag above another of the note's says nothing more: "work/clients" decides,
 * not "work" next to it.
 */
export type FlagKey = typeof MARKDOWN | typeof MONOSPACE | typeof NOWRAP;
export type SortOrder = "modified" | "created" | "title";

export const SORT_ORDERS: { id: SortOrder; label: string }[] = [
  { id: "modified", label: "Last modified first" },
  { id: "created", label: "Last created first" },
  { id: "title", label: "By title" },
];

export interface AppliedOptions {
  markdown: boolean;
  monospace: boolean;
  nowrap: boolean;
  /** Its Markdown opens rendered (see TagOptions.rendered). */
  rendered: boolean;
  /** Installed fonts' IDs, in place of the user's, when a tag says. */
  font?: string;
  monoFont?: string;
  /** The tag each flag comes from, when it comes from one. */
  from: Partial<Record<FlagKey, string>>;
}

/** {@code tags} in the user's order: {@code rank} gives each one's place, those missing go last. */
export function inUserOrder(tags: string[], rank: Map<string, number>): string[] {
  return [...tags].sort((a, b) => (rank.get(a) ?? Number.MAX_SAFE_INTEGER) - (rank.get(b) ?? Number.MAX_SAFE_INTEGER));
}

/** A flag as a tag sets it; the tag's wrap is the note's nowrap, reversed. */
function tagFlag(options: TagOptions | undefined, key: FlagKey): boolean | undefined {
  if (!options) return undefined;
  if (key === MARKDOWN) return options.markdown ?? undefined;
  if (key === MONOSPACE) return options.monospace ?? undefined;
  return options.wrap === undefined || options.wrap === null ? undefined : !options.wrap;
}

/** The note's tags that decide (see withoutRedundant), in the user's order. */
export function decidingTags(tags: string[], rank: Map<string, number>): string[] {
  return inUserOrder(withoutRedundant(tags), rank);
}

/** A flag as the user's defaults set it; their wrap is the note's nowrap, reversed. */
export function defaultFlag(defaults: NoteDefaults, key: FlagKey): boolean {
  if (key === MARKDOWN) return defaults.markdown === true;
  if (key === MONOSPACE) return defaults.monospace === true;
  return defaults.wrap === false;
}

/** What the tags say for {@code key}, and which one: the first, in the user's order, to say (or a tag above it). */
export function fromTags(key: FlagKey, ordered: string[], tagOptions: { [tag: string]: TagOptions }): { value?: boolean; tag?: string } {
  for (const tag of ordered) {
    const said = nearest(tag, tagOptions, (options) => tagFlag(options, key));
    if (said.value !== undefined) return said;
  }
  return {};
}

function own(value: string | undefined): boolean | undefined {
  return value === "true" ? true : value === "false" ? false : undefined;
}

export function appliedOptions(options: { [key: string]: string }, tags: string[], tagOptions: { [tag: string]: TagOptions },
  rank: Map<string, number>, defaults: NoteDefaults = {}): AppliedOptions {
  const ordered = decidingTags(tags, rank);
  const from: AppliedOptions["from"] = {};
  const flag = (key: FlagKey): boolean => {
    const mine = own(options[key]);
    if (mine !== undefined) return mine;
    const tagged = fromTags(key, ordered, tagOptions);
    if (tagged.tag !== undefined) from[key] = tagged.tag;
    return tagged.value ?? defaultFlag(defaults, key);
  };
  const first = (pick: (o: TagOptions) => string | boolean | undefined) => {
    for (const tag of ordered) {
      const said = nearest(tag, tagOptions, pick);
      if (said.value !== undefined) return said.value;
    }
    return undefined;
  };
  return {
    markdown: flag(MARKDOWN),
    monospace: flag(MONOSPACE),
    nowrap: flag(NOWRAP),
    rendered: first((o) => o.rendered) === true,
    font: first((o) => o.font) as string | undefined,
    monoFont: first((o) => o.monoFont) as string | undefined,
    from,
  };
}

/**
 * The note's own value to store for {@code wanted}: none when its tags say the same, or without tags
 * saying, the user's default (it follows them again); otherwise "true" or "false".
 */
export function ownValueFor(wanted: boolean, tagsSay: boolean | undefined, byDefault = false): string | undefined {
  if (wanted === (tagsSay ?? byDefault)) return undefined;
  return wanted ? "true" : "false";
}

/**
 * The list's order while {@code selected} tags filter it: the first of them, in the user's order, to
 * set one, or a tag above it; the most specific selected tags decide (see decidingTags).
 */
export function activeSort(selected: string[], tagOptions: { [tag: string]: TagOptions }, rank: Map<string, number>): SortOrder {
  for (const tag of decidingTags(selected, rank)) {
    const sort = nearest(tag, tagOptions, (options) => options.sort).value;
    if (sort === "created" || sort === "title" || sort === "modified") return sort;
  }
  return "modified";
}
