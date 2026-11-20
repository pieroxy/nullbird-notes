/** Which side panes are open on wide screens, and their widths in CSS pixels. Kept per device. */
export interface PaneLayout {
  /** Kept while the list is closed, which hides the tags too: reopening the list brings them back. */
  tags: boolean;
  list: boolean;
  tagsWidth: number;
  listWidth: number;
}

export type SidePane = "tags" | "list";

export const DEFAULT_LAYOUT: PaneLayout = { tags: true, list: true, tagsWidth: 200, listWidth: 300 };

/** As in style.scss, where narrower windows shrink the tags first, then the list. */
export const MIN_WIDTH = { tags: 140, list: 220, editor: 320 };

/** Tags without the list would filter nothing shown: they show only with it. */
export function tagsShown(layout: PaneLayout): boolean {
  return layout.tags && layout.list;
}

/** With the list closed, opens it with the tags. */
export function toggleTags(layout: PaneLayout): PaneLayout {
  return tagsShown(layout) ? { ...layout, tags: false } : { ...layout, tags: true, list: true };
}

export function toggleList(layout: PaneLayout): PaneLayout {
  return { ...layout, list: !layout.list };
}

/** Clamped so that the editor keeps its minimum width in a window {@code windowWidth} wide. */
export function withWidth(layout: PaneLayout, pane: SidePane, width: number, windowWidth: number): PaneLayout {
  const other = pane === "tags" ? layout.listWidth : (tagsShown(layout) ? layout.tagsWidth : 0);
  const clamped = Math.round(Math.max(MIN_WIDTH[pane], Math.min(width, windowWidth - other - MIN_WIDTH.editor)));
  return pane === "tags" ? { ...layout, tagsWidth: clamped } : { ...layout, listWidth: clamped };
}

export function resetWidth(layout: PaneLayout, pane: SidePane): PaneLayout {
  return pane === "tags" ? { ...layout, tagsWidth: DEFAULT_LAYOUT.tagsWidth } : { ...layout, listWidth: DEFAULT_LAYOUT.listWidth };
}

const STORAGE_KEY = "notes.panes";

/** Storage can be unavailable (private browsing, blocked site data): then the layout lasts the page. */
export function loadLayout(): PaneLayout {
  try {
    const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "null");
    if (stored && typeof stored === "object") {
      return {
        tags: typeof stored.tags === "boolean" ? stored.tags : DEFAULT_LAYOUT.tags,
        list: typeof stored.list === "boolean" ? stored.list : DEFAULT_LAYOUT.list,
        tagsWidth: width(stored.tagsWidth, "tags"),
        listWidth: width(stored.listWidth, "list"),
      };
    }
  } catch {
    // Unavailable or garbled: the default.
  }
  return DEFAULT_LAYOUT;
}

function width(value: unknown, pane: SidePane): number {
  return typeof value === "number" && Number.isFinite(value) ? Math.max(MIN_WIDTH[pane], Math.round(value)) : DEFAULT_LAYOUT[pane === "tags" ? "tagsWidth" : "listWidth"];
}

export function saveLayout(layout: PaneLayout) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(layout));
  } catch {
    // See loadLayout.
  }
}
