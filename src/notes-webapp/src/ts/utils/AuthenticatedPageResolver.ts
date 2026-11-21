import m from "mithril";
import { OutdatedBanner } from "../components/OutdatedBanner";
import { Auth, AuthStatus } from "./Auth";
import { Endpoints } from "./navigation/Endpoints";
import { Routing } from "./navigation/Routing";

export interface ResolverOptions {
  /** Non-admins are sent to the notes. */
  adminOnly?: boolean;
}

/**
 * Wraps a page component that requires a logged-in session, redirecting to /login otherwise.
 * The check lives in a wrapper's view(), not in onmatch(): Auth.getStatus() resolves
 * asynchronously and then only triggers m.redraw(), which re-runs view() but never onmatch().
 */
export class AuthenticatedPageResolver implements m.RouteResolver {
  private readonly component: m.ComponentTypes<any, any>;
  private readonly options: ResolverOptions;

  constructor(component: m.ComponentTypes<any, any>, options: ResolverOptions = {}) {
    this.component = component;
    this.options = options;
  }

  onmatch(): m.ComponentTypes<any, any> {
    const wrapped = this.component;
    const adminOnly = this.options.adminOnly ?? false;
    return {
      view(vnode: m.Vnode) {
        switch (Auth.getStatus()) {
          case AuthStatus.LOGGED_IN:
            if (adminOnly && !Auth.isAdmin()) {
              Routing.goToScreen(Endpoints.HOME, true);
              return null;
            }
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
