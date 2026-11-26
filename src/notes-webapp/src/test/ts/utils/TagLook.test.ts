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

describe("TagLook, nested tags", () => {
  test("the stripes stack: the tags above first, each once, three at most", () => {
    const options = { work: { notes: "blue" }, "work/clients": { notes: "red" }, "work/clients/acme": { notes: "pink" }, urgent: { notes: "orange" },
      "work/meetings": { notes: "green" } };
    const rank = new Map(["work", "work/clients", "work/clients/acme", "work/meetings", "urgent"].map((t, i) => [t, i]));
    expect(noteStripes(["work/clients"], options, rank)).toEqual(["blue", "red"]);
    expect(noteStripes(["work/clients/acme", "urgent"], options, rank)).toEqual(["blue", "red", "pink"]);
    expect(noteStripes(["work/clients", "work/meetings"], options, rank)).toEqual(["blue", "red", "green"]);
    expect(noteStripes(["work", "work/clients"], options, rank)).toEqual(["blue", "red"]);
  });
});
