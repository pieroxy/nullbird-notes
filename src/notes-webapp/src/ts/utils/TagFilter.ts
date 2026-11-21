/**
 * Which notes the list shows: those with all of {@code tags}, or, with {@code untagged}, those
 * with none (then {@code tags} is empty). Kept per device, not synced: it's a view, not data.
 */
export interface TagFilter {
  tags: string[];
  untagged: boolean;
}

export interface TagCount {
  tag: string;
  /** Notes the list would show with this tag added to the filter; for a selected tag, those it shows. */
  count: number;
  selected: boolean;
}

export const NO_FILTER: TagFilter = { tags: [], untagged: false };

export function isActive(filter: TagFilter): boolean {
  return filter.untagged || filter.tags.length > 0;
}

export function matches(filter: TagFilter, noteTags: string[]): boolean {
  if (filter.untagged) return noteTags.length === 0;
  return filter.tags.every((tag) => noteTags.includes(tag));
}

/** Selecting a tag leaves the untagged view. */
export function toggle(filter: TagFilter, tag: string): TagFilter {
  const tags = filter.tags.includes(tag) ? filter.tags.filter((t) => t !== tag) : [...filter.tags, tag];
  return { tags, untagged: false };
}

export function toggleUntagged(filter: TagFilter): TagFilter {
  return { tags: [], untagged: !filter.untagged };
}

/**
 * Every tag of {@code notes} (each a note's tags), plus the selected ones even if no note has
 * them any more, so they can be unselected. Sorted case-insensitively.
 */
export function tagCounts(filter: TagFilter, notes: string[][]): TagCount[] {
  const counts = new Map<string, number>(filter.tags.map((tag) => [tag, 0]));
  for (const noteTags of notes) {
    const shown = matches(filter, noteTags);
    for (const tag of noteTags) counts.set(tag, (counts.get(tag) ?? 0) + (shown ? 1 : 0));
  }
  return [...counts].map(([tag, count]) => ({ tag, count, selected: filter.tags.includes(tag) }))
    .sort((a, b) => a.tag.localeCompare(b.tag, undefined, { sensitivity: "base" }) || (a.tag < b.tag ? -1 : 1));
}

/** {@code counts} in {@code order} (as the user ordered their tags), or as they are without one. */
export function inOrder(counts: TagCount[], order: string[] | undefined): TagCount[] {
  if (!order) return counts;
  const rank = new Map(order.map((tag, i) => [tag, i]));
  return [...counts].sort((a, b) => (rank.get(a.tag) ?? order.length) - (rank.get(b.tag) ?? order.length));
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
 * the way out of it.
 */
export function visibleTags(counts: TagCount[], filter: TagFilter, search: string): TagCount[] {
  const found = findTags(counts, search);
  return filter.tags.length > 0 ? found.filter((count) => count.selected || count.count > 0) : found;
}

const STORAGE_PREFIX = "notes.tagFilter.";

/** Storage can be unavailable (private browsing, blocked site data): then the filter lasts the page. */
export function loadFilter(username: string): TagFilter {
  try {
    const stored = JSON.parse(localStorage.getItem(STORAGE_PREFIX + username) ?? "null");
    if (stored && Array.isArray(stored.tags) && stored.tags.every((tag: unknown) => typeof tag === "string")) {
      return { tags: stored.tags, untagged: stored.untagged === true && stored.tags.length === 0 };
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
