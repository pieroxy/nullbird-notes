/** Today: the time. This year: day and month. Otherwise: the full date. */
export function shortDate(millis: number, now: number = Date.now()): string {
  const date = new Date(millis);
  const today = new Date(now);
  if (date.toDateString() === today.toDateString()) return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  if (date.getFullYear() === today.getFullYear()) return date.toLocaleDateString([], { day: "numeric", month: "short" });
  return date.toLocaleDateString();
}

export function time(millis: number): string {
  return new Date(millis).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

/** The first non-blank line, like the server's NoteInfo.title; the next one as an excerpt. */
export function titleAndExcerpt(text: string): { title: string; excerpt: string } {
  const lines = text.split("\n").map((line) => line.trim()).filter((line) => line.length > 0);
  return { title: (lines[0] ?? "").slice(0, 100), excerpt: (lines[1] ?? "").slice(0, 120) };
}
