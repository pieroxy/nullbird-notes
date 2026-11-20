import m from "mithril";
import { findTags, isActive, NO_FILTER, TagCount, TagFilter, toggle, toggleUntagged } from "../utils/TagFilter";
import { icon } from "./Icons";

export interface TagPanelAttrs {
  filter: TagFilter;
  counts: TagCount[];
  /** What's typed in the tagSearch field above the panel. */
  search: string;
  untaggedCount: number;
  onChange: (filter: TagFilter) => void;
}

/** The tags to filter the list on. */
export class TagPanel implements m.ClassComponent<TagPanelAttrs> {
  view({ attrs }: m.Vnode<TagPanelAttrs>): m.Children {
    const { filter, onChange } = attrs;
    const shown = findTags(attrs.counts, attrs.search);
    return m("aside.tagpanel", [
      m("ul", [
        attrs.counts.length === 0 ? m("li.empty", "No tags yet: add some under a note.") : null,
        attrs.counts.length > 0 && shown.length === 0 ? m("li.empty", "No tag matches.") : null,
        shown.map((count) => m("li", { key: count.tag }, tagItem(count.tag, count.count, count.selected, () => onChange(toggle(filter, count.tag))))),
      ]),
      m(".tagpanelfoot", [
        tagItem("Untagged notes", attrs.untaggedCount, filter.untagged, () => onChange(toggleUntagged(filter)), "untagged"),
        isActive(filter) ? m("button.plain.clearfilter", { type: "button", onclick: () => onChange(NO_FILTER) }, "Clear filter") : null,
      ]),
    ]);
  }
}

export interface TagSearchAttrs {
  search: string;
  onSearch: (search: string) => void;
  /** Enter: toggles the first tag found. */
  onPick: () => void;
}

/** The field narrowing the TagPanel's tags, in the top bar above it. */
export function tagSearch(attrs: TagSearchAttrs): m.Children {
  return m("input.tagsearch", {
    type: "search",
    value: attrs.search,
    placeholder: "Find a tag…",
    "aria-label": "Find a tag",
    oninput: (e: Event) => attrs.onSearch((e.target as HTMLInputElement).value),
    onkeydown: (e: KeyboardEvent) => {
      if (e.key === "Enter") attrs.onPick();
      else if (e.key === "Escape") attrs.onSearch("");
    },
  });
}

/** A tag that would leave no note is dimmed but stays clickable: the empty list says why. */
function tagItem(label: string, count: number, selected: boolean, onclick: () => void, cls = ""): m.Children {
  return m("button.tagitem" + (selected ? ".selected" : "") + (count === 0 && !selected ? ".none" : "") + (cls ? "." + cls : ""), {
    type: "button", role: "checkbox", "aria-checked": String(selected), onclick,
  }, [
    m("span.checkmark", selected ? icon("check") : null),
    m("span.tagname", label),
    m("span.count", count),
  ]);
}
