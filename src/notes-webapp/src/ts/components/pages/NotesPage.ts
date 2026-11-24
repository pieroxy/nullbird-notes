import m from "mithril";
import { LocalNote } from "../../sync/LocalNote";
import { isOn, MARKDOWN, MONOSPACE, NOWRAP, PINNED, publishedToken, retagged, tagsOf } from "../../sync/Metadata";
import { moveTo } from "../../sync/TagOrder";
import { session } from "../../sync/Session";
import { SyncEngine } from "../../sync/SyncEngine";
import { Auth } from "../../utils/Auth";
import { refreshFontsOnce } from "../../utils/Fonts";
import { titleAndExcerpt } from "../../utils/format";
import { Endpoints } from "../../utils/navigation/Endpoints";
import { Routing } from "../../utils/navigation/Routing";
import { loadLayout, PaneLayout, resetWidth, saveLayout, SidePane, tagsShown, toggleList, toggleTags, withWidth } from "../../utils/PaneLayout";
import { findMatches, fold, matches as searchMatches, MAX_MATCHES, parseQuery, Range, SearchIndex, snippet } from "../../utils/Search";
import { ListKind } from "../../utils/ListFormat";
import { loadViewing, saveViewing } from "../../utils/ViewMode";
import { EMOJI_HELPER, loadChoice } from "../../utils/Appearance";
import { emojiHelperShown, TOUCH } from "../../utils/Emoji";
import { noteHasMergeMark, retainMergeMarks } from "../../utils/MergeMarks";
import { EmojiPicker } from "../EmojiPicker";
import { inOrder, isActive, loadFilter, matches, NO_FILTER, saveFilter, TagCount, TagFilter, tagCounts, toggle, visibleTags } from "../../utils/TagFilter";
import { Dropdown } from "../Dropdown";
import { icon, iconButton } from "../Icons";
import { FindAttrs, NoteEditor } from "../NoteEditor";
import { NoteList } from "../NoteList";
import { noteSearch } from "../NoteSearch";
import { SyncStatusIndicator } from "../SyncStatusIndicator";
import { TagPanel, tagSearch } from "../TagPanel";
import { TagSettings } from "../TagSettings";
import { noteStripes } from "../../utils/TagLook";
import { TagOptions } from "../../auto/api-types";
import { AbstractPage } from "./AbstractPage";

/** As in style.scss: one pane at a time. */
const NARROW = "(max-width: 700px)";
const TAGS_SHORTCUT = "Ctrl+Shift+[";
const LIST_SHORTCUT = "Ctrl+Shift+]";
const VIEW_SHORTCUT = "Ctrl+Shift+E";

/**
 * The tags, the note list filtered on them and the search, then the selected note. On wide screens,
 * the tags and the list can be closed and resized. On narrow ones, one at a time: the list or the
 * note, or the tags over the list.
 * Opens the user's notes (see NotesSession) if they aren't yet.
 */
