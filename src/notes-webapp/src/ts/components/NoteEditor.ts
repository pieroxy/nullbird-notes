import m from "mithril";
import { LocalNote } from "../sync/LocalNote";
import { computeSplice } from "../sync/Splice";

export interface NoteEditorAttrs {
  note: LocalNote;
  /** For a conflict copy: the title of the note it was split from. */
  originalTitle?: string;
  /** For a note with unresolved conflict copies: their ids. */
  conflictCopies: string[];
  onEdit: (text: string) => void;
  onDelete: () => void;
  onOpen: (id: string) => void;
  onBack: () => void;
}

/**
 * A plain textarea. Its text is set from the note only when they differ (a change synced from
 * elsewhere), keeping the caret where the user left it relative to the surrounding text.
 */
export class NoteEditor implements m.ClassComponent<NoteEditorAttrs> {
  private textarea?: HTMLTextAreaElement;

  oncreate({ attrs, dom }: m.VnodeDOM<NoteEditorAttrs>) {
    this.textarea = dom.querySelector("textarea")!;
    this.textarea.value = attrs.note.text;
    if (attrs.note.text === "") this.textarea.focus();
  }

  onupdate({ attrs }: m.VnodeDOM<NoteEditorAttrs>) {
    const textarea = this.textarea!;
    const text = attrs.note.text;
    if (textarea.value === text) return;
    const focused = document.activeElement === textarea;
    const start = textarea.selectionStart;
    const end = textarea.selectionEnd;
    const splice = computeSplice(textarea.value, text);
    const oldLength = textarea.value.length;
    textarea.value = text;
    if (focused) textarea.setSelectionRange(shift(start, splice, oldLength, text.length), shift(end, splice, oldLength, text.length));
  }

  view({ attrs }: m.Vnode<NoteEditorAttrs>): m.Children {
    const note = attrs.note;
    return m("section.editor", [
      m(".editorhead", [
        m("button.link.back", { onclick: attrs.onBack }, "‹ Notes"),
        m("span.spacer"),
        m("button.secondary", {
          onclick: () => {
            if (confirm("Delete this note?")) attrs.onDelete();
          },
        }, "Delete"),
      ]),
      note.info?.conflictOf ? m(".banner.conflict", [
        "Conflict copy: this device's version of ",
        m("button.link", { onclick: () => attrs.onOpen(note.info!.conflictOf!.noteId) }, "“" + (attrs.originalTitle || "a note") + "”"),
        ", which couldn't be merged with changes made elsewhere. Compare the two and keep what you want.",
      ]) : null,
      attrs.conflictCopies.length > 0 ? m(".banner.conflict", [
        "Another device's changes to this note couldn't be merged: they are in ",
        attrs.conflictCopies.map((id, i) => [
          i > 0 ? ", " : "",
          m("button.link", { onclick: () => attrs.onOpen(id) }, attrs.conflictCopies.length > 1 ? "conflict copy " + (i + 1) : "a conflict copy"),
        ]),
        ".",
      ]) : null,
      note.error ? m(".banner.error", "Not saved on the server: " + note.error.message + " Your text is kept on this device.") : null,
      m("textarea", {
        spellcheck: true,
        placeholder: "Start typing…",
        oninput: (e: Event) => attrs.onEdit((e.target as HTMLTextAreaElement).value),
      }),
    ]);
  }
}

/** Where a caret at {@code position} lands once {@code splice} is applied. */
function shift(position: number, splice: { prefixLength: number; suffixLength: number; middle: string }, oldLength: number, newLength: number): number {
  if (position <= splice.prefixLength) return position;
  if (position >= oldLength - splice.suffixLength) return position + newLength - oldLength;
  return splice.prefixLength + splice.middle.length;
}
