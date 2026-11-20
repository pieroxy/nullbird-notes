import { isActive, matches, NO_FILTER, tagCounts, toggle, toggleUntagged } from "../../../ts/utils/TagFilter";

const NOTES = [["work", "urgent"], ["work"], ["home"], [], ["work", "home"]];

describe("TagFilter", () => {
  test("a note matches when it has all the selected tags", () => {
    const filter = { tags: ["work", "urgent"], untagged: false };
    expect(matches(filter, ["urgent", "work", "home"])).toBe(true);
    expect(matches(filter, ["work"])).toBe(false);
    expect(matches(NO_FILTER, [])).toBe(true);
  });

  test("the untagged view shows notes without tags", () => {
    const filter = toggleUntagged(NO_FILTER);
    expect(matches(filter, [])).toBe(true);
    expect(matches(filter, ["work"])).toBe(false);
  });

  test("toggling", () => {
    const work = toggle(NO_FILTER, "work");
    expect(work).toEqual({ tags: ["work"], untagged: false });
    expect(isActive(work)).toBe(true);
    expect(toggleUntagged(work)).toEqual({ tags: [], untagged: true });
    expect(toggle(toggleUntagged(work), "home")).toEqual({ tags: ["home"], untagged: false });
    expect(isActive(toggle(work, "work"))).toBe(false);
  });

  test("without a filter, each tag counts the notes that have it", () => {
    expect(tagCounts(NO_FILTER, NOTES)).toEqual([
      { tag: "home", count: 2, selected: false },
      { tag: "urgent", count: 1, selected: false },
      { tag: "work", count: 3, selected: false },
    ]);
  });

  test("with a filter, each tag counts the notes left if it were added", () => {
    expect(tagCounts({ tags: ["work"], untagged: false }, NOTES)).toEqual([
      { tag: "home", count: 1, selected: false },
      { tag: "urgent", count: 1, selected: false },
      { tag: "work", count: 3, selected: true },
    ]);
    expect(tagCounts({ tags: ["work", "urgent"], untagged: false }, NOTES).map((c) => c.count)).toEqual([0, 1, 1]);
  });

  test("a selected tag no note has any more is still listed", () => {
    expect(tagCounts({ tags: ["gone"], untagged: false }, NOTES)[0]).toEqual({ tag: "gone", count: 0, selected: true });
  });

  test("sorted regardless of case, then upper case first", () => {
    expect(tagCounts(NO_FILTER, [["b", "a", "B", "C"]]).map((c) => c.tag)).toEqual(["a", "B", "b", "C"]);
  });
});
