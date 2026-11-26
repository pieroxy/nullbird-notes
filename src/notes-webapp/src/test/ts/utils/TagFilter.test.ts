import { inOrder, isActive, matches, visible, NO_FILTER, tagCounts, toggle, toggleMerged, toggleTrash, toggleUntagged, TRASH, visibleTags } from "../../../ts/utils/TagFilter";

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

  test("a note with a hidden tag shows only when one of its hidden tags is selected", () => {
    const hidden = new Set(["archive", "old"]);
    expect(visible(["work"], [], hidden)).toBe(true);
    expect(visible(["work", "archive"], [], hidden)).toBe(false);
    expect(visible(["work", "archive"], ["work"], hidden)).toBe(false);
    expect(visible(["work", "archive"], ["archive"], hidden)).toBe(true);
    expect(visible(["archive", "old"], ["old"], hidden)).toBe(true);
    expect(matches(toggle(NO_FILTER, "work"), ["work", "archive"], false, hidden)).toBe(false);
    expect(matches(NO_FILTER, ["archive"], false, hidden)).toBe(false);
    expect(matches(toggle(NO_FILTER, "archive"), ["archive"], false, hidden)).toBe(true);
    // The merged view: every note with marks.
    expect(matches(toggleMerged(NO_FILTER), ["archive"], true, hidden)).toBe(true);
  });

  test("counts: a hidden tag counts its notes; other tags don't count hidden notes", () => {
    const notes = [["work", "archive"], ["work"], ["archive"]];
    const counts = tagCounts(NO_FILTER, notes, new Set(["archive"]));
    expect(counts).toEqual([
      { tag: "archive", count: 2, selected: false },
      { tag: "work", count: 1, selected: false },
    ]);
    const inArchive = tagCounts(toggle(NO_FILTER, "archive"), notes, new Set(["archive"]));
    expect(inArchive).toEqual([
      { tag: "archive", count: 2, selected: true },
      { tag: "work", count: 1, selected: false },
    ]);
  });

  test("the trash: a view of its own, left by selecting anything else", () => {
    const trash = toggleTrash(NO_FILTER);
    expect(trash).toEqual(TRASH);
    expect(isActive(trash)).toBe(true);
    expect(toggleTrash(trash)).toEqual(NO_FILTER);
    expect(toggle(trash, "work").trash).toBeFalsy();
    expect(toggleUntagged(trash).trash).toBeFalsy();
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

describe("TagFilter, nested tags", () => {
  const notes = [["work/clients/acme"], ["work/meetings", "urgent"], ["work"], ["home"], ["archive/2025"]];

  test("a tag matches the notes of the tags under it", () => {
    const filter = { ...NO_FILTER, tags: ["work"] };
    expect(notes.filter((tags) => matches(filter, tags))).toEqual([["work/clients/acme"], ["work/meetings", "urgent"], ["work"]]);
    expect(matches({ ...NO_FILTER, tags: ["work/clients"] }, ["workshop"])).toBe(false);
  });

  test("counts: the tags above a note's count it too; children are flagged; tree order", () => {
    const counts = tagCounts(NO_FILTER, notes);
    expect(counts.map((c) => [c.tag, c.count, !!c.hasChildren])).toEqual([
      ["archive", 1, true], ["archive/2025", 1, false], ["home", 1, false], ["urgent", 1, false],
      ["work", 3, true], ["work/clients", 1, true], ["work/clients/acme", 1, false], ["work/meetings", 1, false]]);
  });

  test("the panel follows the selection: the top tags, and those right under a selected one", () => {
    const shown = (tags: string[]) => {
      const filter = { ...NO_FILTER, tags };
      return visibleTags(tagCounts(filter, notes), filter, "").map((c) => c.tag);
    };
    expect(shown([])).toEqual(["archive", "home", "urgent", "work"]);
    expect(shown(["work"])).toEqual(["urgent", "work", "work/clients", "work/meetings"]);
    expect(shown(["work", "work/clients"])).toEqual(["work", "work/clients", "work/clients/acme"]);
    // Searching: every tag that matches, nested or not.
    expect(visibleTags(tagCounts(NO_FILTER, notes), NO_FILTER, "acme").map((c) => c.tag)).toEqual(["work/clients/acme"]);
  });

  test("unselecting a tag unselects those under it; siblings add up", () => {
    const filter = { ...NO_FILTER, tags: ["work", "work/clients", "work/clients/acme", "home"] };
    expect(toggle(filter, "work").tags).toEqual(["home"]);
    expect(toggle(filter, "work/clients").tags).toEqual(["work", "home"]);
    expect(toggle({ ...NO_FILTER, tags: ["work", "work/clients"] }, "work/meetings").tags).toEqual(["work", "work/clients", "work/meetings"]);
  });

  test("a hidden tag hides the tags under it; selecting any of them shows their notes", () => {
    const hidden = new Set(["archive"]);
    expect(visible(["archive/2025"], [], hidden)).toBe(false);
    expect(visible(["archive/2025"], ["archive"], hidden)).toBe(true);
    expect(visible(["archive/2025"], ["archive/2025"], hidden)).toBe(true);
    expect(visible(["work"], [], hidden)).toBe(true);
    expect(visible(["work/old"], ["work"], new Set(["work/old"]))).toBe(false);
  });

  test("in the user's order, siblings stay together under their parent", () => {
    const counts = tagCounts(NO_FILTER, notes);
    expect(inOrder(counts, ["urgent", "work/meetings", "home", "work/clients/acme", "archive/2025"]).map((c) => c.tag)).toEqual([
      "urgent", "work", "work/meetings", "work/clients", "work/clients/acme", "home", "archive", "archive/2025"]);
  });
});
