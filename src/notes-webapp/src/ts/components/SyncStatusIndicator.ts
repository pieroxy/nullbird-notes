import m from "mithril";
import { SyncStatus } from "../sync/SyncEngine";
import { time } from "../utils/format";

export interface SyncStatusIndicatorAttrs {
  status: SyncStatus;
  onRetry: () => void;
}

/** Always visible, and never optimistic: it says what sync last managed, and what's left. */
export class SyncStatusIndicator implements m.ClassComponent<SyncStatusIndicatorAttrs> {
  private ticker?: ReturnType<typeof setInterval>;

  oncreate() {
    // Keeps the retry countdown current.
    this.ticker = setInterval(() => m.redraw(), 1000);
  }

  onremove() {
    clearInterval(this.ticker);
  }

  view({ attrs }: m.Vnode<SyncStatusIndicatorAttrs>): m.Children {
    const status = attrs.status;
    const retry = m("button.link", { onclick: attrs.onRetry }, "Retry now");
    const inSeconds = status.nextRetryAt !== undefined ? Math.max(0, Math.ceil((status.nextRetryAt - Date.now()) / 1000)) : undefined;
    const nextTry = inSeconds !== undefined ? "next try in " + inSeconds + " s" : "";
    const last = status.lastSyncAt !== undefined ? "Last synced at " + time(status.lastSyncAt) : "Never synced on this device";
    let main: m.Children;
    switch (status.state) {
      case "starting":
        main = m("span.syncstatus.starting", "Connecting…");
        break;
      case "synced":
        main = m("span.syncstatus.synced", { title: last }, "Synced");
        break;
      case "saving":
        main = m("span.syncstatus.saving", { title: last }, "Saving…");
        break;
      case "offline":
        main = [
          m("span.syncstatus.offline", { title: last },
            "Offline" + (status.pending > 0 ? " · " + status.pending + " not synced" : "")
            + " · since " + time(status.offlineSince!) + (nextTry ? " · " + nextTry : "")),
          retry,
        ];
        break;
      case "error":
        main = [m("span.syncstatus.error", { title: last }, "Sync error: " + status.message + (nextTry ? " · " + nextTry : "")), retry];
        break;
      case "signedOut":
        main = m("span.syncstatus.error", "Signed out: log in again to sync" + (status.pending > 0 ? " (" + status.pending + " not synced)" : ""));
        break;
    }
    return m(".syncindicator", [
      main,
      status.conflicts > 0 ? m("span.chip.conflict", status.conflicts + (status.conflicts > 1 ? " conflicts" : " conflict")) : null,
      status.noteErrors > 0 ? m("span.chip.error", status.noteErrors + (status.noteErrors > 1 ? " notes" : " note") + " not saved") : null,
    ]);
  }
}
