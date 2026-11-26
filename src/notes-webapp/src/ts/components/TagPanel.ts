import m from "mithril";
import { isActive, NO_FILTER, TagCount, TagFilter, toggle, toggleMerged, toggleTrash, toggleUntagged, visibleTags } from "../utils/TagFilter";
import { TagOptions } from "../auto/api-types";
import { tagClasses, tagDot } from "../utils/TagLook";
import { icon, iconButton, IconName } from "./Icons";
import { ancestorsOf, isUnder, leafOf, parentOf, siblingsOf, withSiblingMoved } from "../utils/TagTree";

export interface TagPanelAttrs {
  filter: TagFilter;
  /** In the order to list them: each tag before those under it (see TagTree.treeOrder). */
  counts: TagCount[];
  /** What's typed in the tagSearch field above the panel. */
  search: string;
  untaggedCount: number;
  /** Notes with merge marks to check (see MergeMarks): listed only when there are some, or while filtering on them. */
  mergedCount: number;
  /** Notes in the trash: listed only when there are some, or while it's shown. */
  trashCount: number;
  onChange: (filter: TagFilter) => void;
  /** Puts {@code tag}, and the tags under it, at {@code index} among its siblings (see TagTree). */
  onMove: (tag: string, index: number) => void;
  /** Moves {@code tag} {@code by} places among its siblings (-1: up), from where it is now: keys can repeat faster than redraws. */
  onMoveBy: (tag: string, by: number) => void;
  /** Opens {@code tag}'s settings: its name, removing it (see TagSettings). */
  onSettings: (tag: string) => void;
  /** How each tag looks (see TagLook): tags with none aren't in it. */
  tagOptions: { [tag: string]: TagOptions };
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
 * or, in edit mode, every tag: to put in order, by dragging the handle (mouse or touch) or with
 * Alt+Up/Down, and to open each one's settings. Not while the search field narrows them.
 * <p>
 * Nested tags (see TagTree) follow the selection: a selected tag shows the tags right under it,
 * indented; a tag with tags under it has a "›" after its name. In edit mode, every tag shows, under
 * the one above it, and moves among its siblings only, with the tags under it.
 */
export class TagPanel implements m.ClassComponent<TagPanelAttrs> {
  private reordering = false;
  private drag?: Drag;
  /** When the last drag ended: the click that follows releasing it mustn't open the tag's settings. */
  private dragEndedAt = 0;
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
        m("p.reorderhint", "Drag to reorder, or Alt+↑ and Alt+↓. ⚙ for a tag's settings."),
        m("button.plain.reorder", { type: "button", onclick: () => this.stopReordering() }, "Done"),
      ] : [
        attrs.mergedCount > 0 || filter.merged
          ? tagItem("Merge markers", attrs.mergedCount, filter.merged, () => onChange(toggleMerged(filter)), "merged") : null,
        tagItem("Untagged notes", attrs.untaggedCount, filter.untagged, () => onChange(toggleUntagged(filter)), "untagged"),
        attrs.trashCount > 0 || filter.trash
          ? tagItem("Trash", attrs.trashCount, !!filter.trash, () => onChange(toggleTrash(filter)), "trash", undefined, "trash-2") : null,
        m(".footactions", [
          isActive(filter) ? m("button.plain.clearfilter", { type: "button", onclick: () => onChange(NO_FILTER) }, "Clear filter") : null,
          attrs.counts.length > 0 ? m("button.plain.reorder", {
            type: "button", disabled: searching, title: searching ? "Clear the search to edit the tags" : "Reorder, rename or remove tags",
            onclick: () => (this.reordering = true),
          }, "Edit") : null,
        ]),
      ]),
    ]);
  }

  private filterList(attrs: TagPanelAttrs): m.Children {
    const { filter, onChange } = attrs;
    const shown = visibleTags(attrs.counts, filter, attrs.search);
    const searching = attrs.search.trim() !== "";
    const narrowing = shown.some((count) => !count.selected);
    const listed = new Set(shown.map((count) => count.tag));
    return m("ul", [
      attrs.counts.length === 0 ? m("li.empty", "No tags yet: add some under a note.") : null,
      attrs.counts.length > 0 && shown.length === 0 ? m("li.empty", "No tag matches.") : null,
      // One keyed list, without holes: mithril refuses keyed and unkeyed siblings mixed, and nulls among
      // keyed ones. " none" can't be a tag: tags have no spaces.
      [
        ...shown.map((count) => {
          // Under the nearest tag shown above it, named from there; searching, by its whole name.
          const above = searching ? [] : ancestorsOf(count.tag).filter((tag) => listed.has(tag));
          const label = above.length ? count.tag.slice(above[above.length - 1].length + 1) : count.tag;
          return m("li", { key: count.tag, style: { "--depth": String(above.length) } },
            tagItem(label, count.count, count.selected, () => onChange(toggle(filter, count.tag)), "", attrs.tagOptions[count.tag], undefined,
              !!count.hasChildren && !count.selected, count.tag));
        }),
        ...(filter.tags.length > 0 && !searching && !narrowing ? [m("li.empty", { key: " none" }, "No other tag narrows this further.")] : []),
      ],
    ]);
  }

  /** Every tag, the dragged one shown where it would go. */
  private reorderList(attrs: TagPanelAttrs): m.Children {
    const tags = attrs.counts.map((count) => count.tag);
    const drag = this.drag;
    const listed = drag ? withSiblingMoved(tags, drag.tag, drag.index) : tags;
    // On update too: the filter list's <ul> may be the element reused.
    const keep = (vnode: m.VnodeDOM) => (this.list = vnode.dom as HTMLElement);
    return m("ul.reorderlist", { oncreate: keep, onupdate: keep }, listed.map((tag) => {
      const siblings = siblingsOf(listed, tag);
      const dragging = !!drag && isUnder(tag, drag.tag);
      return m("li", { key: tag, "data-tag": tag, "data-parent": parentOf(tag) ?? "", style: { "--depth": String(ancestorsOf(tag).length) } },
        [m("button.reorderitem" + (dragging ? ".dragging" : ""), {
        type: "button",
        "aria-label": tag + ", position " + (siblings.indexOf(tag) + 1) + " of " + siblings.length
          + ". Alt+Up or Alt+Down to move it, Enter for its settings.",
        onclick: (e: MouseEvent) => {
          if ((e.target as Element).closest(".grip") || Date.now() - this.dragEndedAt < 500) return;
          attrs.onSettings(tag);
        },
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
          onpointerdown: (e: PointerEvent) => this.startDrag(e, tag, siblings.indexOf(tag), attrs),
        }, icon("grip-vertical")),
        m("span.tagname" + tagClasses(attrs.tagOptions[tag]), { title: tag }, [tagDot(attrs.tagOptions[tag]), leafOf(tag)]),
        hiddenMark(attrs.tagOptions[tag]),
      ]), iconButton("settings", "Settings of " + tag, () => attrs.onSettings(tag), "tagsettings")]);
    }));
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
    // A finger wandering off the list mid-drag would select the page's text (a long press, on iOS).
    document.body.classList.add("dragging");
    const stop = () => {
      document.body.classList.remove("dragging");
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      window.removeEventListener("pointercancel", cancel);
    };
    this.drag = { tag, index, pointerId, stop };
  }

  /** The dragged tag goes before the first of its siblings whose middle is below the pointer. */
  private moveDrag(e: PointerEvent) {
    const drag = this.drag;
    if (!drag || !this.list) return;
    const parent = parentOf(drag.tag) ?? "";
    const others = [...this.list.querySelectorAll<HTMLElement>("li[data-tag]")]
      .filter((li) => li.dataset.tag !== drag.tag && li.dataset.parent === parent);
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
    this.dragEndedAt = Date.now();
    drag.stop();
    this.drag = undefined;
    const before = siblingsOf(attrs.counts.map((count) => count.tag), drag.tag);
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

/**
 * A tag that would leave no note is dimmed but stays clickable: the empty list says why.
 *
 * @param more it has tags under it, not shown: "›" after its name.
 * @param name its whole name, when {@code label} is only its end (a nested tag, under the one above it).
 */
function tagItem(label: string, count: number, selected: boolean, onclick: () => void, cls = "", options?: TagOptions, iconName?: IconName,
  more = false, name = label): m.Children {
  return m("button.tagitem" + (selected ? ".selected" : "") + (count === 0 && !selected ? ".none" : "") + (cls ? "." + cls : ""), {
    type: "button", role: "checkbox", "aria-checked": String(selected), onclick,
    title: name !== label ? name : undefined,
  }, [
    m("span.checkmark", selected ? icon("check") : null),
    m("span.tagname" + tagClasses(options), [iconName ? m("span.tagicon", icon(iconName)) : null, tagDot(options), label,
      more ? m("span.childhint", { "aria-hidden": "true" }, "›") : null]),
    hiddenMark(options),
    m("span.count", count),
  ]);
}

/** A hidden tag's mark: its notes show only when it's selected. */
function hiddenMark(options: TagOptions | undefined): m.Children {
  return options?.hidden ? m("span.hiddenmark", { title: "Hidden: its notes show only when it's selected" }, icon("eye-off")) : null;
}
