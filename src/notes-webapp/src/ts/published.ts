import "../css/published.scss";
import m from "mithril";
import { PublishedNoteApiOutput } from "./auto/api-types";
import { ApiEndpoints } from "./auto/ApiEndpoints";
import { MarkdownView } from "./components/MarkdownView";
import { ApiError } from "./utils/api/Api";
import { titleAndRest } from "./utils/format";

const REPOSITORY = "https://github.com/pieroxy/nullbird-notes";

/**
 * /p/{token}: a published note, read-only, as the app's view shows it (see PublishedNoteApi). A
 * page of its own, without the app: no session, no service worker, no editor.
 */
class PublishedPage implements m.ClassComponent {
  private note?: PublishedNoteApiOutput;
  private error?: string;

  oninit() {
    const token = decodeURIComponent(location.pathname.split("/")[2] ?? "");
    ApiEndpoints.PublishedNote.call({ token })
      .then((note) => (this.note = note))
      .catch((err: Error) => {
        this.error = err instanceof ApiError && err.status === 404 ? "This note isn't published, or its link has changed."
          : "The note couldn't be loaded: " + err.message;
      });
  }

  view(): m.Children {
    const note = this.note;
    if (!note) return m("main.publishedpage", m("p.pagemessage", this.error ?? "Loading…"));
    document.title = titleAndRest(note.text).title || "Note";
    const modified = new Date(note.modifiedAt).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
    return m("main.publishedpage", [
      m("article", note.markdown ? m(MarkdownView, { text: note.text, monospace: note.monospace }) : plainNote(note)),
      m("footer", ["Last modified " + modified + " · Published with ", m("a", { href: REPOSITORY, rel: "noopener noreferrer" }, "nullbird notes")]),
    ]);
  }
}

/** Its title as the view's, the rest as typed. */
function plainNote(note: PublishedNoteApiOutput): m.Children {
  const { title, rest } = titleAndRest(note.text);
  if (!title) return m(".plainnote", m("p.muted", "Empty note."));
  return m(".plainnote" + (note.monospace ? ".monospace" : "") + (note.nowrap ? ".nowrap" : ""), [
    m("h1", title),
    rest ? m(".plaintext", rest) : null,
  ]);
}

// Gone when compat.js found the browser too old, and replaced it with a message.
const root = document.getElementById("app");
if (root) m.mount(root, PublishedPage);
