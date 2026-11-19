import m from "mithril";
import { LocalNote } from "../sync/LocalNote";
import { sortKey } from "../sync/Outcomes";
import { shortDate, titleAndExcerpt } from "../utils/format";

export interface NoteListAttrs {
  notes: LocalNote[];
  selectedId?: string;
  onSelect: (id: string) => void;
  onCreate: () => void;
}

export class NoteList implements m.ClassComponent<NoteListAttrs> {
  view({ attrs }: m.Vnode<NoteListAttrs>): m.Children {
    return m("aside.notelist", [
      m(".notelisthead", m("button", { onclick: attrs.onCreate }, "New note")),
      attrs.notes.length === 0 ? m(".empty", "No notes yet.") : m("ul", attrs.notes.map((note) => this.item(note, attrs))),
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
        note.info?.conflictOf ? m("span.chip.conflict", "conflict") : null,
        unsynced && !note.error ? m("span.dot", { title: "Not synced yet" }) : null,
      ]),
    ]);
  }
}
