import m from "mithril";
import { SyncStatus } from "../sync/SyncEngine";
import { time } from "../utils/format";
import { Dropdown } from "./Dropdown";

export interface SyncStatusIndicatorAttrs {
  status: SyncStatus;
  onRetry: () => void;
}

/**
 * Always visible, and never optimistic: it says what sync last managed, and what's left. Narrow
 * screens show only the state; the panel it opens has everything.
 */
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
    const notSynced = status.pending > 0 ? status.pending + " not synced" : undefined;
    const inSeconds = status.nextRetryAt !== undefined ? Math.max(0, Math.ceil((status.nextRetryAt - Date.now()) / 1000)) : undefined;
    const nextTry = inSeconds !== undefined ? "Next try in " + inSeconds + " s" : undefined;
    const last = status.lastSyncAt !== undefined ? "Last synced at " + time(status.lastSyncAt) : "Never synced on this device";
    const retryable = status.state === "offline" || status.state === "error";
    let state: string;
    let label: string;
    let detail: string | undefined;
    switch (status.state) {
      case "starting":
        [state, label] = ["starting", "Connecting…"];
        break;
      case "synced":
        [state, label] = ["synced", "Synced"];
        break;
      case "saving":
        [state, label] = ["saving", "Saving…"];
        break;
      case "offline":
        [state, label] = ["offline", "Offline"];
        detail = [notSynced, "since " + time(status.offlineSince!)].filter(Boolean).join(" · ");
        break;
      case "error":
        [state, label, detail] = ["error", "Sync error", status.message];
        break;
      case "outdated":
        [state, label, detail] = ["error", "Outdated", "reload the page to sync" + (notSynced ? " (" + notSynced + ")" : "")];
        break;
      case "signedOut":
        [state, label, detail] = ["error", "Signed out", "log in again to sync" + (notSynced ? " (" + notSynced + ")" : "")];
        break;
    }
    const conflicts = status.conflicts > 0 ? status.conflicts + (status.conflicts > 1 ? " conflicts" : " conflict") : undefined;
    const notSaved = status.noteErrors > 0 ? status.noteErrors + (status.noteErrors > 1 ? " notes" : " note") + " not saved" : undefined;
    const retry = (cls: string) => m("button.link." + cls, { type: "button", onclick: attrs.onRetry }, "Retry now");
    return m(".syncindicator", [
      m(Dropdown, {
        label: "Sync status",
        triggerClass: "plain",
        align: "left",
        trigger: [
          m("span.syncstatus." + state, [m("span", label), detail ? m("span.detail", " · " + detail) : null]),
          conflicts ? m("span.chip.conflict", conflicts) : null,
          notSaved ? m("span.chip.error", notSaved) : null,
        ],
      }, [
        m("p.syncstatus." + state, label + (detail ? ": " + detail : "")),
        conflicts ? m("p", conflicts) : null,
        notSaved ? m("p", notSaved) : null,
        m("p.muted", last),
        nextTry && retryable ? m("p.muted", nextTry) : null,
        retryable ? retry("panelretry") : null,
      ]),
      retryable ? retry("inlineretry") : null,
    ]);
  }
}
