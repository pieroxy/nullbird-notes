import "../css/style.scss";
import m from "mithril";
import { AccountPage } from "./components/pages/AccountPage";
import { AdminPage } from "./components/pages/AdminPage";
import { LoginPage } from "./components/pages/LoginPage";
import { NotesPage } from "./components/pages/NotesPage";
import { AuthenticatedPageResolver } from "./utils/AuthenticatedPageResolver";
import { Endpoints } from "./utils/navigation/Endpoints";

const routes: m.RouteDefs = {
  [Endpoints.LOGIN]: LoginPage,
  [Endpoints.HOME]: new AuthenticatedPageResolver(NotesPage),
  [Endpoints.ACCOUNT]: new AuthenticatedPageResolver(AccountPage),
  [Endpoints.ACCOUNT_SECTION]: new AuthenticatedPageResolver(AccountPage),
  [Endpoints.ADMIN]: new AuthenticatedPageResolver(AdminPage, { adminOnly: true }),
  [Endpoints.ADMIN_SECTION]: new AuthenticatedPageResolver(AdminPage, { adminOnly: true }),
  [Endpoints.ADMIN_USER]: new AuthenticatedPageResolver(AdminPage, { adminOnly: true }),
};

m.route(document.getElementById("app")!, Endpoints.HOME, routes);
