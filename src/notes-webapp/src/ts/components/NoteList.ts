import m from "mithril";
import { LocalNote } from "../sync/LocalNote";
import { isOn, PINNED, publishedToken } from "../sync/Metadata";
import { sortKey } from "../sync/Outcomes";
import { shortDate, titleAndExcerpt } from "../utils/format";
import { noteHasMergeMark } from "../utils/MergeMarks";
import { TagOptions } from "../auto/api-types";
import { noteStripe, tagClasses, tagDot } from "../utils/TagLook";
import { Snippet } from "../utils/Search";
import { isActive, NO_FILTER, TagFilter, toggle } from "../utils/TagFilter";
import { icon, iconButton } from "./Icons";
import { noteSearch, NoteSearchAttrs } from "./NoteSearch";
import { leafOf, parentOf, SEPARATOR, withoutRedundant } from "../utils/TagTree";

export interface NoteListAttrs extends NoteSearchAttrs {
  /** Those the tag filter and the search let through. */
  notes: LocalNote[];
  /** All of them. */
  total: number;
  filter: TagFilter;
  onFilter: (filter: TagFilter) => void;
  /** While searching: the excerpt around the first match, if there's one past the title. */
  snippetOf?: (note: LocalNote) => Snippet | undefined;
  selectedId?: string;
  onSelect: (id: string) => void;
  /** How each tag looks (see TagLook). */
  tagOptions: { [tag: string]: TagOptions };
  /** The colors of each note's stripe (see noteStripes). */
  stripesOf: (note: LocalNote) => string[];
  /** Searching: notes of hidden tags that match too, not listed. */
  hiddenMatches: number;
  onShowHidden: () => void;
  /** In the trash view: deletes every note listed for good. */
  onEmptyTrash: () => void;
}

export class NoteList implements m.ClassComponent<NoteListAttrs> {
  /** Where the selected note was last drawn. */
  private selectedIndex = -1;

  view({ attrs }: m.Vnode<NoteListAttrs>): m.Children {
    const filtered = isActive(attrs.filter);
    const searching = attrs.snippetOf !== undefined;
    return m("aside.notelist", [
      m(".searchrow", noteSearch(attrs)),
      filtered ? this.filterStrip(attrs) : null,
      attrs.filter.trash && attrs.notes.length > 0 ? m(".trashbar", [
        m("span.muted", "Deleted notes stay here until deleted forever."),
        m("button.link.danger", { type: "button", onclick: attrs.onEmptyTrash }, "Empty the trash"),
      ]) : null,
      attrs.notes.length > 0 ? m("ul", attrs.notes.map((note) => this.item(note, attrs))) : null,
      attrs.total === 0 && !attrs.filter.trash ? m(".empty", "No notes yet.") : null,
      (attrs.total > 0 || attrs.filter.trash) && attrs.notes.length === 0 ? m(".empty", searching ? [
        m("p", "No note matches this search."),
        m("button", { type: "button", onclick: () => attrs.onSearch("") }, "Clear search"),
      ] : [
        m("p", attrs.filter.trash ? "The trash is empty." : attrs.filter.untagged ? "Every note has tags."
          : attrs.filter.merged ? "No merge markers left to check." : "No note has all of these tags."),
        m("button", { type: "button", onclick: () => attrs.onFilter(NO_FILTER) }, "Clear filter"),
      ]) : null,
      (filtered || searching) && attrs.notes.length > 0 ? m(".filtercount", attrs.notes.length + " of " + attrs.total + " notes") : null,
      attrs.hiddenMatches > 0 ? m(".hiddenmatches", [
        m("span", (attrs.hiddenMatches === 1 ? "1 more" : attrs.hiddenMatches + " more") + " in hidden tags. "),
        m("button.link", { type: "button", onclick: attrs.onShowHidden }, "Show"),
      ]) : null,
    ]);
  }

  /** The active filter, where the tag panel isn't shown next to the list. */
  private filterStrip(attrs: NoteListAttrs): m.Children {
    const { filter, onFilter } = attrs;
    return m(".filterstrip", [
      filter.untagged ? m("span.tag", [m("span.tagname", "Untagged"), iconButton("x", "Show all notes", () => onFilter(NO_FILTER), "tagremove")]) : null,
      filter.merged ? m("span.tag", [m("span.tagname", "Merge markers"), iconButton("x", "Show all notes", () => onFilter(NO_FILTER), "tagremove")]) : null,
      filter.trash ? m("span.tag", [m("span.tagname", "Trash"), iconButton("x", "Show all notes", () => onFilter(NO_FILTER), "tagremove")]) : null,
      // "work/clients" says "work" too, selected above it: the most specific only.
      withoutRedundant(filter.tags).map((tag) => m("span.tag" + tagClasses(attrs.tagOptions[tag]), { key: tag }, [
        m("span.tagname", [tagDot(attrs.tagOptions[tag]), parentOf(tag) ? m("span.tagpath", parentOf(tag) + SEPARATOR) : null, leafOf(tag)]),
        iconButton("x", "Stop filtering on " + tag, () => onFilter(toggle(filter, tag)), "tagremove"),
      ])),
      m("button.link.clearfilter", { type: "button", onclick: () => onFilter(NO_FILTER) }, "Clear"),
    ]);
  }

  private item(note: LocalNote, attrs: NoteListAttrs): m.Children {
    // A deleted note this device never had: the server's title.
    const { title, excerpt } = note.text || !note.info ? titleAndExcerpt(note.text) : { title: note.info.title, excerpt: "" };
    const snippet = attrs.snippetOf?.(note);
    const unsynced = note.dirty || note.pending !== undefined;
    const selected = note.id === attrs.selectedId;
    // Pinning or unpinning moves the selected note: keep it in sight.
    const keepInSight = (vnode: m.VnodeDOM) => {
      if (!selected) return;
      const index = attrs.notes.indexOf(note);
      if (index !== this.selectedIndex) (vnode.dom as HTMLElement).scrollIntoView({ block: "nearest" });
      this.selectedIndex = index;
    };
    return m("li" + (selected ? ".selected" : ""), {
      key: note.id,
      onclick: () => attrs.onSelect(note.id),
      oncreate: keepInSight,
      onupdate: keepInSight,
    }, [
      noteStripe(attrs.stripesOf(note)),
      m(".itemhead", [
        m("span.title" + (title ? "" : ".untitled"), title || "New note"),
        publishedToken(note) ? m("span.flag", { title: "Published", "aria-label": "Published" }, icon("globe")) : null,
        isOn(note, PINNED) ? m("span.flag", { title: "Pinned", "aria-label": "Pinned" }, icon("pin")) : null,
        noteHasMergeMark(note.id, note.text) ? m("span.mergedot", { title: "Merge markers to check", "aria-label": "Merge markers to check" }) : null,
        m("span.date", shortDate(sortKey(note))),
      ]),
      m(".itemfoot", [
        m("span.excerpt", snippet ? snippet.map((piece) => piece.match ? m("b", piece.text) : piece.text) : excerpt),
        note.error ? m("span.chip.error", { title: note.error.message }, "not saved") : null,
        unsynced && !note.error ? m("span.dot", { title: "Not synced yet" }) : null,
      ]),
    ]);
  }
}
