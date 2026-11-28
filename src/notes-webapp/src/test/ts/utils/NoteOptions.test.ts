import { activeSort, appliedOptions, ownValueFor } from "../../../ts/utils/NoteOptions";

const RANK = new Map([["journal", 0], ["code", 1]]);
const TAGS = {
  journal: { markdown: true, wrap: false, font: "aaaaaaaaaaaaaaaa", rendered: true, sort: "created" },
  code: { markdown: false, monospace: true, wrap: true, monoFont: "bbbbbbbbbbbbbbbb", sort: "title" },
};

describe("NoteOptions", () => {
  test("a note follows its tags, the first in the user's order winning", () => {
    const applied = appliedOptions({}, ["code", "journal"], TAGS, RANK);
    expect(applied).toEqual({
      markdown: true, monospace: true, nowrap: true, rendered: true, font: "aaaaaaaaaaaaaaaa", monoFont: "bbbbbbbbbbbbbbbb",
      from: { markdown: "journal", monospace: "code", nowrap: "journal" },
    });
  });

  test("its own value wins, either way", () => {
    const applied = appliedOptions({ markdown: "false", monospace: "false", nowrap: "true" }, ["code", "journal"], TAGS, RANK);
    expect([applied.markdown, applied.monospace, applied.nowrap]).toEqual([false, false, true]);
    expect(applied.from).toEqual({});
    expect(appliedOptions({ markdown: "true" }, [], {}, RANK).markdown).toBe(true);
  });

  test("without tags saying: as before, off", () => {
    expect(appliedOptions({}, ["other"], TAGS, RANK)).toEqual({ markdown: false, monospace: false, nowrap: false, rendered: false, from: {} });
  });

  test("without tags saying: the user's defaults", () => {
    const defaults = { markdown: true, monospace: true, wrap: false };
    expect(appliedOptions({}, ["other"], TAGS, RANK, defaults)).toEqual({ markdown: true, monospace: true, nowrap: true, rendered: false, from: {} });
    // Tags, then the note's own, still win.
    const applied = appliedOptions({ monospace: "false" }, ["code"], TAGS, RANK, defaults);
    expect([applied.markdown, applied.monospace, applied.nowrap]).toEqual([false, false, false]);
  });

  test("toggling, without tags saying: the note's own value only when it differs from the user's default", () => {
    expect(ownValueFor(true, undefined, true)).toBeUndefined();
    expect(ownValueFor(false, undefined, true)).toBe("false");
    expect(ownValueFor(true, false, true)).toBe("true");
  });

  test("toggling: the note's own value only when it differs from its tags'", () => {
    expect(ownValueFor(true, true)).toBeUndefined();
    expect(ownValueFor(false, true)).toBe("false");
    expect(ownValueFor(true, undefined)).toBe("true");
    expect(ownValueFor(false, undefined)).toBeUndefined();
    expect(ownValueFor(true, false)).toBe("true");
  });

  test("the list's order: the first selected tag, in the user's order, to set one", () => {
    expect(activeSort([], TAGS, RANK)).toBe("modified");
    expect(activeSort(["code", "journal"], TAGS, RANK)).toBe("created");
    expect(activeSort(["code"], TAGS, RANK)).toBe("title");
    expect(activeSort(["other"], TAGS, RANK)).toBe("modified");
  });
});

describe("NoteOptions, nested tags", () => {
  const options = { code: { monospace: true, markdown: true, sort: "title" as const }, "code/python": { markdown: false } };
  const rank = new Map([["code", 0], ["code/python", 1], ["code/sql", 2]]);

  test("a nested tag follows the nearest tag above it, unless it sets its own", () => {
    const sql = appliedOptions({}, ["code/sql"], options, rank);
    expect([sql.monospace, sql.markdown, sql.from.monospace]).toEqual([true, true, "code"]);
    const python = appliedOptions({}, ["code/python"], options, rank);
    expect([python.monospace, python.markdown, python.from.markdown]).toEqual([true, false, "code/python"]);
  });

  test("a tag above another of the note's says nothing more", () => {
    expect(appliedOptions({}, ["code", "code/python"], options, rank).markdown).toBe(false);
  });

  test("the list's order: the most specific selected tag, or one above it", () => {
    expect(activeSort(["code/sql"], options, rank)).toBe("title");
  });
});
