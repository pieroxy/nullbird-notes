import "../css/style.scss";
import m from "mithril";
import { LoginPage } from "./components/pages/LoginPage";
import { NotesPage } from "./components/pages/NotesPage";
import { AuthenticatedPageResolver } from "./utils/AuthenticatedPageResolver";
import { Endpoints } from "./utils/navigation/Endpoints";

const routes: m.RouteDefs = {
  [Endpoints.LOGIN]: LoginPage,
  [Endpoints.HOME]: new AuthenticatedPageResolver(NotesPage),
};

m.route(document.getElementById("app")!, Endpoints.HOME, routes);
