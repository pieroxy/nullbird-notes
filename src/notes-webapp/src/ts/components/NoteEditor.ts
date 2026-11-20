import m from "mithril";
import { LocalNote } from "../sync/LocalNote";
import { computeSplice } from "../sync/Splice";
import { continueList, TextEdit } from "../utils/ListContinuation";
import { TagEditor } from "./TagEditor";

export interface NoteEditorAttrs {
  note: LocalNote;
  /** For a conflict copy: the title of the note it was split from. */
  originalTitle?: string;
  /** For a note with unresolved conflict copies: their ids. */
  conflictCopies: string[];
  tags: string[];
  monospace: boolean;
  nowrap: boolean;
  onEdit: (text: string) => void;
  onOpen: (id: string) => void;
  onAddTag: (tag: string) => boolean;
  onRemoveTag: (tag: string) => void;
}

/**
 * A plain textarea. Its text is set from the note only when they differ (a change synced from
 * elsewhere), keeping the caret where the user left it relative to the surrounding text.
 */
export class NoteEditor implements m.ClassComponent<NoteEditorAttrs> {
  private textarea?: HTMLTextAreaElement;
  /** Shift+Enter is a plain line break; its beforeinput doesn't tell it from Enter. */
  private shiftEnter = false;

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
      note.metaError ? m(".banner.error", "A change to the tags or options was refused by the server: " + note.metaError.message) : null,
      m("textarea" + (attrs.monospace ? ".monospace" : "") + (attrs.nowrap ? ".nowrap" : ""), {
        spellcheck: true,
        wrap: attrs.nowrap ? "off" : "soft",
        placeholder: "Start typing…",
        onkeydown: (e: KeyboardEvent) => {
          if (e.key === "Enter") this.shiftEnter = e.shiftKey;
        },
        // beforeinput, not keydown: Android keyboards don't always send a usable Enter keydown.
        onbeforeinput: (e: InputEvent) => {
          const shiftEnter = this.shiftEnter;
          this.shiftEnter = false;
          if (e.inputType !== "insertLineBreak" && e.inputType !== "insertParagraph") return;
          const textarea = e.target as HTMLTextAreaElement;
          if (shiftEnter || e.isComposing || textarea.selectionStart !== textarea.selectionEnd) return;
          const edit = continueList(textarea.value, textarea.selectionStart);
          if (!edit) return;
          e.preventDefault();
          applyEdit(textarea, edit);
        },
        oninput: (e: Event) => attrs.onEdit((e.target as HTMLTextAreaElement).value),
      }),
      m(TagEditor, { tags: attrs.tags, onAdd: attrs.onAddTag, onRemove: attrs.onRemoveTag }),
    ]);
  }
}

/**
 * Through execCommand, deprecated but the only way to keep the textarea's undo history; it fires
 * the input event like typing does.
 */
function applyEdit(textarea: HTMLTextAreaElement, edit: TextEdit) {
  textarea.setSelectionRange(edit.from, edit.to);
  if (document.execCommand(edit.insert === "" ? "delete" : "insertText", false, edit.insert)) return;
  textarea.setRangeText(edit.insert, edit.from, edit.to, "end");
  textarea.dispatchEvent(new Event("input", { bubbles: true }));
}

/** Where a caret at {@code position} lands once {@code splice} is applied. */
function shift(position: number, splice: { prefixLength: number; suffixLength: number; middle: string }, oldLength: number, newLength: number): number {
  if (position <= splice.prefixLength) return position;
  if (position >= oldLength - splice.suffixLength) return position + newLength - oldLength;
  return splice.prefixLength + splice.middle.length;
}
