import m from "mithril";
import { LocalNote } from "../sync/LocalNote";
import { sortKey } from "../sync/Outcomes";
import { shortDate, titleAndExcerpt } from "../utils/format";
import { isActive, NO_FILTER, TagFilter, toggle } from "../utils/TagFilter";
import { iconButton } from "./Icons";

export interface NoteListAttrs {
  /** Those the filter lets through. */
  notes: LocalNote[];
  /** All of them. */
  total: number;
  filter: TagFilter;
  onFilter: (filter: TagFilter) => void;
  selectedId?: string;
  onSelect: (id: string) => void;
}

export class NoteList implements m.ClassComponent<NoteListAttrs> {
  view({ attrs }: m.Vnode<NoteListAttrs>): m.Children {
    const filtered = isActive(attrs.filter);
    return m("aside.notelist", [
      filtered ? this.filterStrip(attrs) : null,
      attrs.notes.length > 0 ? m("ul", attrs.notes.map((note) => this.item(note, attrs))) : null,
      attrs.total === 0 ? m(".empty", "No notes yet.") : null,
      attrs.total > 0 && attrs.notes.length === 0 ? m(".empty", [
        m("p", attrs.filter.untagged ? "Every note has tags." : "No note has all of these tags."),
        m("button", { type: "button", onclick: () => attrs.onFilter(NO_FILTER) }, "Clear filter"),
      ]) : null,
      filtered && attrs.notes.length > 0 ? m(".filtercount", attrs.notes.length + " of " + attrs.total + " notes") : null,
    ]);
  }

  /** The active filter, where the tag panel isn't shown next to the list. */
  private filterStrip(attrs: NoteListAttrs): m.Children {
    const { filter, onFilter } = attrs;
    return m(".filterstrip", [
      filter.untagged ? m("span.tag", [m("span.tagname", "Untagged"), iconButton("x", "Show all notes", () => onFilter(NO_FILTER), "tagremove")]) : null,
      filter.tags.map((tag) => m("span.tag", { key: tag }, [
        m("span.tagname", tag),
        iconButton("x", "Stop filtering on " + tag, () => onFilter(toggle(filter, tag)), "tagremove"),
      ])),
      m("button.link.clearfilter", { type: "button", onclick: () => onFilter(NO_FILTER) }, "Clear"),
    ]);
  }

  private item(note: LocalNote, attrs: NoteListAttrs): m.Children {
    const { title, excerpt } = titleAndExcerpt(note.text);
    const unsynced = note.dirty || note.pending !== undefined;
    return m("li" + (note.id === attrs.selectedId ? ".selected" : ""), { key: note.id, onclick: () => attrs.onSelect(note.id) }, [
      m(".itemhead", [
        m("span.title" + (title ? "" : ".untitled"), title || "New note"),
        m("span.date", shortDate(sortKey(note))),
      ]),
      m(".itemfoot", [
        m("span.excerpt", excerpt),
        note.error ? m("span.chip.error", { title: note.error.message }, "not saved") : null,
        unsynced && !note.error ? m("span.dot", { title: "Not synced yet" }) : null,
      ]),
    ]);
  }
}
