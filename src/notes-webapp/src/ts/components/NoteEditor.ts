import { history, historyKeymap, indentWithTab, insertNewline, isolateHistory, standardKeymap } from "@codemirror/commands";
import { Annotation, ChangeSet, Compartment, EditorState, Extension, RangeSetBuilder, StateEffect, StateField, Transaction } from "@codemirror/state";
import { Decoration, DecorationSet, EditorView, keymap, placeholder } from "@codemirror/view";
import m from "mithril";
import { LocalNote } from "../sync/LocalNote";
import { computeSplice } from "../sync/Splice";
import { continueList } from "../utils/ListContinuation";
import { formatList, ListKind, taskShorthand } from "../utils/ListFormat";
import { MAX_MATCHES, Range } from "../utils/Search";
import { iconButton } from "./Icons";
import { markdownStyle } from "./MarkdownStyle";
import { toggledTask } from "./MarkdownTree";
import { MarkdownView, ScrollAnchor, viewTop } from "./MarkdownView";
import { PublishPanel } from "./PublishPanel";
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
  markdown: boolean;
  /** Its Markdown rendered instead of the editor. */
  viewing: boolean;
  onView: (viewing: boolean) => void;
  /** The publish panel shown, with the note's link if it's published. */
  publishing?: { token: string | undefined; onClose: () => void };
  onEdit: (text: string) => void;
  onAddTag: (tag: string) => boolean;
  onRemoveTag: (tag: string) => void;
  find?: FindAttrs;
  /** Changed to make the lines at the caret or the selection a list (see {@link formatList}). */
  listCommand?: { kind: ListKind; seq: number };
  /** Changed to replace the selection with {@code text} (none: nothing), then take the focus if {@code focus}. */
  insertCommand?: { text: string; focus: boolean; seq: number };
}

/** On changes synced from elsewhere: not the user's to undo, nor to report back as an edit. */
const remote = Annotation.define<boolean>();

const setMatches = StateEffect.define<{ matches: Range[]; current: number }>();

/** The search matches, highlighted; kept in place by edits until the next {@link setMatches}. */
const matchField = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(decorations, tr) {
    for (const effect of tr.effects) if (effect.is(setMatches)) return matchDecorations(effect.value.matches, effect.value.current, tr.state.doc.length);
    return decorations.map(tr.changes);
  },
  provide: (field) => EditorView.decorations.from(field),
});

const matchMark = Decoration.mark({ class: "cm-match" });
const currentMark = Decoration.mark({ class: "cm-match cm-match-current" });

function matchDecorations(matches: Range[], current: number, length: number): DecorationSet {
  const builder = new RangeSetBuilder<Decoration>();
  matches.slice(0, MAX_MATCHES).forEach((match, i) => {
    if (match.end <= length) builder.add(match.start, match.end, i === current ? currentMark : matchMark);
  });
  return builder.finish();
}

/** Enter on a list item continues the list (see {@link continueList}); Shift+Enter is a plain line break. */
const listKeymap = keymap.of([{
  key: "Enter",
  run: (view) => {
    const { main } = view.state.selection;
    if (view.state.selection.ranges.length > 1 || !main.empty) return insertNewline(view);
    const line = view.state.doc.lineAt(main.head);
    const edit = continueList(line.text, main.head - line.from);
    if (!edit) return insertNewline(view);
    const to = line.from + edit.from + edit.insert.length;
    view.dispatch({
      changes: { from: line.from + edit.from, to: line.from + edit.to, insert: edit.insert },
      selection: { anchor: edit.insert === "" ? line.from : to },
      scrollIntoView: true,
      userEvent: "input",
    });
    return true;
  },
  shift: insertNewline,
},
{ key: "Mod-Shift-8", run: (view) => makeList(view, "bullet") },
{ key: "Mod-Shift-7", run: (view) => makeList(view, "number") },
{ key: "Mod-Shift-9", run: (view) => makeList(view, "task") },
]);

/** One undoable edit; the caret stays after a marker inserted at it. */
function makeList(view: EditorView, kind: ListKind): boolean {
  const { state } = view;
  const { main } = state.selection;
  const edits = formatList(state.doc.toString(), main.from, main.to, kind);
  if (edits.length === 0) return true;
  const changes = ChangeSet.of(edits, state.doc.length);
  view.dispatch({ changes, selection: state.selection.map(changes, 1), scrollIntoView: true, userEvent: "input" });
  return true;
}

/** "[]" then a space at the start of a line becomes an unchecked task; undo brings the "[] " back. */
const taskShorthandInput = EditorView.inputHandler.of((view, from, to, text) => {
  if (text !== " " || from !== to || view.state.selection.ranges.length > 1) return false;
  const line = view.state.doc.lineAt(from);
  const indent = taskShorthand(view.state.sliceDoc(line.from, from));
  if (indent === null) return false;
  view.dispatch({ changes: { from, insert: " " }, selection: { anchor: from + 1 }, userEvent: "input.type" });
  const start = line.from + indent.length;
  view.dispatch({
    changes: { from: start, to: from + 1, insert: "- [ ] " },
    selection: { anchor: start + 6 },
    annotations: isolateHistory.of("before"),
    userEvent: "input",
  });
  return true;
});

