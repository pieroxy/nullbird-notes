import { byDay, dayLabel, isThinned, sessionMinutes } from "../../../ts/utils/HistoryDates";

const NOW = new Date(2026, 10, 28, 15, 0).getTime();
const at = (day: number, hour: number, minute = 0) => new Date(2026, 10, day, hour, minute).getTime();

describe("HistoryDates", () => {
  test("today, yesterday, then dates", () => {
    expect(dayLabel(at(28, 0, 5), NOW)).toBe("Today");
    expect(dayLabel(at(27, 23, 59), NOW)).toBe("Yesterday");
    expect(dayLabel(at(20, 12), NOW)).not.toMatch(/2026/);
    expect(dayLabel(new Date(2025, 0, 3).getTime(), NOW)).toMatch(/2025/);
  });

  test("a session's length in minutes, none under a minute", () => {
    expect(sessionMinutes(at(28, 14), at(28, 14) + 40_000)).toBeUndefined();
    expect(sessionMinutes(at(28, 14), at(28, 14, 18))).toBe(18);
    expect(sessionMinutes(at(28, 14), at(28, 14, 1) + 20_000)).toBe(1);
  });

  test("grouped by day, in the order given", () => {
    const days = byDay([at(28, 14), at(28, 9), at(27, 18), at(20, 10)], (t) => t, NOW);
    expect(days.map((d) => [d.label, d.items.length])).toEqual([["Today", 2], ["Yesterday", 1], [dayLabel(at(20, 10), NOW), 1]]);
  });

  test("thinned past a week", () => {
    expect(isThinned(at(22, 14), NOW)).toBe(false);
    expect(isThinned(at(20, 14), NOW)).toBe(true);
  });
});
