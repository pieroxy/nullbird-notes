import m from "mithril";
import { ApiEndpoints } from "../../auto/ApiEndpoints";
import { session } from "../../sync/Session";
import { Auth } from "../../utils/Auth";
import { Endpoints } from "../../utils/navigation/Endpoints";
import { Routing } from "../../utils/navigation/Routing";
import { NO_FILTER, saveFilter } from "../../utils/TagFilter";
import { ChangePasswordForm } from "../ChangePasswordForm";
import { iconButton } from "../Icons";
import { SyncStatusIndicator } from "../SyncStatusIndicator";
import { AbstractPage } from "./AbstractPage";

/**
 * The user's account: who's logged in, logging out, changing the password, the version. Opens the user's notes (see
 * NotesSession) if they aren't yet: the sync goes on, and logging out knows what isn't synced.
 */
export class AccountPage extends AbstractPage {
  private version?: string;
  private loggingOut = false;

  oninit() {
    const username = Auth.getUsername();
    if (username) void session.open(username);
    ApiEndpoints.Version.call({})
      .then((output) => (this.version = output.version + " (" + output.revision + ")"))
      .catch(() => undefined);
  }

  getPageTitle(): string {
    return "My Account - Notes";
  }

  render(): m.Children {
    const engine = session.engine;
    return m("main.accountpage", [
      m("header.topbar", [
        iconButton("chevron-left", "Back to the notes", () => Routing.goToScreen(Endpoints.HOME), "back"),
        m("h1", "My Account"),
        engine ? m(SyncStatusIndicator, { status: engine.status(), onRetry: () => engine.retryNow(), align: "right" }) : null,
      ]),
      m(".accountbody", [
        m("section.accountsection", [
          m("p", ["Logged in as ", m("strong", Auth.getUsername())]),
          m("p.muted", "Logging out removes your notes from this device. They stay on the server."),
          m("button.secondary", { type: "button", disabled: this.loggingOut, onclick: () => void this.logout() }, "Log out"),
        ]),
        m("section.accountsection", [
          m("h2", "Password"),
          m(ChangePasswordForm, { username: Auth.getUsername() ?? "" }),
        ]),
        this.version ? m("p.accountversion", "Version " + this.version) : null,
      ]),
    ]);
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
