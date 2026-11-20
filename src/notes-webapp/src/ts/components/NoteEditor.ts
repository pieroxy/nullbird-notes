import { history, historyKeymap, indentWithTab, insertNewline, standardKeymap } from "@codemirror/commands";
import { Annotation, Compartment, EditorState, Extension, RangeSetBuilder, StateEffect, StateField, Transaction } from "@codemirror/state";
import { Decoration, DecorationSet, EditorView, keymap, placeholder } from "@codemirror/view";
import m from "mithril";
import { LocalNote } from "../sync/LocalNote";
import { computeSplice } from "../sync/Splice";
import { continueList } from "../utils/ListContinuation";
import { MAX_MATCHES, Range } from "../utils/Search";
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
}]);

/**
 * Styles inside the editor's shadow root, where the page's stylesheet doesn't apply: CSS variables
 * still do.
 */
const theme = EditorView.theme({
  "&": { flex: "1", minHeight: "0", background: "var(--bg)", color: "var(--text)" },
  "&.cm-focused": { outline: "none" },
  ".cm-scroller": { fontFamily: "inherit", fontSize: "var(--editor-font-size)", lineHeight: "1.5" },
  ".cm-content": { padding: "16px 0", caretColor: "var(--text)" },
  ".cm-line": { padding: "0 20px" },
  "&.monospace .cm-scroller": { fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace", fontSize: "var(--editor-mono-font-size)" },
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

  oncreate({ attrs, dom }: m.VnodeDOM<NoteEditorAttrs>) {
    this.onEdit = attrs.onEdit;
    this.text = attrs.note.text;
    this.optionsKey = optionsKey(attrs);
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
    if (attrs.note.text === "") this.editor.focus();
    this.showFind(attrs);
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
      m(".cmhost"),
      m(TagEditor, { tags: attrs.tags, onAdd: attrs.onAddTag, onRemove: attrs.onRemoveTag }),
    ]);
  }
}

function optionsKey(attrs: NoteEditorAttrs): string {
  return attrs.monospace + " " + attrs.nowrap;
}

function options(attrs: NoteEditorAttrs): Extension {
  return [
    attrs.nowrap ? [] : EditorView.lineWrapping,
    EditorView.editorAttributes.of({ class: attrs.monospace ? "monospace" : "" }),
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
