import { ImportSummary } from "../../../ts/auto/api-types";
import { importLines, size } from "../../../ts/utils/ImportText";

const summary = (fields: Partial<ImportSummary>): ImportSummary => ({
  fileName: "notes.zip", notesInFile: 0, imported: 0, trashed: 0, alreadyThere: 0, tooLarge: [], maxNoteSize: 10 * 1024 * 1024, ...fields,
});

describe("ImportText", () => {
  test("before running: what it would do", () => {
    expect(importLines(summary({ token: "t", imported: 1234, trashed: 12, alreadyThere: 3,
      tooLarge: [{ title: "Huge", size: 11 * 1024 * 1024 }] }), false)).toEqual([
      (1234).toLocaleString() + " notes to import (12 of them to the trash)",
      "3 already there, skipped",
      "1 too large (over 10 MB), skipped:",
    ]);
  });

  test("once run: what it did", () => {
    expect(importLines(summary({ imported: 1 }), true)).toEqual(["1 note imported"]);
  });

  test("nothing to import", () => {
    expect(importLines(summary({ alreadyThere: 2 }), false)).toEqual(["Nothing to import.", "2 already there, skipped"]);
  });

  test("sizes", () => {
    expect(size(100)).toBe("1 KB");
    expect(size(812 * 1024)).toBe("812 KB");
    expect(size(1024 * 1024)).toBe("1 MB");
    expect(size(12.5 * 1024 * 1024)).toBe("13 MB");
    expect(size(2.5 * 1024 * 1024)).toBe("2.5 MB");
  });
});
