import m from "mithril";
import { addRecent, canvasDraws, drawnVersions, Emoji, EmojiGroup, emojiGroups, loadRecent, searchEmojis, systemShortcut } from "../utils/Emoji";
import { iconButton } from "./Icons";

export interface EmojiPickerAttrs {
  /** Inserts it at the note's caret; {@code done}: the picker closed, the note to take the focus back. */
  onPick: (emoji: string, done: boolean) => void;
  /** Closed without picking: the note to take the focus back. */
  onClose: () => void;
}

/** As style.scss lays the grid out: for the arrow keys. */
const COLUMNS = 8;
const RECENT = "recent";

/** Tested once, when first opened: drawing on a canvas takes a moment. */
let drawn: Set<number> | undefined;

function shownGroups(): EmojiGroup[] {
  const all = emojiGroups();
  drawn ??= drawnVersions(all.flatMap((g) => g.emojis), canvasDraws());
  return all.map((g) => ({ ...g, emojis: g.emojis.filter((e) => drawn!.has(e.version)) }));
}

/**
 * The emoji helper's button and panel: a search, the recent emojis and Unicode's groups. Clicking
 * one inserts it and leaves the panel open, for more; Enter in the search inserts the one selected
 * and closes it. The focus stays in the search, the arrow keys moving the selection.
 */
export class EmojiPicker implements m.ClassComponent<EmojiPickerAttrs> {
  private open = false;
  private query = "";
  private tab = RECENT;
  private active = 0;
  private hovered?: Emoji;
  private groups: EmojiGroup[] = [];
  private dom?: Element;
  private attrs?: EmojiPickerAttrs;
  private readonly onDocumentPointer = (e: PointerEvent) => {
    if (this.open && !this.dom?.contains(e.target as Node)) this.close(false);
  };

  oncreate({ dom }: m.VnodeDOM<EmojiPickerAttrs>) {
    this.dom = dom;
    document.addEventListener("pointerdown", this.onDocumentPointer);
  }

  onremove() {
    document.removeEventListener("pointerdown", this.onDocumentPointer);
  }

  view({ attrs }: m.Vnode<EmojiPickerAttrs>): m.Children {
    this.attrs = attrs;
    return m(".emojipicker", [
      iconButton("smile", "Insert an emoji", () => (this.open ? this.close(true) : this.show()), "emojibutton" + (this.open ? ".active" : "")),
      this.open ? this.panel() : null,
    ]);
  }

  private show() {
    this.groups = shownGroups();
    this.open = true;
    this.query = "";
    this.active = 0;
    this.tab = loadRecent().length > 0 ? RECENT : this.groups[0].id;
  }

  private close(refocus: boolean) {
    this.open = false;
    this.hovered = undefined;
    if (refocus) this.attrs?.onClose();
    m.redraw();
  }

  /** What the grid shows: the search's results, else the tab's emojis. */
  private shown(): Emoji[] {
    const all = this.groups.flatMap((g) => g.emojis);
    if (this.query.trim()) return searchEmojis(all, this.query);
    if (this.tab === RECENT) {
      const byChar = new Map(all.map((e) => [e.char, e]));
      return loadRecent().map((c) => byChar.get(c)).filter((e): e is Emoji => e !== undefined);
    }
    return this.groups.find((g) => g.id === this.tab)?.emojis ?? [];
  }

  private pick(emoji: Emoji, done: boolean) {
    addRecent(emoji.char);
    this.attrs?.onPick(emoji.char, done);
    if (done) {
      this.open = false;
      this.hovered = undefined;
    }
  }

  private panel(): m.Children {
    const shown = this.shown();
    this.active = Math.min(this.active, Math.max(0, shown.length - 1));
    const searching = this.query.trim() !== "";
    const recent = loadRecent().length > 0;
    const tabs = [
      ...(recent ? [{ id: RECENT, label: "Recent", icon: "🕘" }] : []),
      ...this.groups.map((g) => ({ id: g.id, label: g.label, icon: g.icon })),
    ];
    const label = this.hovered ?? shown[this.active];
    const shortcut = systemShortcut(navigator.userAgent);
    return m(".emojipanel", { role: "dialog", "aria-label": "Emojis" }, [
      m("input.emojisearch", {
        type: "search",
        placeholder: "Search emojis",
        "aria-label": "Search emojis",
        "aria-controls": "emojigrid",
        "aria-activedescendant": shown.length ? "emoji-" + this.active : undefined,
        autocomplete: "off",
        spellcheck: false,
        value: this.query,
        oncreate: ({ dom }: m.VnodeDOM) => (dom as HTMLInputElement).focus(),
        oninput: (e: Event) => {
          this.query = (e.target as HTMLInputElement).value;
          this.active = 0;
        },
        onkeydown: (e: KeyboardEvent) => this.onKey(e, shown),
      }),
      searching ? null : m(".emojitabs", { role: "tablist" }, tabs.map((t) => m("button.emojitab", {
        key: t.id,
        type: "button",
        role: "tab",
        title: t.label,
        "aria-label": t.label,
        "aria-selected": String(t.id === this.tab),
        class: t.id === this.tab ? "selected" : "",
        // The focus stays in the search.
        onmousedown: (e: MouseEvent) => e.preventDefault(),
        onclick: () => {
          this.tab = t.id;
          this.active = 0;
          this.dom?.querySelector(".emojigrid")?.scrollTo(0, 0);
        },
      }, t.icon))),
      shown.length === 0
        ? m("p.emojinone", searching ? "No emoji found." : "")
        : m(".emojigrid#emojigrid", { role: "listbox", "aria-label": searching ? "Results" : tabs.find((t) => t.id === this.tab)?.label },
          shown.map((emoji, i) => m("button.emoji", {
            key: emoji.char,
            id: "emoji-" + i,
            type: "button",
            role: "option",
            title: emoji.name,
            "aria-label": emoji.name,
            "aria-selected": String(i === this.active),
            class: i === this.active ? "active" : "",
            onmousedown: (e: MouseEvent) => e.preventDefault(),
            onmouseenter: () => (this.hovered = emoji),
            onmouseleave: () => (this.hovered = undefined),
            onclick: () => {
              this.active = i;
              this.pick(emoji, false);
            },
          }, emoji.char))),
      m(".emojifooter", [
        label ? [m("span.emojibig", label.char), m("span.emojiname", label.name)] : m("span.emojiname"),
        shortcut ? m("span.muted", { title: "Your system's own emoji picker" }, shortcut) : null,
      ]),
    ]);
  }

  private onKey(e: KeyboardEvent, shown: Emoji[]) {
    const move = { ArrowRight: 1, ArrowLeft: -1, ArrowDown: COLUMNS, ArrowUp: -COLUMNS }[e.key];
    if (move !== undefined && shown.length > 0) {
      // Left and right move the caret in the search while there's text in it.
      if ((e.key === "ArrowLeft" || e.key === "ArrowRight") && this.query) return;
      e.preventDefault();
      const next = this.active + move;
      if (next >= 0 && next < shown.length) this.active = next;
      requestAnimationFrame(() => this.dom?.querySelector(".emoji.active")?.scrollIntoView({ block: "nearest" }));
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (shown[this.active]) this.pick(shown[this.active], true);
    } else if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation();
      this.close(true);
    }
  }
}
