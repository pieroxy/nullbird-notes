import m from "mithril";
import { ApiEndpoints } from "../../auto/ApiEndpoints";
import { LocalStore } from "../../sync/LocalStore";
import { HttpSyncApi } from "../../sync/SyncApi";
import { LocalNote } from "../../sync/LocalNote";
import { isOn, MONOSPACE, NOWRAP, tagsOf } from "../../sync/Metadata";
import { SyncEngine } from "../../sync/SyncEngine";
import { TabClaim } from "../../sync/TabClaim";
import { Auth } from "../../utils/Auth";
import { titleAndExcerpt } from "../../utils/format";
import { Endpoints } from "../../utils/navigation/Endpoints";
import { Routing } from "../../utils/navigation/Routing";
import { loadLayout, PaneLayout, resetWidth, saveLayout, SidePane, tagsShown, toggleList, toggleTags, withWidth } from "../../utils/PaneLayout";
import { findMatches, fold, matches as searchMatches, MAX_MATCHES, parseQuery, Range, SearchIndex, snippet } from "../../utils/Search";
import { findTags, isActive, loadFilter, matches, NO_FILTER, saveFilter, TagCount, TagFilter, tagCounts, toggle } from "../../utils/TagFilter";
import { Dropdown } from "../Dropdown";
import { icon, iconButton } from "../Icons";
import { FindAttrs, NoteEditor } from "../NoteEditor";
import { NoteList } from "../NoteList";
import { noteSearch } from "../NoteSearch";
import { SyncStatusIndicator } from "../SyncStatusIndicator";
import { TagPanel, tagSearch } from "../TagPanel";
import { AbstractPage } from "./AbstractPage";

type Phase = "opening" | "active" | "elsewhere" | "failed";

/** As in style.scss: one pane at a time. */
const NARROW = "(max-width: 700px)";
const TAGS_SHORTCUT = "Ctrl+Shift+[";
const LIST_SHORTCUT = "Ctrl+Shift+]";

/**
 * The tags, the note list filtered on them and the search, then the selected note. On wide screens,
 * the tags and the list can be closed and resized. On narrow ones, one at a time: the list or the
 * note, or the tags over the list.
 * Runs the user's sync engine while this tab holds their notes (see TabClaim).
 */
export class NotesPage extends AbstractPage {
  private phase: Phase = "opening";
  private failure?: string;
  private claim?: TabClaim;
  private store?: LocalStore;
  private engine?: SyncEngine;
  private unsubscribe: (() => void)[] = [];
  private selectedId?: string;
  private filter: TagFilter = NO_FILTER;
  private tagSearch = "";
  /** Not kept across reloads, unlike the tag filter: a search is a quick lookup. */
  private search = "";
  private readonly searchIndex = new SearchIndex();
  /** The selected match in the open note, -1 before stepping to any. */
  private matchIndex = -1;
  /** Bumped to make the editor select the current match. */
  private jump = 0;
  /** Its match count, for stepping from outside its render. */
  private matchCount = 0;
  /** Narrow screens: the tags shown instead of the list. */
  private showTags = false;
  private layout: PaneLayout = loadLayout();
  private version?: string;
  private readonly onPageHide = () => void this.engine?.flush();
  private readonly onVisibilityChange = () => {
    if (document.visibilityState === "hidden") void this.engine?.flush();
  };
  /** By physical key: [ and ] move around on other keyboard layouts. Cmd too, for macOS webviews: browsers keep Cmd+Shift+[ for tabs. */
  private readonly onKeyDown = (e: KeyboardEvent) => {
    if (this.onSearchKey(e)) {
      e.preventDefault();
      m.redraw();
      return;
    }
    if (!(e.ctrlKey || e.metaKey) || !e.shiftKey || e.altKey || matchMedia(NARROW).matches) return;
    if (e.code === "BracketLeft") this.setLayout(toggleTags(this.layout));
    else if (e.code === "BracketRight") this.setLayout(toggleList(this.layout));
    else return;
    e.preventDefault();
    m.redraw();
  };

  oninit() {
    window.addEventListener("pagehide", this.onPageHide);
    document.addEventListener("visibilitychange", this.onVisibilityChange);
    document.addEventListener("keydown", this.onKeyDown);
    void this.activate();
    ApiEndpoints.Version.call({})
      .then((output) => (this.version = output.version + " (" + output.revision + ")"))
      .catch(() => undefined);
  }

  onremove() {
    window.removeEventListener("pagehide", this.onPageHide);
    document.removeEventListener("visibilitychange", this.onVisibilityChange);
    document.removeEventListener("keydown", this.onKeyDown);
    void this.deactivate().then(() => {
      this.claim?.close();
      this.claim = undefined;
    });
  }

