import m from "mithril";
import { ApiEndpoints } from "../../auto/ApiEndpoints";
import { LocalStore } from "../../sync/LocalStore";
import { HttpSyncApi } from "../../sync/SyncApi";
import { isOn, MONOSPACE, NOWRAP, tagsOf } from "../../sync/Metadata";
import { SyncEngine } from "../../sync/SyncEngine";
import { TabClaim } from "../../sync/TabClaim";
import { Auth } from "../../utils/Auth";
import { titleAndExcerpt } from "../../utils/format";
import { Endpoints } from "../../utils/navigation/Endpoints";
import { Routing } from "../../utils/navigation/Routing";
import { loadLayout, PaneLayout, resetWidth, saveLayout, SidePane, tagsShown, toggleList, toggleTags, withWidth } from "../../utils/PaneLayout";
import { findTags, isActive, loadFilter, matches, NO_FILTER, saveFilter, TagCount, TagFilter, tagCounts, toggle } from "../../utils/TagFilter";
import { Dropdown } from "../Dropdown";
import { icon, iconButton } from "../Icons";
import { NoteEditor } from "../NoteEditor";
import { NoteList } from "../NoteList";
import { SyncStatusIndicator } from "../SyncStatusIndicator";
import { TagPanel, tagSearch } from "../TagPanel";
import { AbstractPage } from "./AbstractPage";

type Phase = "opening" | "active" | "elsewhere" | "failed";

/** As in style.scss: one pane at a time. */
const NARROW = "(max-width: 700px)";
const TAGS_SHORTCUT = "Ctrl+Shift+[";
const LIST_SHORTCUT = "Ctrl+Shift+]";

/**
 * The tags, the note list filtered on them, then the selected note. On wide screens, the tags and
 * the list can be closed and resized. On narrow ones, one at a time: the list or the note, or the
 * tags over the list.
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
          m("span.spacer"),
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
    const original = selected?.info?.conflictOf ? engine.get(selected.info.conflictOf.noteId) : undefined;
    const notes = engine.list();
    const conflictCopies = selected ? notes.filter((note) => note.info?.conflictOf?.noteId === selected.id).map((note) => note.id) : [];
    const tags = new Map(notes.map((note) => [note.id, tagsOf(note)]));
    const onFilter = (filter: TagFilter) => this.setFilter(filter);
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
        notes: notes.filter((note) => matches(this.filter, tags.get(note.id)!)),
        total: notes.length,
        filter: this.filter,
        onFilter,
        selectedId: this.selectedId,
        onSelect: (id: string) => this.select(id),
      }),
      this.resizer("list"),
      selected
        ? m(NoteEditor, {
          key: selected.id,
          note: selected,
          originalTitle: original ? titleAndExcerpt(original.text).title : undefined,
          conflictCopies,
          tags: tagsOf(selected),
          monospace: isOn(selected, MONOSPACE),
          nowrap: isOn(selected, NOWRAP),
          onEdit: (text: string) => engine.edit(selected.id, text),
          onOpen: (id: string) => this.select(id),
          onAddTag: (tag: string) => engine.addTag(selected.id, tag),
          onRemoveTag: (tag: string) => engine.removeTag(selected.id, tag),
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

  /** It gets the tags filtered on: otherwise it wouldn't be in the list it was created from. */
  private newNote(engine: SyncEngine) {
    this.showTags = false;
    this.select(engine.create(this.filter.tags));
  }

  private setFilter(filter: TagFilter) {
    this.filter = filter;
    const username = Auth.getUsername();
    if (username) saveFilter(username, filter);
  }

  private select(id: string | undefined) {
    if (this.selectedId && this.selectedId !== id) this.engine?.discardIfEmpty(this.selectedId);
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
        engine.onMove((from, to) => {
          if (this.selectedId === from) this.selectedId = to;
        }),
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
