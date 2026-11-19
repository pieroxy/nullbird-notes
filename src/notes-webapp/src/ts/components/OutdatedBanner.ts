import m from "mithril";

/** Unsynced edits are in IndexedDB: the reloaded page sends them. */
export class OutdatedBanner implements m.ClassComponent {
  view(): m.Children {
    return m(".outdatedbanner", { role: "alert" }, [
      m("span", "A new version of notes is available."),
      m("button", { type: "button", onclick: () => location.reload() }, "Reload"),
    ]);
  }
}
