import { DEFAULT_LAYOUT, resetWidth, tagsShown, toggleList, toggleTags, withWidth } from "../../../ts/utils/PaneLayout";

describe("PaneLayout", () => {
  test("the panes close and open on their own", () => {
    expect(toggleTags(DEFAULT_LAYOUT)).toEqual({ ...DEFAULT_LAYOUT, tags: false });
    expect(toggleList(DEFAULT_LAYOUT)).toEqual({ ...DEFAULT_LAYOUT, list: false });
    expect(toggleList(toggleList(DEFAULT_LAYOUT))).toEqual(DEFAULT_LAYOUT);
  });

  test("the tags show only with the list, which brings them back when it reopens", () => {
    const listClosed = toggleList(DEFAULT_LAYOUT);
    expect(tagsShown(listClosed)).toBe(false);
    expect(toggleList(listClosed)).toEqual(DEFAULT_LAYOUT);

    const bothClosed = toggleList(toggleTags(DEFAULT_LAYOUT));
    expect(tagsShown(toggleList(bothClosed))).toBe(false);
  });

  test("with the list closed, the tags open it", () => {
    const listClosed = toggleList(DEFAULT_LAYOUT);
    expect(toggleTags(listClosed)).toEqual(DEFAULT_LAYOUT);
    expect(toggleTags({ ...listClosed, tags: false })).toEqual(DEFAULT_LAYOUT);
  });

  test("a width stays between the pane's minimum and what leaves the editor its own", () => {
    expect(withWidth(DEFAULT_LAYOUT, "tags", 250.4, 1200).tagsWidth).toBe(250);
    expect(withWidth(DEFAULT_LAYOUT, "tags", 50, 1200).tagsWidth).toBe(140);
    expect(withWidth(DEFAULT_LAYOUT, "list", 2000, 1200).listWidth).toBe(1200 - 200 - 320);
    expect(withWidth({ ...DEFAULT_LAYOUT, tags: false }, "list", 2000, 1200).listWidth).toBe(1200 - 320);
  });

  test("the minimum wins in a window too narrow for both", () => {
    expect(withWidth(DEFAULT_LAYOUT, "list", 500, 600).listWidth).toBe(220);
  });

  test("a width goes back to its default", () => {
    expect(resetWidth({ ...DEFAULT_LAYOUT, listWidth: 480 }, "list")).toEqual(DEFAULT_LAYOUT);
  });
});
