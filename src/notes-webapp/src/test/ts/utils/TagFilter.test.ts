import { inOrder, isActive, matches, NO_FILTER, tagCounts, toggle, toggleMerged, toggleUntagged, visibleTags } from "../../../ts/utils/TagFilter";

const NOTES = [["work", "urgent"], ["work"], ["home"], [], ["work", "home"]];

describe("TagFilter", () => {
  test("a note matches when it has all the selected tags", () => {
    const filter = { tags: ["work", "urgent"], untagged: false, merged: false };
    expect(matches(filter, ["urgent", "work", "home"])).toBe(true);
    expect(matches(filter, ["work"])).toBe(false);
    expect(matches(NO_FILTER, [])).toBe(true);
  });

  test("the untagged view shows notes without tags", () => {
    const filter = toggleUntagged(NO_FILTER);
    expect(matches(filter, [])).toBe(true);
    expect(matches(filter, ["work"])).toBe(false);
  });

  test("the merged view shows notes with merge marks, whatever their tags", () => {
    const filter = toggleMerged(toggle(NO_FILTER, "work"));
    expect(filter).toEqual({ tags: [], untagged: false, merged: true });
    expect(isActive(filter)).toBe(true);
    expect(matches(filter, ["home"], true)).toBe(true);
    expect(matches(filter, [], false)).toBe(false);
    expect(toggleUntagged(filter)).toEqual({ tags: [], untagged: true, merged: false });
    expect(toggle(filter, "home")).toEqual({ tags: ["home"], untagged: false, merged: false });
    expect(toggleMerged(filter)).toEqual(NO_FILTER);
  });

  test("toggling", () => {
    const work = toggle(NO_FILTER, "work");
    expect(work).toEqual({ tags: ["work"], untagged: false, merged: false });
    expect(isActive(work)).toBe(true);
    expect(toggleUntagged(work)).toEqual({ tags: [], untagged: true, merged: false });
    expect(toggle(toggleUntagged(work), "home")).toEqual({ tags: ["home"], untagged: false, merged: false });
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
    expect(tagCounts({ tags: ["work"], untagged: false, merged: false }, NOTES)).toEqual([
      { tag: "home", count: 1, selected: false },
      { tag: "urgent", count: 1, selected: false },
      { tag: "work", count: 3, selected: true },
    ]);
    expect(tagCounts({ tags: ["work", "urgent"], untagged: false, merged: false }, NOTES).map((c) => c.count)).toEqual([0, 1, 1]);
  });

  test("a selected tag no note has any more is still listed", () => {
    expect(tagCounts({ tags: ["gone"], untagged: false, merged: false }, NOTES)[0]).toEqual({ tag: "gone", count: 0, selected: true });
  });

  test("sorted regardless of case, then upper case first", () => {
    expect(tagCounts(NO_FILTER, [["b", "a", "B", "C"]]).map((c) => c.tag)).toEqual(["a", "B", "b", "C"]);
  });

  test("inOrder: as ordered, alphabetical without an order, unknown ones last", () => {
    const counts = [{ tag: "a", count: 1, selected: false }, { tag: "b", count: 1, selected: false }, { tag: "c", count: 0, selected: true }];
    expect(inOrder(counts, undefined).map((c) => c.tag)).toEqual(["a", "b", "c"]);
    expect(inOrder(counts, ["b", "a"]).map((c) => c.tag)).toEqual(["b", "a", "c"]);
  });

  describe("visibleTags", () => {
    const notes = [["work", "urgent"], ["work"], ["home"], []];

    test("no filter: every tag", () => {
      expect(visibleTags(tagCounts(NO_FILTER, notes), NO_FILTER, "").map((c) => c.tag)).toEqual(["home", "urgent", "work"]);
    });

    test("a tag selected: those that would leave no note go, the selected one stays", () => {
      const filter = { tags: ["work"], untagged: false, merged: false };
      expect(visibleTags(tagCounts(filter, notes), filter, "").map((c) => c.tag)).toEqual(["urgent", "work"]);
    });

    test("a selected tag no note has any more stays, to be unselected", () => {
      const filter = { tags: ["gone"], untagged: false, merged: false };
      expect(visibleTags(tagCounts(filter, notes), filter, "").map((c) => c.tag)).toEqual(["gone"]);
    });

    test("the search narrows what's left", () => {
      const filter = { tags: ["work"], untagged: false, merged: false };
      expect(visibleTags(tagCounts(filter, notes), filter, "URG").map((c) => c.tag)).toEqual(["urgent"]);
      expect(visibleTags(tagCounts(filter, notes), filter, "home")).toEqual([]);
    });

    test("the untagged view keeps every tag: they're the way out", () => {
      const filter = { tags: [], untagged: true, merged: false };
      expect(visibleTags(tagCounts(filter, notes), filter, "").map((c) => c.tag)).toEqual(["home", "urgent", "work"]);
    });
  });
});
