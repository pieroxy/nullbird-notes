/** How the history of a note (see NoteHistory) says when each version was made. */

const DAY = 24 * 60 * 60 * 1000;
/** As the server's ThinningPolicy: every save kept this long, then fewer. */
export const KEEP_ALL_DAYS = 7;

function startOfDay(millis: number): number {
  const date = new Date(millis);
  date.setHours(0, 0, 0, 0);
  return date.getTime();
}

/** Today, Yesterday, then the date: without the year this year. */
export function dayLabel(millis: number, now: number = Date.now()): string {
  const days = Math.round((startOfDay(now) - startOfDay(millis)) / DAY);
  if (days === 0) return "Today";
  if (days === 1) return "Yesterday";
  const date = new Date(millis);
  const sameYear = date.getFullYear() === new Date(now).getFullYear();
  return date.toLocaleDateString([], { weekday: "short", day: "numeric", month: "short", year: sameYear ? undefined : "numeric" });
}

export function timeOfDay(millis: number): string {
  return new Date(millis).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

/** How long a session lasted, in whole minutes; undefined under a minute (a single save, mostly). */
export function sessionMinutes(from: number, to: number): number | undefined {
  return to - from < 60_000 ? undefined : Math.round((to - from) / 60_000);
}

/** The time, then how long it lasted: "14:02 · 18 min". */
export function sessionLabel(from: number, to: number): string {
  const minutes = sessionMinutes(from, to);
  return timeOfDay(from) + (minutes === undefined ? "" : " · " + minutes + " min");
}

/** Consecutive items of the same day together, under its label; in the order given. */
export function byDay<T>(items: T[], at: (item: T) => number, now: number = Date.now()): { label: string; items: T[] }[] {
  const days: { label: string; items: T[] }[] = [];
  for (const item of items) {
    const label = dayLabel(at(item), now);
    const last = days[days.length - 1];
    if (last && last.label === label) last.items.push(item);
    else days.push({ label, items: [item] });
  }
  return days;
}

/** Older than a week: only a version an hour is left, then one a day. */
export function isThinned(millis: number, now: number = Date.now()): boolean {
  return now - millis > KEEP_ALL_DAYS * DAY;
}