  getPageTitle(): string {
    const selected = this.selectedId ? this.engine?.get(this.selectedId) : undefined;
    const title = selected ? titleAndExcerpt(selected.text).title : "";
    return title ? title + " - Notes" : "Notes";
  }

  render(): m.Children {
    const engine = this.phase === "active" ? this.engine : undefined;
    const selected = engine && this.selectedId ? engine.get(this.selectedId) : undefined;
    const tagging = this.showTags && !this.selectedId;
    const filtering = isActive(this.filter);
    const layout = this.layout;
    const classes = (this.selectedId ? ".editing" : "") + (tagging ? ".tagging" : "") + (tagsShown(layout) ? "" : ".notags") + (layout.list ? "" : ".nolist");
    // Set through the CSSOM, which the CSP allows, unlike style attributes.
    const widths = { "--tags-width": layout.tagsWidth + "px", "--list-width": layout.listWidth + "px" };
    // Every button is always rendered; which ones show depends on the screen width and the classes above (see style.scss).
    return m("main.notespage" + classes, { style: widths }, [
      // Its sides line up with the panes. The status is on the widest one: it must never be cut.
      m("header.topbar", [
        m(".tagside", [
          engine ? tagSearch({
            search: this.tagSearch,
            onSearch: (search) => (this.tagSearch = search),
            onPick: () => {
              const first = findTags(this.tagCounts(engine), this.tagSearch)[0];
              if (first) this.setFilter(toggle(this.filter, first.tag));
            },
          }) : m("span.spacer"),
          m("button.plain.done", { type: "button", onclick: () => (this.showTags = false) }, "Done"),
          iconButton("x", "Hide the tags (" + TAGS_SHORTCUT + ")", () => this.setLayout(toggleTags(layout)), "tagsclose"),
        ]),
        m(".listside", [
          this.selectedId ? iconButton("chevron-left", "Back to the notes", () => this.select(undefined), "back") : null,
          engine && !this.selectedId ? iconButton("panel-left", (filtering ? "Tags, filtering" : "Tags") + " (" + TAGS_SHORTCUT + ")", () => {
            if (matchMedia(NARROW).matches) this.showTags = true;
            else this.setLayout(toggleTags(layout));
          }, "tagsbutton" + (filtering ? ".active" : "")) : null,
          engine ? iconButton("plus", "New note", () => this.newNote(engine), "new") : null,
          engine ? noteSearch(this.searchAttrs()) : m("span.spacer"),
          iconButton("x", "Hide the notes list (" + LIST_SHORTCUT + ")", () => this.setLayout(toggleList(layout)), "listclose"),
        ]),
        m(".noteside", [
          iconButton("list", "Show the notes list (" + LIST_SHORTCUT + ")", () => this.setLayout(toggleList(layout)), "listopen"),
          this.engine ? m(SyncStatusIndicator, { status: this.engine.status(), onRetry: () => this.engine?.retryNow() }) : m("span.spacer"),
          engine && selected && !selected.deleted ? iconButton("trash-2", "Delete note", () => {
            if (!confirm("Delete this note?")) return;
            engine.delete(selected.id);
            this.selectedId = undefined;
          }, "delete") : null,
          m(Dropdown, { label: "Menu", triggerClass: "icon", align: "right", trigger: icon("ellipsis") }, [
            engine && selected && !selected.deleted ? [
              m(".menusection", "This note"),
              checkItem("Fixed-width font", isOn(selected, MONOSPACE), (on) => engine.setOption(selected.id, MONOSPACE, on)),
              checkItem("Wrap long lines", !isOn(selected, NOWRAP), (on) => engine.setOption(selected.id, NOWRAP, !on)),
              m(".menuseparator"),
            ] : null,
            m(".menuuser", Auth.getUsername()),
            m("button.menuitem", { type: "button", onclick: () => void this.logout() }, "Log out"),
            this.version ? m(".menuversion", "Version " + this.version) : null,
          ]),
        ]),
      ]),
      this.body(),
    ]);
  }

  private body(): m.Children {
    switch (this.phase) {
      case "opening":
        return m(".pagemessage", "Opening your notes…");
      case "failed":
        return m(".pagemessage", "Your notes couldn't be opened on this device: " + this.failure);
      case "elsewhere":
        return m(".pagemessage", [
          m("p", "Your notes are open in another tab."),
          m("button", { onclick: () => void this.activate() }, "Use them here"),
        ]);
      case "active":
        return this.panes(this.engine!);
    }
  }

