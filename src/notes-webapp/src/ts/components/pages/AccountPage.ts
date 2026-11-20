import m from "mithril";
import { ApiEndpoints } from "../../auto/ApiEndpoints";
import { session } from "../../sync/Session";
import { Auth } from "../../utils/Auth";
import { Endpoints } from "../../utils/navigation/Endpoints";
import { Routing } from "../../utils/navigation/Routing";
import { NO_FILTER, saveFilter } from "../../utils/TagFilter";
import { ChangePasswordForm } from "../ChangePasswordForm";
import { icon, iconButton } from "../Icons";
import { SyncStatusIndicator } from "../SyncStatusIndicator";
import { AbstractPage } from "./AbstractPage";

export interface AccountPageAttrs {
  /** From the route: /account/:section. Absent at /account. */
  section?: string;
}

/** Fetched once: moving between sections recreates the page. */
let version: string | undefined;

interface Section {
  id: string;
  title: string;
  render: () => m.Children;
}

/**
 * The user's account: who's logged in and logging out, then its sections, each at
 * /account/:section. Wide screens show the list of sections beside the one open (/account opens
 * the first); narrow ones, one at a time: the list at /account, a section at its own route. Opens
 * the user's notes (see NotesSession) if they aren't yet: the sync goes on, and logging out knows
 * what isn't synced.
 */
export class AccountPage extends AbstractPage<AccountPageAttrs> {
  private loggingOut = false;
  private readonly sections: Section[] = [
    { id: "security", title: "Security", render: () => this.security() },
    { id: "data", title: "Your notes", render: () => this.data() },
  ];

  oninit() {
    const username = Auth.getUsername();
    if (username) void session.open(username);
    if (version === undefined) {
      ApiEndpoints.Version.call({})
        .then((output) => (version = output.version + " (" + output.revision + ")"))
        .catch(() => undefined);
    }
  }

  getPageTitle(): string {
    return "My Account - Notes";
  }

  render({ attrs }: m.Vnode<AccountPageAttrs>): m.Children {
    const engine = session.engine;
    const chosen = this.sections.find((section) => section.id === attrs.section);
    const shown = chosen ?? this.sections[0];
    // Every element is always rendered; which ones show depends on the screen width and .insection (see style.scss).
    return m("main.accountpage" + (chosen ? ".insection" : ""), [
      m("header.topbar", [
        iconButton("chevron-left", "Back to the notes", () => Routing.goToScreen(Endpoints.HOME), "backhome"),
        iconButton("chevron-left", "Back to My Account", () => Routing.goToScreen(Endpoints.ACCOUNT), "backlist"),
        m("h1.pagetitle", "My Account"),
        m("h1.sectiontitle", shown.title),
        engine ? m(SyncStatusIndicator, { status: engine.status(), onRetry: () => engine.retryNow(), align: "right" }) : null,
      ]),
      m(".accountlayout", [
        m("nav.accountnav", [
          m(".accountuser", [
            m("p", ["Logged in as ", m("strong", Auth.getUsername())]),
            m("p.muted", "Logging out removes your notes from this device. They stay on the server."),
            m("button.secondary", { type: "button", disabled: this.loggingOut, onclick: () => void this.logout() }, "Log out"),
          ]),
          m("ul.sectionlinks", this.sections.map((section) =>
            m("li", { key: section.id }, m(m.route.Link, {
              href: Endpoints.ACCOUNT + "/" + section.id,
              class: section === shown ? "active" : "",
            }, [m("span", section.title), icon("chevron-right")])))),
          version ? m("p.accountversion", "Version " + version) : null,
        ]),
        m(".accountbody", m("section.accountsection", { key: shown.id }, shown.render())),
      ]),
    ]);
  }

  private security(): m.Children {
    return [
      m("h2", "Password"),
      m(ChangePasswordForm, { username: Auth.getUsername() ?? "" }),
    ];
  }

  private data(): m.Children {
    const pending = session.engine?.status().pending ?? 0;
    return [
      m("h2", "Export"),
      m("p", "Downloads all your notes as a .zip file, laid out as Simplenote's export: a .txt file per note, "
        + "plus source/notes.json with every note, its tags and dates, deleted notes included."),
      pending > 0 ? m("p.warning", pending + (pending > 1 ? " notes have" : " note has")
        + " changes not synced yet: the export has " + (pending > 1 ? "them" : "it") + " as last synced.") : null,
      // A plain link: the server answers with a download, the page stays.
      m("a.button", { href: "/export", download: "" }, "Export my notes"),
    ];
  }

  /** Logging out wipes this device's copy of the notes: a shared computer mustn't keep them. */
  private async logout() {
    const pending = session.engine?.status().pending ?? 0;
    if (pending > 0 && !confirm(pending + (pending > 1 ? " notes have" : " note has")
      + " changes not synced yet, which will be lost. Log out anyway?")) return;
    this.loggingOut = true;
    const username = Auth.getUsername();
    if (username) {
      await session.destroy(username);
      saveFilter(username, NO_FILTER);
    }
    await Auth.logout();
    Routing.goToScreen(Endpoints.LOGIN);
  }
}