/**
 * Styles inside the editor's shadow root, where the page's stylesheet doesn't apply: CSS variables
 * still do.
 */
const theme = EditorView.theme({
  "&": { flex: "1", minHeight: "0", background: "var(--bg)", color: "var(--text)" },
  "&.cm-focused": { outline: "none" },
  ".cm-scroller": { fontFamily: "var(--font-note)", fontSize: "var(--editor-font-size)", lineHeight: "1.5" },
  ".cm-content": { padding: "16px 0", caretColor: "var(--text)" },
  ".cm-line": { padding: "0 20px" },
  "&.monospace .cm-scroller": { fontFamily: "var(--font-mono)", fontSize: "var(--editor-mono-font-size)" },
  ".cm-placeholder": { color: "var(--text-muted)" },
  ".cm-match": { background: "var(--match-bg)", borderRadius: "2px" },
  ".cm-match-current": { background: "var(--match-current-bg)", color: "var(--text-on-accent)" },
});

/**
 * CodeMirror, in a shadow root: there it styles itself with a constructable stylesheet, where in
 * the document it would add a <style> element, which the CSP refuses. Its text is set from the
 * note only when they differ (a change synced from elsewhere), as the smallest change, so that the
 * caret and the undo history stay where they apply.
 * <p>
 * Stepping through search matches selects each one without focusing the editor: a phone doesn't
 * bring its keyboard up over the note.
 * <p>
 * Viewing, the editor stays, hidden: a task ticked in the view is an edit of its own (undoable, as
 * typed), and editing again finds the caret and history where they were.
 */
export class NoteEditor implements m.ClassComponent<NoteEditorAttrs> {
  private editor?: EditorView;
  private onEdit?: (text: string) => void;
  private readonly options = new Compartment();
  private optionsKey?: string;
  /** The text last reported or applied: comparing with it spares rebuilding the document's string. */
  private text = "";
  private lastJump?: number;
  private lastMatches?: Range[];
  private lastCurrent?: number;
  private lastListSeq?: number;
  private lastInsertSeq?: number;
  private wasViewing = false;
  /** Where the caret goes once the editor shows: where the view was double-clicked. */
  private caretOnShow?: number;
  /** Switching between editor and view: what the one left showed at its top, for the other to show there too. */
  private anchor?: ScrollAnchor;
  private dom?: HTMLElement;

  oncreate({ attrs, dom }: m.VnodeDOM<NoteEditorAttrs>) {
    this.dom = dom as HTMLElement;
    this.onEdit = attrs.onEdit;
    this.text = attrs.note.text;
    this.optionsKey = optionsKey(attrs);
    this.lastListSeq = attrs.listCommand?.seq;
    this.lastInsertSeq = attrs.insertCommand?.seq;
    const host = dom.querySelector(".cmhost")!;
    const root = host.attachShadow({ mode: "open" });
    this.editor = new EditorView({
      root,
      parent: root,
      state: EditorState.create({
        doc: attrs.note.text,
        extensions: [
          history(),
          listKeymap,
          keymap.of([...standardKeymap, ...historyKeymap, indentWithTab]),
          EditorView.contentAttributes.of({ spellcheck: "true", autocorrect: "on", autocapitalize: "sentences" }),
          placeholder("Start typing…"),
          theme,
          matchField,
          this.options.of(options(attrs)),
          EditorView.updateListener.of((update) => {
            if (update.docChanged && !update.transactions.some((tr) => tr.annotation(remote))) {
              this.text = update.state.doc.toString();
              this.onEdit!(this.text);
            }
          }),
        ],
      }),
    });
    this.wasViewing = attrs.viewing;
    if (attrs.note.text === "" && !attrs.viewing) this.editor.focus();
    this.showFind(attrs);
  }

  /** Before the side being left is gone. */
  onbeforeupdate({ attrs }: m.Vnode<NoteEditorAttrs>): boolean {
    if (attrs.viewing !== this.wasViewing) this.anchor = this.wasViewing ? viewTop(this.dom!) : editorTop(this.editor!);
    return true;
  }

  onupdate({ attrs }: m.VnodeDOM<NoteEditorAttrs>) {
    const view = this.editor!;
    this.onEdit = attrs.onEdit;
    this.syncText(attrs.note.text);
    if (optionsKey(attrs) !== this.optionsKey) {
      this.optionsKey = optionsKey(attrs);
      view.dispatch({ effects: this.options.reconfigure(options(attrs)) });
    }
    this.showFind(attrs);
    this.showOrHide(attrs);
    if (attrs.listCommand && attrs.listCommand.seq !== this.lastListSeq) {
      this.lastListSeq = attrs.listCommand.seq;
      if (!attrs.viewing) {
        makeList(view, attrs.listCommand.kind);
        view.focus();
      }
    }
    if (attrs.insertCommand && attrs.insertCommand.seq !== this.lastInsertSeq) {
      this.lastInsertSeq = attrs.insertCommand.seq;
      if (!attrs.viewing) {
        if (attrs.insertCommand.text) view.dispatch(view.state.replaceSelection(attrs.insertCommand.text), { scrollIntoView: true, userEvent: "input" });
        if (attrs.insertCommand.focus) view.focus();
      }
    }
  }

