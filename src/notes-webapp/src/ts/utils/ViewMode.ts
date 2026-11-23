/** The notes shown rendered (Markdown ones), by ID. Kept per user and per device: never synced. */
const STORAGE_PREFIX = "notes.viewing.";

/** Storage can be unavailable (private browsing, blocked site data): then it lasts the page. */
export function loadViewing(username: string): Set<string> {
  try {
    const stored = JSON.parse(localStorage.getItem(STORAGE_PREFIX + username) ?? "null");
    if (Array.isArray(stored)) return new Set(stored.filter((id: unknown) => typeof id === "string"));
  } catch {
    // Unavailable or garbled: none.
  }
  return new Set();
}

/** Only the IDs among {@code existing}: deleted notes don't pile up. */
export function saveViewing(username: string, viewing: Set<string>, existing: Set<string>) {
  const kept = [...viewing].filter((id) => existing.has(id));
  try {
    if (kept.length) localStorage.setItem(STORAGE_PREFIX + username, JSON.stringify(kept));
    else localStorage.removeItem(STORAGE_PREFIX + username);
  } catch {
    // See loadViewing.
  }
}
