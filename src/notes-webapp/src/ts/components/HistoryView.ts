import m from "mithril";
import { ApiEndpoints } from "../auto/ApiEndpoints";
import { HistoryEntry, HistoryKind } from "../auto/api-types";
import { byDay, dayLabel, isThinned, sessionLabel } from "../utils/HistoryDates";
import { DiffLine, diffLines, Fold, folded, hasChanges } from "../utils/TextDiff";
import { icon, iconButton } from "./Icons";
import { MarkdownView } from "./MarkdownView";

/** As in style.scss: one panel at a time. */
const NARROW = "(max-width: 700px)";
const WIDTH_KEY = "notes.historyWidth";
const DEFAULT_WIDTH = 280;
const MIN_WIDTH = 200;
const MIN_VERSION_WIDTH = 320;

export interface HistoryViewAttrs {
  noteId: string;
  title: string;
  /** The note's text now: restoring the same is pointless. */
  current: string;
  markdown: boolean;
  monospace: boolean;
  onClose: () => void;
  /** @param at when the version was made, to say which one was restored. */
  onRestore: (text: string, at: number) => void;
  onRestoreAsNew: (text: string) => void;
  onCopy: (text: string) => void;
}

interface Version {
  text: string;
  /** The version before it, to show what changed; absent for the first one. */
  baseText?: string;
  lines?: DiffLine[];
}

const KIND_LABELS: { [kind in HistoryKind]?: string } = {
  [HistoryKind.CREATED]: "Created",
  [HistoryKind.DELETED]: "Moved to the trash",
  [HistoryKind.RESTORED]: "Restored from the trash",
};

function loadWidth(): number {
  try {
    const stored = Number(localStorage.getItem(WIDTH_KEY));
    if (Number.isFinite(stored) && stored >= MIN_WIDTH) return stored;
  } catch {
    // Unavailable: the default.
  }
  return DEFAULT_WIDTH;
}

function saveWidth(width: number) {
  try {
    localStorage.setItem(WIDTH_KEY, String(width));
  } catch {
    // See loadWidth.
  }
}

/**
 * A note's history, over the whole page: its editing sessions on the left (see NoteHistory on the
 * server), newest first, by day; on the right, the selected one, with what it changed from the one
 * before, or as it was, and what brings it back. On a phone, the list, then the version opened from it.
 */
export class HistoryView implements m.ClassComponent<HistoryViewAttrs> {
  private entries?: HistoryEntry[];
  private error?: string;
  private selected?: number;
  /** A phone: the version shown, rather than the list. */
  private opened = false;
  private showChanges = true;
  private readonly versions = new Map<number, Version>();
  private versionError?: string;
  /** The folds opened in the shown version, by their place in it. */
  private unfolded = new Set<number>();
  private width = loadWidth();
  private onKey?: (e: KeyboardEvent) => void;

  oninit({ attrs }: m.Vnode<HistoryViewAttrs>) {
    ApiEndpoints.NoteHistory.call({ noteId: attrs.noteId })
      .then((output) => {
        this.entries = output.entries;
        if (output.entries.length > 0 && !matchMedia(NARROW).matches) this.select(attrs, 0, false);
      })
      .catch((err: Error) => (this.error = err.message))
      .finally(() => m.redraw());
  }

