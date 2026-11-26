import { ancestorsOf, isUnder, parentOf, rankOf, treeOrder, withAncestors } from "./TagTree";

/**
 * Which notes the list shows: those with all of {@code tags} (a note with "work/clients" has
 * "work" too, see TagTree); or, with {@code untagged}, those
 * with none; or, with {@code merged}, those with merge marks to check (see MergeMarks); or, with
 * {@code trash}, the deleted ones. Those three are views of their own: then {@code tags} is empty,
 * and only one is on. Kept per device, not synced: it's a view, not data.
 */
export interface TagFilter {
  tags: string[];
  untagged: boolean;
  merged: boolean;
  trash?: boolean;
}

export interface TagCount {
  tag: string;
  /** Notes the list would show with this tag added to the filter; for a selected tag, those it shows. */
  count: number;
  selected: boolean;
  /** Tags under it (see TagTree). */
  hasChildren?: boolean;
}

export const NO_FILTER: TagFilter = { tags: [], untagged: false, merged: false };

/** The trash: deleted notes, whatever their tags. */
export const TRASH: TagFilter = { tags: [], untagged: false, merged: false, trash: true };

const NONE: ReadonlySet<string> = new Set();

/** Whether {@code tag} is hidden: set so, or under a tag set so (see TagTree). */
export function isHidden(tag: string, hidden: ReadonlySet<string>): boolean {
  return hidden.has(tag) || ancestorsOf(tag).some((above) => hidden.has(above));
}

/** Whether a note with {@code noteTags} has {@code tag}, or a tag under it. */
export function hasTag(noteTags: string[], tag: string): boolean {
  return noteTags.some((noteTag) => isUnder(noteTag, tag));
}

/**
 * Whether a note with {@code noteTags} shows, as far as hidden tags go (see isHidden): one with any
 * shows only when a hidden tag it has is among {@code selected} (an archive shows when selected).
 */
export function visible(noteTags: string[], selected: string[], hidden: ReadonlySet<string>): boolean {
  if (hidden.size === 0 || !noteTags.some((tag) => isHidden(tag, hidden))) return true;
  return selected.some((tag) => isHidden(tag, hidden) && hasTag(noteTags, tag));
}

export function isActive(filter: TagFilter): boolean {
  return filter.untagged || filter.merged || !!filter.trash || filter.tags.length > 0;
}

/**
 * {@code merged}: whether the note has merge marks; {@code hidden}: the hidden tags (see visible).
 * The merged view shows notes with marks whatever their tags: they need a look.
 */
export function matches(filter: TagFilter, noteTags: string[], merged = false, hidden: ReadonlySet<string> = NONE): boolean {
  if (filter.untagged) return noteTags.length === 0;
  if (filter.merged) return merged;
  return filter.tags.every((tag) => hasTag(noteTags, tag)) && visible(noteTags, filter.tags, hidden);
}

/** Selecting a tag leaves the untagged or merged view. Unselecting one unselects those under it. */
export function toggle(filter: TagFilter, tag: string): TagFilter {
  const tags = filter.tags.includes(tag) ? filter.tags.filter((t) => !isUnder(t, tag)) : [...filter.tags, tag];
  return { tags, untagged: false, merged: false };
}

export function toggleUntagged(filter: TagFilter): TagFilter {
  return { tags: [], untagged: !filter.untagged, merged: false };
}

export function toggleMerged(filter: TagFilter): TagFilter {
  return { tags: [], untagged: false, merged: !filter.merged };
}

/**
 * Every tag of {@code notes} (each a note's tags) and the tags above them (see TagTree), plus the
 * selected ones even if no note has them any more, so they can be unselected. Each tag before those
 * under it, siblings alphabetically (see inOrder for the user's order).
 */
export function tagCounts(filter: TagFilter, notes: string[][], hidden: ReadonlySet<string> = NONE): TagCount[] {
  const counts = new Map<string, number>([...withAncestors(filter.tags)].map((tag) => [tag, 0]));
  for (const noteTags of notes) {
    const shown = matches(filter, noteTags);
    for (const tag of withAncestors(noteTags)) {
      // As the list would show it with this tag selected too: a hidden one counts its notes.
      const counted = shown && visible(noteTags, filter.tags.includes(tag) ? filter.tags : [...filter.tags, tag], hidden);
      counts.set(tag, (counts.get(tag) ?? 0) + (counted ? 1 : 0));
    }
  }
  return inOrder([...counts].map(([tag, count]) => ({ tag, count, selected: filter.tags.includes(tag) })), undefined);
}

/**
 * {@code counts} as the tags panel lists them (see TagTree.treeOrder): each tag before those under
 * it, siblings in {@code order} (as the user ordered their tags), alphabetically without one.
 */
export function inOrder(counts: TagCount[], order: string[] | undefined): TagCount[] {
  const byTag = new Map(counts.map((count) => [count.tag, count]));
  const parents = new Set(counts.map((count) => parentOf(count.tag)));
  const rank = rankOf(treeOrder(byTag.keys(), order));
  return [...counts].sort((a, b) => rank.get(a.tag)! - rank.get(b.tag)!).map((count) => (parents.has(count.tag) ? { ...count, hasChildren: true } : count));
}

/** Those whose name contains {@code search}, ignoring case. */
export function findTags(counts: TagCount[], search: string): TagCount[] {
  const needle = search.trim().toLowerCase();
  return needle ? counts.filter((count) => count.tag.toLowerCase().includes(needle)) : counts;
}

/**
 * The tags to list: those found by {@code search}, without, while tags are selected, those that
 * would leave no note: dead ends, since selected tags combine. Selected ones always stay, to be
 * unselected. Not in the untagged view: no shown note has a tag, every count is 0, and tags are
 * the way out of it. Nor in the merged view, for the same reason: its counts are all 0.
 * <p>
 * Without a search, the panel follows the selection: the top tags, and those right under a selected
 * one. Searching, every tag that matches, nested or not.
 */
export function visibleTags(counts: TagCount[], filter: TagFilter, search: string): TagCount[] {
  const found = search.trim() ? findTags(counts, search)
    : counts.filter((count) => count.selected || parentOf(count.tag) === undefined || filter.tags.includes(parentOf(count.tag)!));
  return filter.tags.length > 0 ? found.filter((count) => count.selected || count.count > 0) : found;
}

const STORAGE_PREFIX = "notes.tagFilter.";

/** Storage can be unavailable (private browsing, blocked site data): then the filter lasts the page. */
export function loadFilter(username: string): TagFilter {
  try {
    const stored = JSON.parse(localStorage.getItem(STORAGE_PREFIX + username) ?? "null");
    if (stored && Array.isArray(stored.tags) && stored.tags.every((tag: unknown) => typeof tag === "string")) {
      const untagged = stored.untagged === true && stored.tags.length === 0;
      const merged = stored.merged === true && stored.tags.length === 0 && !untagged;
      return stored.trash === true && stored.tags.length === 0 && !untagged && !merged ? TRASH : { tags: stored.tags, untagged, merged };
    }
  } catch {
    // Unavailable or garbled: no filter.
  }
  return NO_FILTER;
}

export function saveFilter(username: string, filter: TagFilter) {
  try {
    if (isActive(filter)) localStorage.setItem(STORAGE_PREFIX + username, JSON.stringify(filter));
    else localStorage.removeItem(STORAGE_PREFIX + username);
  } catch {
    // See loadFilter.
  }
}

export function toggleTrash(filter: TagFilter): TagFilter {
  return filter.trash ? NO_FILTER : TRASH;
}
