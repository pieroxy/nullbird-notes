import m from "mithril";
import { LocalNote } from "../sync/LocalNote";
import { shortDate } from "../utils/format";
import { sortKey } from "../sync/Outcomes";
import { MarkdownView } from "./MarkdownView";

export interface TrashedNoteAttrs {
  note: LocalNote;
  markdown: boolean;
  monospace: boolean;
  /** Its text, fetched from the server when this device never had it (see SyncEngine.trashedText). */
  loadText: () => Promise<string>;
  onRestore: () => Promise<void>;
  onPurge: () => Promise<void>;
}

/**
 * A note in the trash: read-only, with what takes it out of there. Restoring brings it back with
 * its tags; deleting it for good takes its history too.
 */
export class TrashedNote implements m.ClassComponent<TrashedNoteAttrs> {
  private text?: string;
  private error?: string;
  private busy = false;

  oninit({ attrs }: m.Vnode<TrashedNoteAttrs>) {
    this.text = attrs.note.text || undefined;
    if (this.text === undefined) {
      attrs.loadText()
        .then((text) => (this.text = text))
        .catch((err: Error) => (this.error = "Its text can't be shown: " + err.message))
        .finally(() => m.redraw());
    }
  }

  view({ attrs }: m.Vnode<TrashedNoteAttrs>): m.Children {
    const text = this.text;
    return m("section.editor.trashed", [
      m(".banner.trashbanner", [
        m("span", "In the trash since " + shortDate(sortKey(attrs.note)) + "."),
        m("span.spacer"),
        m("button", { type: "button", disabled: this.busy, onclick: () => void this.run(attrs.onRestore) }, "Restore"),
        m("button.secondary.danger", { type: "button", disabled: this.busy, onclick: () => void this.purge(attrs) }, "Delete forever"),
      ]),
      this.error ? m("p.errormessage.trasherror", this.error) : null,
      text === undefined ? m(".pagemessage", this.error ? "" : "Loading…")
        : attrs.markdown ? m(MarkdownView, { text, monospace: attrs.monospace })
          : m("pre.trashtext" + (attrs.monospace ? ".monospace" : ""), text || "Empty note."),
    ]);
  }

  private async purge(attrs: TrashedNoteAttrs) {
    if (!confirm("Delete this note forever? Its history goes too: it can't be brought back.")) return;
    await this.run(attrs.onPurge);
  }

  private async run(action: () => Promise<void>) {
    this.busy = true;
    this.error = undefined;
    try {
      await action();
    } catch (err) {
      this.error = (err as Error).message;
    } finally {
      this.busy = false;
      m.redraw();
    }
  }
}