  oncreate({ attrs, dom }: m.VnodeDOM<HistoryViewAttrs>) {
    this.onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.preventDefault();
      e.stopPropagation();
      if (this.opened && matchMedia(NARROW).matches) this.opened = false;
      else attrs.onClose();
      m.redraw();
    };
    document.addEventListener("keydown", this.onKey, true);
    (dom.querySelector(".historyclose") as HTMLElement | null)?.focus();
  }

  onremove() {
    if (this.onKey) document.removeEventListener("keydown", this.onKey, true);
  }

  view({ attrs }: m.Vnode<HistoryViewAttrs>): m.Children {
    const entry = this.selected !== undefined ? this.entries?.[this.selected] : undefined;
    return m(".historyview" + (this.opened ? ".opened" : ""), {
      role: "dialog", "aria-modal": "true", "aria-label": "History of " + (attrs.title || "this note"),
      style: { "--history-width": this.width + "px" },
    }, [
      m("header.historybar", [
        m(".historylistside", [
          m("h2", ["History", attrs.title ? m("span.historytitle", " of " + attrs.title) : null]),
        ]),
        m(".historyversionside", [
          iconButton("chevron-left", "Back to the versions", () => (this.opened = false), "historyback"),
          entry ? m("span.historywhen", dayLabel(entry.from) + ", " + sessionLabel(entry.from, entry.to)) : m("span"),
          m("span.spacer"),
          iconButton("x", "Close the history (Esc)", () => attrs.onClose(), "historyclose"),
        ]),
      ]),
      m(".historypanes", [
        m("nav.historylist", { "aria-label": "Versions" }, this.list(attrs)),
        m(".resizer.historyresizer", {
          title: "Drag to resize, double-click to reset",
          onpointerdown: (e: PointerEvent) => this.startResize(e),
          ondblclick: () => {
            this.width = DEFAULT_WIDTH;
            saveWidth(this.width);
          },
        }),
        m("section.historyversion", entry ? this.version(attrs, entry) : m(".pagemessage", this.entries?.length ? "Select a version." : "")),
      ]),
    ]);
  }

  private list(attrs: HistoryViewAttrs): m.Children {
    if (this.error) return m(".pagemessage", this.error);
    const entries = this.entries;
    if (!entries) return m(".pagemessage", "Loading…");
    if (entries.length === 0) return m(".pagemessage", "No history yet.");
    const index = new Map(entries.map((entry, i) => [entry, i]));
    const thinned = isThinned(entries[entries.length - 1].from);
    return [
      byDay(entries, (entry) => entry.from).map((day) => m(".historyday", { key: day.label }, [
        m("h3", day.label),
        day.items.map((entry) => {
          const i = index.get(entry)!;
          const kind = KIND_LABELS[entry.kind];
          return m("button.historyentry" + (i === this.selected ? ".selected" : ""), {
            type: "button",
            "aria-current": i === this.selected ? "true" : undefined,
            onclick: () => this.select(attrs, i, true),
          }, [
            m("span.historytime", sessionLabel(entry.from, entry.to)),
            kind ? m("span.historykind", kind) : null,
          ]);
        }),
      ])),
      thinned ? m("p.historynote", "Past a week, only the last version of each hour is kept; past three months, of each day.") : null,
    ];
  }

  private version(attrs: HistoryViewAttrs, entry: HistoryEntry): m.Children {
    const version = this.versions.get(entry.revision);
    const content = this.versionError ? m(".pagemessage", this.versionError)
      : !version ? m(".pagemessage", "Loading…")
        : this.showChanges ? this.changes(version, entry)
          : attrs.markdown ? m(MarkdownView, { text: version.text, monospace: attrs.monospace })
            : m("pre.versiontext" + (attrs.monospace ? ".monospace" : ""), version.text || "Empty note.");
    const same = version !== undefined && version.text === attrs.current;
    return [
      m(".historytools", [
        m(".segmented", { role: "group", "aria-label": "Show" }, [
          m("button" + (this.showChanges ? ".on" : ""), { type: "button", "aria-pressed": String(this.showChanges), onclick: () => (this.showChanges = true) }, "Changes"),
          m("button" + (this.showChanges ? "" : ".on"), { type: "button", "aria-pressed": String(!this.showChanges), onclick: () => (this.showChanges = false) }, "Full text"),
        ]),
        same ? m("span.muted", "Same as the note now") : null,
      ]),
      m(".historycontent", content),
      m(".historyactions", [
        m("button", {
          type: "button", disabled: !version || same, title: same ? "The note is this version already" : undefined,
          onclick: () => version && attrs.onRestore(version.text, entry.to),
        }, "Restore this version"),
        m("button.secondary", { type: "button", disabled: !version, onclick: () => version && attrs.onRestoreAsNew(version.text) }, "Restore as a new note"),
        m("button.secondary", { type: "button", disabled: !version, onclick: () => version && attrs.onCopy(version.text) }, "Copy this version"),
      ]),
    ];
  }

  private changes(version: Version, entry: HistoryEntry): m.Children {
    version.lines ??= diffLines(version.baseText ?? "", version.text);
    const lines = version.lines;
    if (!hasChanges(lines)) {
      return m(".pagemessage", entry.kind === HistoryKind.DELETED || entry.kind === HistoryKind.RESTORED
        ? "The text didn't change." : "No changes to the text.");
    }
    return m(".diff", folded(lines).map((item, i) => {
      if (item.kind !== "fold") return diffLine(item);
      const fold = item as Fold;
      if (this.unfolded.has(i)) return fold.lines.map(diffLine);
      return m("button.difffold", { type: "button", onclick: () => this.unfolded.add(i) },
        [icon("chevron-down"), fold.lines.length + " unchanged lines"]);
    }));
  }

  /** Its text and the one before it, once each. */
  private select(attrs: HistoryViewAttrs, index: number, open: boolean) {
    this.selected = index;
    this.opened = open;
    this.unfolded = new Set();
    this.versionError = undefined;
    const entries = this.entries!;
    const entry = entries[index];
    if (this.versions.has(entry.revision)) return;
    const base = entries[index + 1]?.revision;
    ApiEndpoints.NoteVersion.call({ noteId: attrs.noteId, revision: entry.revision, base })
      .then((output) => this.versions.set(entry.revision, { text: output.text, baseText: output.baseText }))
      .catch((err: Error) => {
        if (this.selected === index) this.versionError = err.message;
      })
      .finally(() => m.redraw());
  }

  private startResize(e: PointerEvent) {
    if (e.button !== 0) return;
    e.preventDefault();
    const handle = e.currentTarget as HTMLElement;
    const list = document.querySelector(".historylist");
    if (!list) return;
    const startWidth = list.getBoundingClientRect().width;
    const startX = e.clientX;
    handle.setPointerCapture(e.pointerId);
    document.body.classList.add("resizing");
    const move = (event: PointerEvent) => {
      const max = window.innerWidth - MIN_VERSION_WIDTH;
      this.width = Math.round(Math.max(MIN_WIDTH, Math.min(startWidth + event.clientX - startX, max)));
      m.redraw();
    };
    const end = () => {
      handle.removeEventListener("pointermove", move);
      handle.removeEventListener("pointerup", end);
      handle.removeEventListener("pointercancel", end);
      document.body.classList.remove("resizing");
      saveWidth(this.width);
    };
    handle.addEventListener("pointermove", move);
    handle.addEventListener("pointerup", end);
    handle.addEventListener("pointercancel", end);
  }
}

function diffLine(line: DiffLine): m.Children {
  const mark = line.kind === "removed" ? "del" : "ins";
  return m(".diffline." + line.kind, line.pieces.map((piece) => piece.changed && line.pieces.length > 1 ? m(mark, piece.text) : piece.text)
    .concat(line.pieces.every((piece) => piece.text === "") ? ["​"] : []));
}