export class NotesPage extends AbstractPage {
  private selectedId?: string;
  private filter: TagFilter = NO_FILTER;
  private tagSearch = "";
  /** Not kept across reloads, unlike the tag filter: a search is a quick lookup. */
  private search = "";
  /** The search shows the notes of hidden tags too, as asked: until it's cleared. */
  private searchHidden = false;
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
  /** The open note's publish panel shown. */
  private publishing = false;
  /** The tag whose settings are open (see TagSettings). */
  private tagSettings?: string;
  /** A message at the bottom of the screen for a few seconds, with what undoes it. */
  private toast?: { message: string; undo?: () => void };
  private toastTimer?: ReturnType<typeof setTimeout>;
  /** The engine whose tag events the filter follows, and how to stop. */
  private following?: { engine: SyncEngine; stop: () => void };
  private listCommand?: { kind: ListKind; seq: number };
  private insertCommand?: { text: string; focus: boolean; seq: number };
  /** The notes shown rendered, kept on this device (see ViewMode). */
  private viewing = new Set<string>();
  /** By physical key: [ and ] move around on other keyboard layouts. Cmd too, for macOS webviews: browsers keep Cmd+Shift+[ for tabs. */
  private readonly onKeyDown = (e: KeyboardEvent) => {
    if (this.onSearchKey(e)) {
      e.preventDefault();
      m.redraw();
      return;
    }
    if ((e.ctrlKey || e.metaKey) && e.shiftKey && !e.altKey && e.code === "KeyE") {
      const selected = this.selectedId ? this.engine?.get(this.selectedId) : undefined;
      if (selected && this.canView(selected)) {
        this.setViewing(selected.id, !this.viewing.has(selected.id));
        e.preventDefault();
        m.redraw();
      }
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
    document.addEventListener("keydown", this.onKeyDown);
    const username = Auth.getUsername();
    if (username) {
      this.filter = loadFilter(username);
      this.viewing = loadViewing(username);
      void session.open(username);
      refreshFontsOnce();
    }
  }

  /** The notes stay open, for the other pages. */
  onremove() {
    document.removeEventListener("keydown", this.onKeyDown);
    this.following?.stop();
    clearTimeout(this.toastTimer);
    if (this.selectedId) this.engine?.discardIfEmpty(this.selectedId);
  }

  private get engine(): SyncEngine | undefined {
    return session.engine;
  }

  getPageTitle(): string {
    const selected = this.selectedId ? this.engine?.get(this.selectedId) : undefined;
    const title = selected ? titleAndExcerpt(selected.text).title : "";
    return title ? title + " - Notes" : "Notes";
  }

  render(): m.Children {
    const engine = session.phase === "active" ? this.engine : undefined;
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
          iconButton("panel-left-close", "Hide the tags (" + TAGS_SHORTCUT + ")", () => this.setLayout(toggleTags(layout)), "tagsclose"),
          engine ? tagSearch({
            search: this.tagSearch,
            onSearch: (search) => (this.tagSearch = search),
            onPick: () => {
              const first = visibleTags(this.tagCounts(engine), this.filter, this.tagSearch)[0];
              if (first) this.setFilter(toggle(this.filter, first.tag));
            },
          }) : m("span.spacer"),
          m("button.plain.done", { type: "button", onclick: () => (this.showTags = false) }, "Done"),
        ]),
        m(".listside", [
          this.selectedId ? iconButton("chevron-left", "Back to the notes", () => this.select(undefined), "back") : null,
          engine ? iconButton("panel-left-open", (filtering ? "Tags, filtering" : "Tags") + " (" + TAGS_SHORTCUT + ")", () => {
            if (matchMedia(NARROW).matches) this.showTags = true;
            else this.setLayout(toggleTags(layout));
          }, "tagsbutton" + (filtering ? ".active" : "")) : null,
          iconButton("panel-left-close", "Hide the notes list (" + LIST_SHORTCUT + ")", () => this.setLayout(toggleList(layout)), "listclose"),
          engine ? noteSearch(this.searchAttrs()) : m("span.spacer"),
          engine ? iconButton("plus", "New note", () => this.newNote(engine), "new") : null,
        ]),
        m(".noteside", [
          iconButton("panel-left-open", "Show the notes list (" + LIST_SHORTCUT + ")", () => this.setLayout(toggleList(layout)), "listopen"),
          this.engine ? m(SyncStatusIndicator, { status: this.engine.status(), onRetry: () => this.engine?.retryNow() }) : m("span.spacer"),
          engine && selected && !selected.deleted && !(this.viewing.has(selected.id) && this.canView(selected))
            && emojiHelperShown(loadChoice(EMOJI_HELPER), matchMedia(TOUCH).matches)
            ? m(EmojiPicker, {
              onPick: (emoji: string, done: boolean) => this.insert(emoji, done),
              onClose: () => this.insert("", true),
            })
            : null,
          engine && selected && !selected.deleted && this.canView(selected)
            ? (this.viewing.has(selected.id)
              ? iconButton("pencil", "Edit (" + VIEW_SHORTCUT + ")", () => this.setViewing(selected.id, false), "viewtoggle")
              : iconButton("eye", "View (" + VIEW_SHORTCUT + ")", () => this.setViewing(selected.id, true), "viewtoggle"))
            : null,
          engine && selected && !selected.deleted ? iconButton("trash-2", "Delete note", () => {
            if (!confirm("Delete this note?")) return;
            engine.delete(selected.id);
            this.selectedId = undefined;
          }, "delete") : null,
          m(Dropdown, { label: "Menu", triggerClass: "icon", align: "right", trigger: icon("ellipsis") }, [
            engine && selected && !selected.deleted ? [
              m(".menusection", "This note"),
              checkItem("Pinned", isOn(selected, PINNED), (on) => engine.setOption(selected.id, PINNED, on)),
              checkItem("Markdown", isOn(selected, MARKDOWN), (on) => engine.setOption(selected.id, MARKDOWN, on)),
              checkItem("Fixed-width font", isOn(selected, MONOSPACE), (on) => engine.setOption(selected.id, MONOSPACE, on)),
              checkItem("Wrap long lines", !isOn(selected, NOWRAP), (on) => engine.setOption(selected.id, NOWRAP, !on)),
              this.viewing.has(selected.id) && this.canView(selected) ? null : [
                m(".menuseparator"),
                m("button.menuitem", { type: "button", onclick: () => this.makeList("bullet") }, "Bullet list"),
                m("button.menuitem", { type: "button", onclick: () => this.makeList("number") }, "Numbered list"),
                m("button.menuitem", { type: "button", onclick: () => this.makeList("task") }, "Task list"),
                m(".menuseparator"),
              ],
              m("button.menuitem", { type: "button", onclick: () => (this.publishing = true) },
                publishedToken(selected) ? "Published link…" : "Publish…"),
              m(".menuseparator"),
            ] : null,
            m("button.menuitem", { type: "button", onclick: () => Routing.goToScreen(Endpoints.ACCOUNT) }, "My Account"),
            Auth.isAdmin() ? m("button.menuitem", { type: "button", onclick: () => Routing.goToScreen(Endpoints.ADMIN) }, "Administration") : null,
          ]),
        ]),
      ]),
      this.body(),
      engine && this.tagSettings ? this.tagSettingsDialog(engine, this.tagSettings) : null,
      this.toast ? m(".toast", { role: "status" }, [
        m("span", this.toast.message),
        this.toast.undo ? m("button.plain", { type: "button", onclick: () => { this.toast?.undo?.(); this.hideToast(); } }, "Undo") : null,
        iconButton("x", "Dismiss", () => this.hideToast()),
      ]) : null,
    ]);
  }

  private tagSettingsDialog(engine: SyncEngine, tag: string): m.Children {
    // Unkeyed, as its siblings: mithril refuses a mix. One tag's settings close before another's open.
    return m(TagSettings, {
      tagName: tag,
      count: engine.tagNoteCount(tag),
      allTags: engine.allTagNames(),
      options: engine.allTagOptions()[tag],
      onOptions: (change: Partial<TagOptions>) => engine.setTagOptions(tag, { ...engine.allTagOptions()[tag], ...change }),
      onClose: () => (this.tagSettings = undefined),
      onRename: async (to: string) => {
        await engine.renameTag(tag, to);
        this.tagSettings = undefined;
      },
      onRemove: async () => {
        // Where it was, to put it back there on undo, if the user ordered their tags.
        const listed = this.tagCounts(engine).map((count) => count.tag);
        const ordered = engine.tagOrder(listed) !== undefined;
        const at = listed.indexOf(tag);
        const ids = await engine.deleteTag(tag);
        this.tagSettings = undefined;
        this.showToast("Removed \"" + tag + "\" from " + (ids.length === 1 ? "1 note" : ids.length + " notes") + ".", () => {
          for (const id of ids) engine.addTag(id, tag);
          if (ordered) engine.moveTag({ tag, after: at > 0 ? listed[at - 1] : null });
        });
      },
    });
  }

  private showToast(message: string, undo?: () => void) {
    clearTimeout(this.toastTimer);
    this.toast = { message, undo };
    this.toastTimer = setTimeout(() => this.hideToast(), 10_000);
  }

  private hideToast() {
    clearTimeout(this.toastTimer);
    this.toast = undefined;
    m.redraw();
  }

  /** Tags renamed or removed, here or on another device: the filter follows. */
  private followTagEvents(engine: SyncEngine) {
    if (this.following?.engine === engine) return;
    this.following?.stop();
    const stop = engine.onTagEvents((events) => {
      let tags = this.filter.tags;
      for (const event of events) tags = retagged(tags, event.from, event.to);
      if (tags !== this.filter.tags) this.setFilter({ ...this.filter, tags });
      if (this.tagSettings && events.some((event) => event.from === this.tagSettings)) this.tagSettings = undefined;
    });
    this.following = { engine, stop };
  }

  private body(): m.Children {
    switch (session.phase) {
      case "idle":
      case "opening":
        return m(".pagemessage", "Opening your notes…");
      case "failed":
        return m(".pagemessage", "Your notes couldn't be opened on this device: " + session.failure);
      case "elsewhere":
        return m(".pagemessage", [
          m("p", "Your notes are open in another tab."),
          m("button", { onclick: () => void session.takeOver() }, "Use them here"),
        ]);
      case "active":
        return this.panes(this.engine!);
    }
  }

  private panes(engine: SyncEngine): m.Children {
    this.followTagEvents(engine);
    const selected = this.selectedId ? engine.get(this.selectedId) : undefined;
    if (this.selectedId && (!selected || selected.deleted)) this.selectedId = undefined;
    const notes = engine.list();
    const tags = new Map(notes.map((note) => [note.id, tagsOf(note)]));
    retainMergeMarks(new Set(tags.keys()));
    const tagOptions = engine.allTagOptions();
    // Each tag's place in the user's order, for the notes' stripes.
    const rank = new Map(this.tagCounts(engine).map((count, i) => [count.tag, i]));
    const merged = (note: LocalNote) => noteHasMergeMark(note.id, note.text);
    const hidden = hiddenTags(engine);
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
        counts: this.tagCounts(engine),
        search: this.tagSearch,
        untaggedCount: [...tags.values()].filter((noteTags) => noteTags.length === 0).length,
        mergedCount: notes.filter(merged).length,
        onChange: onFilter,
        onMove: (tag: string, index: number) => {
          const listed = this.tagCounts(engine).map((count) => count.tag);
          engine.moveTag(moveTo(listed, tag, index));
        },
        onSettings: (tag: string) => (this.tagSettings = tag),
        tagOptions,
        onMoveBy: (tag: string, by: number) => {
          // From the order as it is now: the engine has the previous move already, the page may not show it yet.
          const listed = this.tagCounts(engine).map((count) => count.tag);
          const at = listed.indexOf(tag);
          const to = at + by;
          if (at >= 0 && to >= 0 && to < listed.length) engine.moveTag(moveTo(listed, tag, to));
        },
      }),
      this.resizer("tags"),
      m(NoteList, {
        key: "list",
        notes: notes.filter((note) => matches(this.filter, tags.get(note.id)!, merged(note), this.searchHidden && terms.length > 0 ? undefined : hidden)
          && (terms.length === 0 || found(note))),
        // Searching, those of hidden tags that would match: said, and shown on request.
        hiddenMatches: terms.length > 0 && !this.searchHidden && hidden.size > 0
          ? notes.filter((note) => !matches(this.filter, tags.get(note.id)!, merged(note), hidden)
            && matches(this.filter, tags.get(note.id)!, merged(note)) && found(note)).length : 0,
        onShowHidden: () => (this.searchHidden = true),
        total: notes.length,
        filter: this.filter,
        onFilter,
        ...this.searchAttrs(),
        snippetOf: terms.length > 0 ? (note: LocalNote) => snippet(note.text, folded(note), terms) : undefined,
        selectedId: this.selectedId,
        onSelect: (id: string) => this.select(id),
        tagOptions,
        stripesOf: (note: LocalNote) => noteStripes(tags.get(note.id)!, tagOptions, rank),
      }),
      this.resizer("list"),
      selected
        ? m(NoteEditor, {
          key: selected.id,
          note: selected,
          tags: tagsOf(selected),
          monospace: isOn(selected, MONOSPACE),
          nowrap: isOn(selected, NOWRAP),
          markdown: isOn(selected, MARKDOWN),
          viewing: this.viewing.has(selected.id) && this.canView(selected),
          onView: (viewing: boolean) => this.setViewing(selected.id, viewing),
          publishing: this.publishing ? { token: publishedToken(selected), onClose: () => (this.publishing = false) } : undefined,
          onEdit: (text: string) => engine.edit(selected.id, text),
          onAddTag: (tag: string) => engine.addTag(selected.id, tag),
          onRemoveTag: (tag: string) => engine.removeTag(selected.id, tag),
          tagOptions,
          find,
          listCommand: this.listCommand,
          insertCommand: this.insertCommand,
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

  private canView(note: LocalNote): boolean {
    return isOn(note, MARKDOWN);
  }

  /** Applied by the editor, which keeps its selection while the menu has the focus. */
  private makeList(kind: ListKind) {
    this.listCommand = { kind, seq: (this.listCommand?.seq ?? 0) + 1 };
  }

  /** Applied by the editor, which keeps its selection while the emoji picker has the focus. */
  private insert(text: string, focus: boolean) {
    this.insertCommand = { text, focus, seq: (this.insertCommand?.seq ?? 0) + 1 };
  }

  private setViewing(id: string, viewing: boolean) {
    if (viewing) this.viewing.add(id);
    else this.viewing.delete(id);
    const username = Auth.getUsername();
    if (username && this.engine) saveViewing(username, this.viewing, new Set(this.engine.list().map((note) => note.id)));
  }

  private setLayout(layout: PaneLayout) {
    this.layout = layout;
    saveLayout(layout);
  }

  /** In the user's order, once they gave one; alphabetical until then. */
  private tagCounts(engine: SyncEngine): TagCount[] {
    const counts = tagCounts(this.filter, engine.list().map(tagsOf), hiddenTags(engine));
    return inOrder(counts, engine.tagOrder(counts.map((count) => count.tag)));
  }

  private searchAttrs() {
    return {
      search: this.search,
      onSearch: (search: string) => {
        this.search = search;
        this.matchIndex = -1;
        if (!search.trim()) this.searchHidden = false;
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
    this.searchHidden = false;
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
      this.publishing = false;
    }
    this.selectedId = id;
  }
}

/** A menu entry with a check mark: {@code onToggle} gets the new state. */
function checkItem(label: string, on: boolean, onToggle: (on: boolean) => void): m.Children {
  return m("button.menuitem.checkitem", { type: "button", role: "menuitemcheckbox", "aria-checked": String(on), onclick: () => onToggle(!on) }, [
    m("span.checkmark", on ? icon("check") : null),
    label,
  ]);
}

/** The tags whose notes show only when they're selected (see TagFilter.visible). */
function hiddenTags(engine: SyncEngine): Set<string> {
  return new Set(Object.entries(engine.allTagOptions()).filter(([, options]) => options.hidden).map(([tag]) => tag));
}