  private panes(engine: SyncEngine): m.Children {
    const selected = this.selectedId ? engine.get(this.selectedId) : undefined;
    if (this.selectedId && (!selected || selected.deleted)) this.selectedId = undefined;
    const notes = engine.list();
    const tags = new Map(notes.map((note) => [note.id, tagsOf(note)]));
    const onFilter = (filter: TagFilter) => this.setFilter(filter);
    const terms = parseQuery(this.search);
    const folded = (note: LocalNote) => this.searchIndex.get(note.id, note.text);
    const found = (note: LocalNote) => searchMatches(terms, folded(note), tags.get(note.id)!.map(fold));
    if (terms.length > 0) this.searchIndex.retain(new Set(notes.map((note) => note.id)));
    const find = selected && terms.length > 0 ? this.findAttrs(findMatches(folded(selected), terms), found(selected)) : undefined;
    // Keyed throughout: mithril refuses siblings some of which have keys and some not.
    return m(".panes", [
      m(TagPanel, {
        key: "tags",
        filter: this.filter,
        counts: tagCounts(this.filter, [...tags.values()]),
        search: this.tagSearch,
        untaggedCount: [...tags.values()].filter((noteTags) => noteTags.length === 0).length,
        onChange: onFilter,
      }),
      this.resizer("tags"),
      m(NoteList, {
        key: "list",
        notes: notes.filter((note) => matches(this.filter, tags.get(note.id)!) && (terms.length === 0 || found(note))),
        total: notes.length,
        filter: this.filter,
        onFilter,
        ...this.searchAttrs(),
        snippetOf: terms.length > 0 ? (note: LocalNote) => snippet(note.text, folded(note), terms) : undefined,
        selectedId: this.selectedId,
        onSelect: (id: string) => this.select(id),
      }),
      this.resizer("list"),
      selected
        ? m(NoteEditor, {
          key: selected.id,
          note: selected,
          tags: tagsOf(selected),
          monospace: isOn(selected, MONOSPACE),
          nowrap: isOn(selected, NOWRAP),
          onEdit: (text: string) => engine.edit(selected.id, text),
          onAddTag: (tag: string) => engine.addTag(selected.id, tag),
          onRemoveTag: (tag: string) => engine.removeTag(selected.id, tag),
          find,
        })
        : m("section.editor.none", { key: "none" }, m(".empty", "Select a note or create one.")),
    ]);
  }

  /** A zero-width flex item on the right border of its pane: the topbar, without it, stays aligned. */
  private resizer(pane: SidePane): m.Children {
    return m(".resizer." + pane + "resizer", {
      key: pane + "resizer",
      title: "Drag to resize, double-click to reset",
      onpointerdown: (e: PointerEvent) => this.startResize(pane, e),
      ondblclick: () => this.setLayout(resetWidth(this.layout, pane)),
    });
  }

  /** From the pane's rendered width, which a narrow window may have shrunk below its setting. */
  private startResize(pane: SidePane, e: PointerEvent) {
    if (e.button !== 0) return;
    e.preventDefault();
    const handle = e.currentTarget as HTMLElement;
    const paneElement = document.querySelector(pane === "tags" ? ".tagpanel" : ".notelist");
    if (!paneElement) return;
    const startWidth = paneElement.getBoundingClientRect().width;
    const startX = e.clientX;
    handle.setPointerCapture(e.pointerId);
    document.body.classList.add("resizing");
    const move = (event: PointerEvent) => {
      this.layout = withWidth(this.layout, pane, startWidth + event.clientX - startX, window.innerWidth);
      m.redraw();
    };
    const end = () => {
      handle.removeEventListener("pointermove", move);
      handle.removeEventListener("pointerup", end);
      handle.removeEventListener("pointercancel", end);
      document.body.classList.remove("resizing");
      saveLayout(this.layout);
    };
    handle.addEventListener("pointermove", move);
    handle.addEventListener("pointerup", end);
    handle.addEventListener("pointercancel", end);
  }

  private setLayout(layout: PaneLayout) {
    this.layout = layout;
    saveLayout(layout);
  }

  private tagCounts(engine: SyncEngine): TagCount[] {
    return tagCounts(this.filter, engine.list().map(tagsOf));
  }

  private searchAttrs() {
    return {
      search: this.search,
      onSearch: (search: string) => {
        this.search = search;
        this.matchIndex = -1;
      },
      onStep: (direction: 1 | -1) => this.step(direction),
    };
  }

  private findAttrs(matches: Range[], found: boolean): FindAttrs {
    this.matchCount = Math.min(matches.length, MAX_MATCHES);
    if (this.matchIndex >= this.matchCount) this.matchIndex = this.matchCount - 1;
    return {
      matches, current: this.matchIndex, jump: this.jump, byTag: found && matches.length === 0,
      onStep: (direction) => this.step(direction),
      onClear: () => this.clearSearch(),
    };
  }

