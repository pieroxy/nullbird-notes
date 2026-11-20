import m from "mithril";
import { LocalNote } from "../sync/LocalNote";
import { computeSplice } from "../sync/Splice";
import { continueList, TextEdit } from "../utils/ListContinuation";
import { MAX_MATCHES, Range } from "../utils/Search";
import { scrollToRange } from "../utils/TextareaScroll";
import { iconButton } from "./Icons";
import { TagEditor } from "./TagEditor";

/** While a search is active. */
export interface FindAttrs {
  /** In the note's text; one more than {@link MAX_MATCHES} when there are more. */
  matches: Range[];
  /** The one selected, -1 before stepping to any. */
  current: number;
  /** Changed to select the current match. */
  jump: number;
  /** Matched by a tag only: nothing in the text. */
  byTag: boolean;
  onStep: (direction: 1 | -1) => void;
  onClear: () => void;
}

export interface NoteEditorAttrs {
  note: LocalNote;
  tags: string[];
  monospace: boolean;
  nowrap: boolean;
  onEdit: (text: string) => void;
  onAddTag: (tag: string) => boolean;
  onRemoveTag: (tag: string) => void;
  find?: FindAttrs;
}

/**
 * A plain textarea. Its text is set from the note only when they differ (a change synced from
 * elsewhere), keeping the caret where the user left it relative to the surrounding text.
 * <p>
 * Stepping through search matches selects each one, which a textarea only shows when focused: it
 * is made read-only meanwhile, so that a phone doesn't bring its keyboard up over the note, and
 * Enter steps on instead of replacing the match. Tapping or typing in the text makes it editable.
 */
export class NoteEditor implements m.ClassComponent<NoteEditorAttrs> {
  private textarea?: HTMLTextAreaElement;
  /** Shift+Enter is a plain line break; its beforeinput doesn't tell it from Enter. */
  private shiftEnter = false;
  private lastJump?: number;
  private finding = false;

  oncreate({ attrs, dom }: m.VnodeDOM<NoteEditorAttrs>) {
    this.textarea = dom.querySelector("textarea")!;
    this.textarea.value = attrs.note.text;
    if (attrs.note.text === "") this.textarea.focus();
    this.jumpIfAsked(attrs);
  }

  onupdate({ attrs }: m.VnodeDOM<NoteEditorAttrs>) {
    this.syncText(attrs.note.text);
    this.jumpIfAsked(attrs);
  }

  private syncText(text: string) {
    const textarea = this.textarea!;
    if (textarea.value === text) return;
    const focused = document.activeElement === textarea;
    const start = textarea.selectionStart;
    const end = textarea.selectionEnd;
    const splice = computeSplice(textarea.value, text);
    const oldLength = textarea.value.length;
    textarea.value = text;
    if (focused) textarea.setSelectionRange(shift(start, splice, oldLength, text.length), shift(end, splice, oldLength, text.length));
  }

  private jumpIfAsked(attrs: NoteEditorAttrs) {
    const find = attrs.find;
    if (!find || find.jump === this.lastJump) return;
    this.lastJump = find.jump;
    const match = find.matches[find.current];
    if (!match) return;
    const textarea = this.textarea!;
    textarea.readOnly = true;
    this.finding = true;
    textarea.focus({ preventScroll: true });
    textarea.setSelectionRange(match.start, match.end);
    scrollToRange(textarea, match);
  }

  private endFind() {
    if (!this.finding) return;
    this.finding = false;
    this.textarea!.readOnly = false;
  }

  view({ attrs }: m.Vnode<NoteEditorAttrs>): m.Children {
    const note = attrs.note;
    return m("section.editor", [
      note.error ? m(".banner.error", "Not saved on the server: " + note.error.message + " Your text is kept on this device.") : null,
      note.metaError ? m(".banner.error", "A change to the tags or options was refused by the server: " + note.metaError.message) : null,
      attrs.find ? findBar(attrs.find) : null,
      m("textarea" + (attrs.monospace ? ".monospace" : "") + (attrs.nowrap ? ".nowrap" : ""), {
        spellcheck: true,
        wrap: attrs.nowrap ? "off" : "soft",
        placeholder: "Start typing…",
        onpointerdown: () => this.endFind(),
        onblur: () => this.endFind(),
        onkeydown: (e: KeyboardEvent) => {
          if (this.finding && e.key === "Enter" && attrs.find) {
            e.preventDefault();
            attrs.find.onStep(e.shiftKey ? -1 : 1);
            return;
          }
          // Typing replaces the match, as in any editor.
          if (this.finding && (e.key === "Escape" || e.key === "Backspace" || e.key === "Delete" || (e.key.length === 1 && !e.ctrlKey && !e.metaKey))) {
            this.endFind();
          }
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

function findBar(find: FindAttrs): m.Children {
  const capped = find.matches.length > MAX_MATCHES;
  const count = capped ? MAX_MATCHES + "+" : String(find.matches.length);
  let label: string;
  if (find.matches.length === 0) label = find.byTag ? "Matched by a tag" : "No match in this note";
  else if (find.current < 0) label = count + (find.matches.length > 1 ? " matches" : " match");
  else label = "Match " + (find.current + 1) + " of " + count;
  const none = find.matches.length === 0;
  return m(".findbar", [
    none ? null : iconButton("chevron-up", "Previous match (Shift+Enter)", () => find.onStep(-1)),
    none ? null : iconButton("chevron-down", "Next match (Enter)", () => find.onStep(1)),
    m("span.findcount", label),
    m("span.spacer"),
    iconButton("x", "Clear the search", find.onClear),
  ]);
}

/** Where a caret at {@code position} lands once {@code splice} is applied. */
function shift(position: number, splice: { prefixLength: number; suffixLength: number; middle: string }, oldLength: number, newLength: number): number {
  if (position <= splice.prefixLength) return position;
  if (position >= oldLength - splice.suffixLength) return position + newLength - oldLength;
  return splice.prefixLength + splice.middle.length;
}
