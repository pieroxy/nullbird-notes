import m from "mithril";
import { OutdatedBanner } from "../components/OutdatedBanner";
import { Auth, AuthStatus } from "./Auth";
import { Endpoints } from "./navigation/Endpoints";
import { Routing } from "./navigation/Routing";

/**
 * Wraps a page component that requires a logged-in session, redirecting to /login otherwise.
 * The check lives in a wrapper's view(), not in onmatch(): Auth.getStatus() resolves
 * asynchronously and then only triggers m.redraw(), which re-runs view() but never onmatch().
 */
export class AuthenticatedPageResolver implements m.RouteResolver {
  private readonly component: m.ComponentTypes<any, any>;

  constructor(component: m.ComponentTypes<any, any>) {
    this.component = component;
  }

  onmatch(): m.ComponentTypes<any, any> {
    const wrapped = this.component;
    return {
      view(vnode: m.Vnode) {
        switch (Auth.getStatus()) {
          case AuthStatus.LOGGED_IN:
            return m(wrapped, vnode.attrs);
          case AuthStatus.NO_SESSION:
            Routing.goToScreen(Endpoints.LOGIN, true);
            return null;
          case AuthStatus.UNREACHABLE:
            return m(".pagemessage", "Cannot reach the server. Retrying…");
          case AuthStatus.OUTDATED:
            return m(OutdatedBanner);
          case AuthStatus.CHECKING:
            return null;
        }
      },
    };
  }
}