  onremove() {
    this.editor?.destroy();
  }

  private syncText(text: string) {
    if (text === this.text) return;
    const view = this.editor!;
    const current = view.state.doc.toString();
    this.text = text;
    if (current === text) return;
    const splice = computeSplice(current, text);
    view.dispatch({
      changes: { from: splice.prefixLength, to: current.length - splice.suffixLength, insert: splice.middle },
      annotations: [remote.of(true), Transaction.addToHistory.of(false)],
    });
  }

  /**
   * Shown, it gets the focus, unless a search is on and the view wasn't double-clicked: the search
   * field keeps it, and a phone keeps its keyboard down.
   */
  private showOrHide(attrs: NoteEditorAttrs) {
    if (attrs.viewing === this.wasViewing) return;
    this.wasViewing = attrs.viewing;
    const view = this.editor!;
    if (attrs.viewing) {
      view.contentDOM.blur();
      return;
    }
    view.requestMeasure();
    const caret = this.caretOnShow;
    this.caretOnShow = undefined;
    if (caret !== undefined && caret <= view.state.doc.length) {
      view.dispatch({ selection: { anchor: caret }, effects: EditorView.scrollIntoView(caret, { y: "center" }) });
    } else if (this.anchor && this.anchor.pos <= view.state.doc.length) {
      view.dispatch({ effects: EditorView.scrollIntoView(this.anchor.pos, { y: "start", yMargin: Math.max(0, this.anchor.offset) }) });
    }
    if (caret === undefined && attrs.find) return;
    view.focus();
  }

  private toggleTask(marker: number) {
    const view = this.editor!;
    const change = toggledTask(view.state.doc.toString(), marker);
    if (change) view.dispatch({ changes: change, userEvent: "input" });
  }

  private showFind(attrs: NoteEditorAttrs) {
    const view = this.editor!;
    const matches = attrs.find?.matches ?? [];
    const current = attrs.find?.current ?? -1;
    if (matches !== this.lastMatches || current !== this.lastCurrent) {
      this.lastMatches = matches;
      this.lastCurrent = current;
      view.dispatch({ effects: setMatches.of({ matches, current }) });
    }
    if (!attrs.find || attrs.find.jump === this.lastJump) return;
    this.lastJump = attrs.find.jump;
    const match = matches[current];
    if (!match || match.end > view.state.doc.length) return;
    view.dispatch({
      selection: { anchor: match.start, head: match.end },
      effects: EditorView.scrollIntoView(match.start, { y: "center" }),
    });
  }

  view({ attrs }: m.Vnode<NoteEditorAttrs>): m.Children {
    const note = attrs.note;
    return m("section.editor", [
      note.error ? m(".banner.error", "Not saved on the server: " + note.error.message + " Your text is kept on this device.") : null,
      note.metaError ? m(".banner.error", "A change to the tags or options was refused by the server: " + note.metaError.message) : null,
      attrs.find ? findBar(attrs.find) : null,
      attrs.publishing ? m(PublishPanel, { noteId: note.id, token: attrs.publishing.token, onClose: attrs.publishing.onClose }) : null,
      m(".cmhost" + (attrs.viewing ? ".hidden" : "")),
      attrs.viewing ? m(MarkdownView, {
        text: note.text,
        monospace: attrs.monospace,
        find: attrs.find,
        anchor: this.anchor,
        onToggleTask: (marker: number) => this.toggleTask(marker),
        onEditAt: (pos: number) => {
          this.caretOnShow = pos;
          attrs.onView(false);
        },
      }) : null,
      m(TagEditor, { tags: attrs.tags, onAdd: attrs.onAddTag, onRemove: attrs.onRemoveTag }),
    ]);
  }
}

/** The line at the top of the editor, and how far below the top it starts (negative: partly scrolled off). */
function editorTop(view: EditorView): ScrollAnchor {
  const rect = view.scrollDOM.getBoundingClientRect();
  const pos = view.posAtCoords({ x: rect.left + rect.width / 2, y: rect.top + 1 }, false);
  const line = view.lineBlockAt(pos);
  return { pos: line.from, offset: view.documentTop + line.top - rect.top };
}

function optionsKey(attrs: NoteEditorAttrs): string {
  return attrs.monospace + " " + attrs.nowrap + " " + attrs.markdown;
}

function options(attrs: NoteEditorAttrs): Extension {
  return [
    attrs.nowrap ? [] : EditorView.lineWrapping,
    EditorView.editorAttributes.of({ class: attrs.monospace ? "monospace" : "" }),
    attrs.markdown ? [markdownStyle, taskShorthandInput] : [],
  ];
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
