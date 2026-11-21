import m from "mithril";
import { NotesSession } from "./NotesSession";
import { HttpSyncApi } from "./SyncApi";

/** The notes session of this tab, shared by its pages. */
export const session = new NotesSession(() => new HttpSyncApi(), () => m.redraw());

// The tab may be closed or frozen next.
window.addEventListener("pagehide", () => void session.flush());
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") void session.flush();
  // Back to the foreground (a phone unlocked, the app switched back to): try now, not after the backoff.
  else session.retryIfWaiting();
});
// A network is back. Not proof the server answers (a hotel's login page counts), but worth a try now.
window.addEventListener("online", () => session.retryIfWaiting());
