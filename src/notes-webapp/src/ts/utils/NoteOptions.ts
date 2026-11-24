import { TagOptions } from "../auto/api-types";
import { MARKDOWN, MONOSPACE, NOWRAP } from "../sync/Metadata";

/**
 * A note's options as they apply: its own when set ("true" or "false"), otherwise its tags', the
 * first in the user's tag order to set one (see TagOptions on the server), otherwise the default.
 * Notes follow their tags live: a tag's option changed, every note of it without its own follows.
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

/** What the tags say for {@code key}, and which one: the first, in the user's order, to say. */
export function fromTags(key: FlagKey, ordered: string[], tagOptions: { [tag: string]: TagOptions }): { value?: boolean; tag?: string } {
  for (const tag of ordered) {
    const value = tagFlag(tagOptions[tag], key);
    if (value !== undefined) return { value, tag };
  }
  return {};
}

function own(value: string | undefined): boolean | undefined {
  return value === "true" ? true : value === "false" ? false : undefined;
}

export function appliedOptions(options: { [key: string]: string }, tags: string[], tagOptions: { [tag: string]: TagOptions },
  rank: Map<string, number>): AppliedOptions {
  const ordered = inUserOrder(tags, rank);
  const from: AppliedOptions["from"] = {};
  const flag = (key: FlagKey): boolean => {
    const mine = own(options[key]);
    if (mine !== undefined) return mine;
    const tagged = fromTags(key, ordered, tagOptions);
    if (tagged.tag !== undefined) from[key] = tagged.tag;
    return tagged.value ?? false;
  };
  const first = (pick: (o: TagOptions) => string | boolean | undefined) => {
    for (const tag of ordered) {
      const value = tagOptions[tag] && pick(tagOptions[tag]);
      if (value !== undefined && value !== null) return value;
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
 * The note's own value to store for {@code wanted}: none when its tags say the same (it follows them
 * again), otherwise "true" or "false". Without tags saying, off is none, as before.
 */
export function ownValueFor(wanted: boolean, tagsSay: boolean | undefined): string | undefined {
  if (wanted === (tagsSay ?? false)) return undefined;
  return wanted ? "true" : "false";
}

/** The list's order while {@code selected} tags filter it: the first of them, in the user's order, to set one. */
export function activeSort(selected: string[], tagOptions: { [tag: string]: TagOptions }, rank: Map<string, number>): SortOrder {
  for (const tag of inUserOrder(selected, rank)) {
    const sort = tagOptions[tag]?.sort;
    if (sort === "created" || sort === "title" || sort === "modified") return sort;
  }
  return "modified";
}
