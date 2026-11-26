import m from "mithril";
import { session } from "../../sync/Session";
import { Auth } from "../../utils/Auth";
import { Endpoints } from "../../utils/navigation/Endpoints";
import { ConfigSection } from "../AdminConfig";
import { FontsSection } from "../AdminFonts";
import { LogsSection } from "../AdminLogs";
import { MailOutSection } from "../AdminMailOut";
import { UserDetail, UserList } from "../AdminUsers";
import { PageSection, sectionedPage } from "../SectionedPage";
import { AbstractPage } from "./AbstractPage";

export interface AdminPageAttrs {
  /** From the route: /admin/:section. Absent at /admin. */
  section?: string;
  /** From the route: /admin/users/:username, a user's page in the Users section. */
  username?: string;
}

/**
 * Administration, for admins only (see AuthenticatedPageResolver): its sections, each at
 * /admin/:section (see sectionedPage). Opens the user's notes as My Account does, so the sync goes on.
 */
export class AdminPage extends AbstractPage<AdminPageAttrs> {
  private username?: string;
  private readonly sections: PageSection[] = [
    { id: "users", title: "Users", render: () => this.username !== undefined ? m(UserDetail, { key: this.username, username: this.username }) : m(UserList) },
    { id: "config", title: "Config", render: () => m(ConfigSection) },
    { id: "fonts", title: "Fonts", render: () => m(FontsSection) },
    { id: "mailout", title: "Mail out", render: () => m(MailOutSection) },
    { id: "logs", title: "Logs", render: () => m(LogsSection), wide: true },
  ];

  oninit() {
    const username = Auth.getUsername();
    if (username) void session.open(username);
  }

  getPageTitle(): string {
    return "Administration - Notes";
  }

  render({ attrs }: m.Vnode<AdminPageAttrs>): m.Children {
    this.username = attrs.username;
    return sectionedPage({
      route: Endpoints.ADMIN,
      title: "Administration",
      sections: this.sections,
      section: attrs.username !== undefined ? "users" : attrs.section,
      className: "adminpage",
    });
  }
}
