import m from "mithril";
import { session } from "../../sync/Session";
import { Auth } from "../../utils/Auth";
import { Endpoints } from "../../utils/navigation/Endpoints";
import { PageSection, sectionedPage } from "../SectionedPage";
import { AbstractPage } from "./AbstractPage";

export interface AdminPageAttrs {
  /** From the route: /admin/:section. Absent at /admin. */
  section?: string;
}

/**
 * Administration, for admins only (see AuthenticatedPageResolver): its sections, each at
 * /admin/:section (see sectionedPage). Opens the user's notes as My Account does, so the sync goes on.
 */
export class AdminPage extends AbstractPage<AdminPageAttrs> {
  private readonly sections: PageSection[] = [
    { id: "users", title: "Users", render: () => this.placeholder("Users", "The users of this server.") },
    { id: "config", title: "Config", render: () => this.placeholder("Config", "This server's configuration.") },
  ];

  oninit() {
    const username = Auth.getUsername();
    if (username) void session.open(username);
  }

  getPageTitle(): string {
    return "Administration - Notes";
  }

  render({ attrs }: m.Vnode<AdminPageAttrs>): m.Children {
    return sectionedPage({
      route: Endpoints.ADMIN,
      title: "Administration",
      sections: this.sections,
      section: attrs.section,
      className: "adminpage",
    });
  }

  private placeholder(title: string, what: string): m.Children {
    return [
      m("h2", title),
      m("p.muted", what + " Coming soon."),
    ];
  }
}
