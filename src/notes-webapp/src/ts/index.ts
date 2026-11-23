import "../css/style.scss";
import m from "mithril";
import { AccountPage } from "./components/pages/AccountPage";
import { AdminPage } from "./components/pages/AdminPage";
import { LoginPage } from "./components/pages/LoginPage";
import { NotesPage } from "./components/pages/NotesPage";
import { AuthenticatedPageResolver } from "./utils/AuthenticatedPageResolver";
import { Endpoints } from "./utils/navigation/Endpoints";
import { registerServiceWorker } from "./utils/ServiceWorker";
import { initAppearance } from "./utils/Appearance";

const routes: m.RouteDefs = {
  [Endpoints.LOGIN]: LoginPage,
  [Endpoints.HOME]: new AuthenticatedPageResolver(NotesPage),
  [Endpoints.ACCOUNT]: new AuthenticatedPageResolver(AccountPage),
  [Endpoints.ACCOUNT_SECTION]: new AuthenticatedPageResolver(AccountPage),
  [Endpoints.ADMIN]: new AuthenticatedPageResolver(AdminPage, { adminOnly: true }),
  [Endpoints.ADMIN_SECTION]: new AuthenticatedPageResolver(AdminPage, { adminOnly: true }),
  [Endpoints.ADMIN_USER]: new AuthenticatedPageResolver(AdminPage, { adminOnly: true }),
};

// Gone when compat.js found the browser too old, and replaced it with a message.
const root = document.getElementById("app");
if (root) {
  initAppearance(() => m.redraw());
  m.route(root, Endpoints.HOME, routes);
  registerServiceWorker();
}
