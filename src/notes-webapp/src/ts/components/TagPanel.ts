import m from "mithril";
import { isActive, NO_FILTER, TagCount, TagFilter, toggle, toggleUntagged, visibleTags } from "../utils/TagFilter";
import { icon } from "./Icons";

export interface TagPanelAttrs {
  filter: TagFilter;
  /** In the order to list them. */
  counts: TagCount[];
  /** What's typed in the tagSearch field above the panel. */
  search: string;
  untaggedCount: number;
  onChange: (filter: TagFilter) => void;
  /** Puts {@code tag} at {@code index} among the tags listed. */
  onMove: (tag: string, index: number) => void;
  /** Moves {@code tag} {@code by} places (-1: up), from where it is now: keys can repeat faster than redraws. */
  onMoveBy: (tag: string, by: number) => void;
}

/** A tag being dragged in reorder mode, and where it would go. */
interface Drag {
  tag: string;
  index: number;
  pointerId: number;
  /** Removes the window's listeners. */
  stop: () => void;
}

/**
 * The tags to filter the list on (see visibleTags: dead ends are left out while tags are selected);
 * or, in reorder mode, every tag, to put in order: by dragging the handle (mouse or touch) or with
 * Alt+Up/Down. Not while the search field narrows them.
 */
export class TagPanel implements m.ClassComponent<TagPanelAttrs> {
  private reordering = false;
  private drag?: Drag;
  /** Keeps the keyboard focus on a tag moved with the keyboard, once the list is redrawn. */
  private focusTag?: string;
  private list?: HTMLElement;

  onremove() {
    this.drag?.stop();
  }

  view({ attrs }: m.Vnode<TagPanelAttrs>): m.Children {
    const { filter, onChange } = attrs;
    const searching = attrs.search.trim() !== "";
    if (this.reordering && searching) this.reordering = false;
    return m("aside.tagpanel" + (this.reordering ? ".reordering" : ""), [
      this.reordering ? this.reorderList(attrs) : this.filterList(attrs),
      m(".tagpanelfoot", this.reordering ? [
        m("p.reorderhint", "Drag the handles, or Alt+↑ and Alt+↓."),
        m("button.plain.reorder", { type: "button", onclick: () => this.stopReordering() }, "Done"),
      ] : [
        tagItem("Untagged notes", attrs.untaggedCount, filter.untagged, () => onChange(toggleUntagged(filter)), "untagged"),
        m(".footactions", [
          isActive(filter) ? m("button.plain.clearfilter", { type: "button", onclick: () => onChange(NO_FILTER) }, "Clear filter") : null,
          attrs.counts.length > 1 ? m("button.plain.reorder", {
            type: "button", disabled: searching, title: searching ? "Clear the search to reorder the tags" : "Put the tags in your own order",
            onclick: () => (this.reordering = true),
          }, "Reorder") : null,
        ]),
      ]),
    ]);
  }

  private filterList(attrs: TagPanelAttrs): m.Children {
    const { filter, onChange } = attrs;
    const shown = visibleTags(attrs.counts, filter, attrs.search);
    const searching = attrs.search.trim() !== "";
    const narrowing = shown.some((count) => !count.selected);
    return m("ul", [
      attrs.counts.length === 0 ? m("li.empty", "No tags yet: add some under a note.") : null,
      attrs.counts.length > 0 && shown.length === 0 ? m("li.empty", "No tag matches.") : null,
      // One keyed list, without holes: mithril refuses keyed and unkeyed siblings mixed, and nulls among
      // keyed ones. " none" can't be a tag: tags have no spaces.
      [
        ...shown.map((count) => m("li", { key: count.tag }, tagItem(count.tag, count.count, count.selected, () => onChange(toggle(filter, count.tag))))),
        ...(filter.tags.length > 0 && !searching && !narrowing ? [m("li.empty", { key: " none" }, "No other tag narrows this further.")] : []),
      ],
    ]);
  }

