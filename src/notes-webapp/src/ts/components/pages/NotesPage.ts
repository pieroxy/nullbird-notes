import m from "mithril";
import { ApiEndpoints } from "../../auto/ApiEndpoints";
import { Auth } from "../../utils/Auth";
import { Endpoints } from "../../utils/navigation/Endpoints";
import { Routing } from "../../utils/navigation/Routing";
import { SyncStatus } from "../SyncStatus";
import { AbstractPage } from "./AbstractPage";

/** The note list on the left, the editor on the right. Both empty until notes exist. */
export class NotesPage extends AbstractPage {
  private version: string | undefined;

  oninit() {
    ApiEndpoints.Version.call({})
      .then((output) => {
        this.version = output.version + " (" + output.revision + ")";
        m.redraw();
      })
      .catch(() => {
        // Only shown as a hint: nothing to report.
      });
  }

  getPageTitle(): string {
    return "Notes";
  }

  render(): m.Children {
    return m("main.notespage", [
      m("header.topbar", [
        m("span.appname", "Notes"),
        m(SyncStatus),
        m("span.spacer"),
        m("span.username", Auth.getUsername()),
        m("button.secondary", { onclick: () => this.logout() }, "Log out"),
      ]),
      m(".panes", [
        m("aside.notelist", [
          m(".empty", "No notes yet."),
          this.version ? m(".version", "Version " + this.version) : null,
        ]),
        m("section.editor", m(".empty", "Select a note or create one.")),
      ]),
    ]);
  }

  private logout() {
    Auth.logout().then(() => Routing.goToScreen(Endpoints.LOGIN));
  }
}
