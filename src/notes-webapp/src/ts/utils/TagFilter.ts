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

/** Those whose name contains {@code search}, ignoring case. */
export function findTags(counts: TagCount[], search: string): TagCount[] {
  const needle = search.trim().toLowerCase();
  return needle ? counts.filter((count) => count.tag.toLowerCase().includes(needle)) : counts;
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
