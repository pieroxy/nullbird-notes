import m from "mithril";

/** Always visible, so a sync problem can never go unnoticed. Placeholder until the sync engine exists. */
export class SyncStatus implements m.ClassComponent {
  view(): m.Children {
    return m("span.syncstatus.idle", { title: "Sync is not implemented yet." }, "Sync: not started");
  }
}
