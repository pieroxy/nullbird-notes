import m from "mithril";

export interface NoteSearchAttrs {
  search: string;
  onSearch: (search: string) => void;
  /** Enter, Shift+Enter: to the next or previous match in the open note. */
  onStep: (direction: 1 | -1) => void;
}

/** The search field: in the list's top bar on wide screens, above the list on narrow ones. */
export function noteSearch(attrs: NoteSearchAttrs): m.Children {
  return m("input.notesearch", {
    type: "search",
    value: attrs.search,
    placeholder: "Search notes…",
    "aria-label": "Search notes (Ctrl+K)",
    title: "Words anywhere in the note or its tags; \"quotes\" for a phrase",
    oninput: (e: Event) => attrs.onSearch((e.target as HTMLInputElement).value),
    onkeydown: (e: KeyboardEvent) => {
      if (e.key === "Enter") {
        e.preventDefault();
        attrs.onStep(e.shiftKey ? -1 : 1);
      } else if (e.key === "Escape") {
        attrs.onSearch("");
      }
    },
  });
}
