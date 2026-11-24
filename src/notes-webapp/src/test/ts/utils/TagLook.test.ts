import { noteStripes, tagClasses } from "../../../ts/utils/TagLook";

describe("TagLook", () => {
  test("a tag's classes: its colors, a pill when it has a background or a stripe; unknown colors ignored", () => {
    expect(tagClasses(undefined)).toBe("");
    expect(tagClasses({ dot: "red" })).toBe("");
    expect(tagClasses({ text: "blue" })).toBe(".tagtext-blue");
    expect(tagClasses({ background: "yellow", stripe: "red" })).toBe(".tagbg-yellow.tagstripe-red.tagstyled");
    expect(tagClasses({ background: "mauve" })).toBe("");
  });

  test("a note's stripe: its tags' colors in the user's order, three at most", () => {
    const options = { a: { notes: "blue" }, b: { notes: "red" }, c: { dot: "green" }, d: { notes: "teal" }, e: { notes: "pink" } };
    const rank = new Map([["e", 0], ["b", 1], ["a", 2]]);
    expect(noteStripes(["a", "b", "c"], options, rank)).toEqual(["red", "blue"]);
    expect(noteStripes(["a", "b", "d", "e"], options, rank)).toEqual(["pink", "red", "blue"]);
    expect(noteStripes(["c"], options, rank)).toEqual([]);
  });
});
