import m from "mithril";
import { ApiEndpoints } from "../auto/ApiEndpoints";
import { CopyableText } from "./CopyableText";
import { iconButton } from "./Icons";
import { Platform } from "../utils/Platform";

export interface PublishPanelAttrs {
  noteId: string;
  /** Its published option, as synced: the token of its link. */
  token: string | undefined;
  onClose: () => void;
}

/**
 * Publishing the note, or its link: to copy, to replace, to stop. On the server right away, not
 * through the sync: not while offline. Until the sync brings the option, the server's answer shows.
 */
export class PublishPanel implements m.ClassComponent<PublishPanelAttrs> {
  /** The server's answer, until the synced option matches it. */
  private answered?: { token: string | undefined };
  private busy = false;
  private error?: string;

  view({ attrs }: m.Vnode<PublishPanelAttrs>): m.Children {
    if (this.answered && this.answered.token === attrs.token) this.answered = undefined;
    const token = this.answered ? this.answered.token : attrs.token;
    return m(".publishpanel", [
      m(".publishhead", [
        m("strong", token ? "Published" : "Publish this note"),
        m("span.spacer"),
        iconButton("x", "Close", attrs.onClose),
      ]),
      token ? [
        // Keyed: a new link isn't "Copied" yet.
        m(".publishlink", m(CopyableText, {
          key: token,
          text: Platform.serverOrigin() + "/p/" + token,
          kind: "link",
          hint: "Anyone with this link can read the note, as it changes, without logging in.",
          selectedHint: "Selected: press Ctrl+C (⌘C on a Mac) to copy it.",
        })),
        m(".publishactions", [
          m("button.secondary", { type: "button", disabled: this.busy, onclick: () => this.publish(attrs, true) }, "New link"),
          m("button.secondary", { type: "button", disabled: this.busy, onclick: () => this.unpublish(attrs) }, "Stop publishing"),
        ]),
        m("p.muted", "A new link replaces this one, which stops working."),
      ] : [
        m("p", "It gets a link: anyone with it can read the note, as it changes, without logging in."),
        m(".publishactions", m("button", { type: "button", disabled: this.busy, onclick: () => this.publish(attrs, false) }, "Publish")),
      ],
      this.error ? m("p.errormessage", { role: "alert" }, this.error) : null,
    ]);
  }

  private publish(attrs: PublishPanelAttrs, newLink: boolean) {
    this.run(ApiEndpoints.PublishNote.call({ noteId: attrs.noteId, newLink }).then((output) => output.token));
  }

  private unpublish(attrs: PublishPanelAttrs) {
    this.run(ApiEndpoints.UnpublishNote.call({ noteId: attrs.noteId }).then(() => undefined));
  }

  private run(call: Promise<string | undefined>) {
    this.busy = true;
    this.error = undefined;
    call
      .then((token) => (this.answered = { token }))
      .catch((err: Error) => (this.error = err.message))
      .finally(() => (this.busy = false));
  }
}