  /** Every tag, the dragged one shown where it would go. */
  private reorderList(attrs: TagPanelAttrs): m.Children {
    const tags = attrs.counts.map((count) => count.tag);
    const drag = this.drag;
    const listed = drag ? placed(tags, drag.tag, drag.index) : tags;
    // On update too: the filter list's <ul> may be the element reused.
    const keep = (vnode: m.VnodeDOM) => (this.list = vnode.dom as HTMLElement);
    return m("ul.reorderlist", { oncreate: keep, onupdate: keep }, listed.map((tag, index) =>
      m("li", { key: tag, "data-tag": tag }, m("button.reorderitem" + (drag?.tag === tag ? ".dragging" : ""), {
        type: "button",
        "aria-label": tag + ", position " + (index + 1) + " of " + listed.length + ". Alt+Up or Alt+Down to move it.",
        oncreate: (vnode: m.VnodeDOM) => this.keepFocus(vnode, tag),
        onupdate: (vnode: m.VnodeDOM) => this.keepFocus(vnode, tag),
        onkeydown: (e: KeyboardEvent) => {
          if (!e.altKey || (e.key !== "ArrowUp" && e.key !== "ArrowDown")) return;
          e.preventDefault();
          this.focusTag = tag;
          attrs.onMoveBy(tag, e.key === "ArrowUp" ? -1 : 1);
        },
      }, [
        m("span.grip", {
          "aria-hidden": "true",
          onpointerdown: (e: PointerEvent) => this.startDrag(e, tag, index, attrs),
        }, icon("grip-vertical")),
        m("span.tagname", tag),
      ]))));
  }

  /**
   * Follows the pointer on the whole window, not with a pointer capture: the list is redrawn with
   * the tag where it would go, which moves its element, and a moved element loses the capture.
   */
  private startDrag(e: PointerEvent, tag: string, index: number, attrs: TagPanelAttrs) {
    if (e.button !== 0 || this.drag) return;
    e.preventDefault();
    const pointerId = e.pointerId;
    const move = (ev: PointerEvent) => {
      if (ev.pointerId !== pointerId) return;
      ev.preventDefault();
      this.moveDrag(ev);
    };
    const up = (ev: PointerEvent) => {
      if (ev.pointerId === pointerId) this.endDrag(attrs);
    };
    const cancel = (ev: PointerEvent) => {
      if (ev.pointerId !== pointerId) return;
      this.drag?.stop();
      this.drag = undefined;
      m.redraw();
    };
    window.addEventListener("pointermove", move, { passive: false });
    window.addEventListener("pointerup", up);
    window.addEventListener("pointercancel", cancel);
    const stop = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      window.removeEventListener("pointercancel", cancel);
    };
    this.drag = { tag, index, pointerId, stop };
  }

  /** The dragged tag goes before the first other tag whose middle is below the pointer. */
  private moveDrag(e: PointerEvent) {
    const drag = this.drag;
    if (!drag || !this.list) return;
    const others = [...this.list.querySelectorAll<HTMLElement>("li[data-tag]")].filter((li) => li.dataset.tag !== drag.tag);
    let index = others.findIndex((li) => {
      const rect = li.getBoundingClientRect();
      return e.clientY < rect.top + rect.height / 2;
    });
    if (index < 0) index = others.length;
    if (index !== drag.index) {
      this.drag = { ...drag, index };
      m.redraw();
    }
  }

  private endDrag(attrs: TagPanelAttrs) {
    const drag = this.drag;
    if (!drag) return;
    drag.stop();
    this.drag = undefined;
    const before = attrs.counts.map((count) => count.tag);
    if (before.indexOf(drag.tag) !== drag.index) attrs.onMove(drag.tag, drag.index);
    m.redraw();
  }

  private keepFocus(vnode: m.VnodeDOM, tag: string) {
    if (this.focusTag !== tag) return;
    this.focusTag = undefined;
    (vnode.dom as HTMLElement).focus();
  }

  private stopReordering() {
    this.reordering = false;
    this.drag?.stop();
    this.drag = undefined;
  }
}

/** {@code tags} with {@code tag} at {@code index} among the others. */
function placed(tags: string[], tag: string, index: number): string[] {
  const others = tags.filter((t) => t !== tag);
  return [...others.slice(0, index), tag, ...others.slice(index)];
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