  /** To the next or previous match in the open note, round the end. */
  private step(direction: 1 | -1) {
    if (!this.selectedId || this.matchCount === 0) return;
    const count = this.matchCount;
    this.matchIndex = direction > 0 ? (this.matchIndex + 1) % count : this.matchIndex <= 0 ? count - 1 : this.matchIndex - 1;
    this.jump++;
  }

  private clearSearch() {
    this.search = "";
    this.matchIndex = -1;
  }

  /**
   * Ctrl/Cmd+K: to the search field. F3, Ctrl/Cmd+G: to the next match (with Shift, the previous).
   * @return whether {@code e} was one of them.
   */
  private onSearchKey(e: KeyboardEvent): boolean {
    const mod = (e.ctrlKey || e.metaKey) && !e.altKey;
    if (mod && !e.shiftKey && e.key.toLowerCase() === "k") {
      if (matchMedia(NARROW).matches) this.select(undefined);
      else if (!this.layout.list) this.setLayout(toggleList(this.layout));
      // Once shown: the redraw comes first.
      requestAnimationFrame(() => {
        const field = [...document.querySelectorAll<HTMLInputElement>("input.notesearch")].find((input) => input.offsetParent !== null);
        field?.focus();
        field?.select();
      });
      return true;
    }
    if (this.search.trim() && (e.key === "F3" || (mod && e.key.toLowerCase() === "g"))) {
      this.step(e.shiftKey ? -1 : 1);
      return true;
    }
    return false;
  }

  /** It gets the tags filtered on: otherwise it wouldn't be in the list it was created from. A search would hide it too: cleared. */
  private newNote(engine: SyncEngine) {
    this.showTags = false;
    this.clearSearch();
    this.select(engine.create(this.filter.tags));
  }

  private setFilter(filter: TagFilter) {
    this.filter = filter;
    const username = Auth.getUsername();
    if (username) saveFilter(username, filter);
  }

  /** Opened from a search, a note shows its first match. */
  private select(id: string | undefined) {
    if (this.selectedId && this.selectedId !== id) this.engine?.discardIfEmpty(this.selectedId);
    if (id !== this.selectedId) {
      this.matchIndex = id && this.search.trim() ? 0 : -1;
      this.jump++;
    }
    this.selectedId = id;
  }

  private async activate() {
    const username = Auth.getUsername();
    if (!username) return;
    this.phase = "opening";
    this.filter = loadFilter(username);
    m.redraw();
    this.claim ??= new TabClaim(username);
    const won = await this.claim.claim(() => this.lose());
    if (!won) {
      this.phase = "elsewhere";
      m.redraw();
      return;
    }
    try {
      this.store = await LocalStore.open(username);
      const engine = new SyncEngine(this.store, new HttpSyncApi());
      this.unsubscribe = [
        engine.onChange(() => m.redraw()),
      ];
      this.engine = engine;
      await engine.start();
      this.phase = "active";
    } catch (e) {
      this.failure = e instanceof Error ? e.message : String(e);
      this.phase = "failed";
    }
    m.redraw();
  }

  /** Another tab took the notes over. */
  private async lose() {
    await this.deactivate();
    this.phase = "elsewhere";
    m.redraw();
  }

  private async deactivate() {
    const engine = this.engine;
    this.engine = undefined;
    this.unsubscribe.forEach((unsubscribe) => unsubscribe());
    this.unsubscribe = [];
    await engine?.stop();
    this.store?.close();
    this.store = undefined;
  }

  /** Logging out wipes this device's copy of the notes: a shared computer mustn't keep them. */
  private async logout() {
    const pending = this.engine?.status().pending ?? 0;
    if (pending > 0 && !confirm(pending + (pending > 1 ? " notes have" : " note has")
      + " changes not synced yet, which will be lost. Log out anyway?")) return;
    const username = Auth.getUsername();
    await this.deactivate();
    this.claim?.close();
    this.claim = undefined;
    if (username) {
      await LocalStore.destroy(username);
      saveFilter(username, NO_FILTER);
    }
    await Auth.logout();
    Routing.goToScreen(Endpoints.LOGIN);
  }
}

/** A menu entry with a check mark: {@code onToggle} gets the new state. */
function checkItem(label: string, on: boolean, onToggle: (on: boolean) => void): m.Children {
  return m("button.menuitem.checkitem", { type: "button", role: "menuitemcheckbox", "aria-checked": String(on), onclick: () => onToggle(!on) }, [
    m("span.checkmark", on ? icon("check") : null),
    label,
  ]);
}
