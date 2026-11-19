import m from "mithril";
import { ApiEndpoints } from "../../auto/ApiEndpoints";
import { LocalStore } from "../../sync/LocalStore";
import { HttpSyncApi } from "../../sync/SyncApi";
import { SyncEngine } from "../../sync/SyncEngine";
import { TabClaim } from "../../sync/TabClaim";
import { Auth } from "../../utils/Auth";
import { titleAndExcerpt } from "../../utils/format";
import { Endpoints } from "../../utils/navigation/Endpoints";
import { Routing } from "../../utils/navigation/Routing";
import { Dropdown } from "../Dropdown";
import { icon, iconButton } from "../Icons";
import { NoteEditor } from "../NoteEditor";
import { NoteList } from "../NoteList";
import { SyncStatusIndicator } from "../SyncStatusIndicator";
import { AbstractPage } from "./AbstractPage";

type Phase = "opening" | "active" | "elsewhere" | "failed";

/**
 * The note list on the left, the selected note on the right (one at a time on narrow screens).
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
  private version?: string;
  private readonly onPageHide = () => void this.engine?.flush();
  private readonly onVisibilityChange = () => {
    if (document.visibilityState === "hidden") void this.engine?.flush();
  };

  oninit() {
    window.addEventListener("pagehide", this.onPageHide);
    document.addEventListener("visibilitychange", this.onVisibilityChange);
    void this.activate();
    ApiEndpoints.Version.call({})
      .then((output) => (this.version = output.version + " (" + output.revision + ")"))
      .catch(() => undefined);
  }

  onremove() {
    window.removeEventListener("pagehide", this.onPageHide);
    document.removeEventListener("visibilitychange", this.onVisibilityChange);
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
    return m("main.notespage" + (this.selectedId ? ".editing" : ""), [
      // Its two sides line up with the panes. The status is on the wider one: it must never be cut.
      m("header.topbar", [
        m(".listside", [
          this.selectedId ? iconButton("chevron-left", "Back to the notes", () => this.select(undefined), "back") : null,
          m("span.spacer"),
          engine ? iconButton("plus", "New note", () => this.select(engine.create()), "new") : null,
        ]),
        m(".noteside", [
          this.engine ? m(SyncStatusIndicator, { status: this.engine.status(), onRetry: () => this.engine?.retryNow() }) : m("span.spacer"),
          engine && selected && !selected.deleted ? iconButton("trash-2", "Delete note", () => {
            if (!confirm("Delete this note?")) return;
            engine.delete(selected.id);
            this.selectedId = undefined;
          }, "delete") : null,
          m(Dropdown, { label: "Menu", triggerClass: "icon", align: "right", trigger: icon("ellipsis") }, [
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
    // Keyed throughout: mithril refuses siblings some of which have keys and some not.
    return m(".panes", [
      m(NoteList, {
        key: "list",
        notes,
        selectedId: this.selectedId,
        onSelect: (id: string) => this.select(id),
      }),
      selected
        ? m(NoteEditor, {
          key: selected.id,
          note: selected,
          originalTitle: original ? titleAndExcerpt(original.text).title : undefined,
          conflictCopies,
          onEdit: (text: string) => engine.edit(selected.id, text),
          onOpen: (id: string) => this.select(id),
        })
        : m("section.editor.none", { key: "none" }, m(".empty", "Select a note or create one.")),
    ]);
  }

  private select(id: string | undefined) {
    if (this.selectedId && this.selectedId !== id) this.engine?.discardIfEmpty(this.selectedId);
    this.selectedId = id;
  }

  private async activate() {
    const username = Auth.getUsername();
    if (!username) return;
    this.phase = "opening";
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
    if (username) await LocalStore.destroy(username);
    await Auth.logout();
    Routing.goToScreen(Endpoints.LOGIN);
  }
}
