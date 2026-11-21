import m from "mithril";
import { session } from "../sync/Session";
import { Endpoints } from "../utils/navigation/Endpoints";
import { Routing } from "../utils/navigation/Routing";
import { icon, iconButton } from "./Icons";
import { SyncStatusIndicator } from "./SyncStatusIndicator";

export interface PageSection {
  /** In the route: the page's own, then "/" + id. */
  id: string;
  title: string;
  render: () => m.Children;
}

export interface SectionedPageAttrs {
  /** The page's own route, e.g. /account. */
  route: Endpoints;
  title: string;
  sections: PageSection[];
  /** From the route: absent at the page's own route. */
  section?: string;
  /** Added to main's classes. */
  className: string;
  /** In the list, above the sections. */
  navHeader?: m.Children;
  /** At the bottom of the list. */
  navFooter?: m.Children;
}

/**
 * A page made of sections (My Account, Administration), each at its own route. Wide screens show
 * the list of sections beside the one open (the page's own route opens the first); narrow ones, one
 * at a time: the list at the page's route, a section at its own. Every element is always rendered;
 * which ones show depends on the screen width and .insection (see style.scss).
 */
export function sectionedPage(attrs: SectionedPageAttrs): m.Children {
  const engine = session.engine;
  const chosen = attrs.sections.find((section) => section.id === attrs.section);
  const shown = chosen ?? attrs.sections[0];
  return m("main.sectionedpage." + attrs.className + (chosen ? ".insection" : ""), [
    m("header.topbar", [
      iconButton("chevron-left", "Back to the notes", () => Routing.goToScreen(Endpoints.HOME), "backhome"),
      iconButton("chevron-left", "Back to " + attrs.title, () => Routing.goToScreen(attrs.route), "backlist"),
      m("h1.pagetitle", attrs.title),
      m("h1.sectiontitle", shown.title),
      engine ? m(SyncStatusIndicator, { status: engine.status(), onRetry: () => engine.retryNow(), align: "right" }) : null,
    ]),
    m(".sectionedlayout", [
      m("nav.sectionednav", [
        attrs.navHeader ? m(".navheader", attrs.navHeader) : null,
        m("ul.sectionlinks", attrs.sections.map((section) =>
          m("li", { key: section.id }, m(m.route.Link, {
            href: attrs.route + "/" + section.id,
            class: section === shown ? "active" : "",
          }, [m("span", section.title), icon("chevron-right")])))),
        attrs.navFooter ? m(".navfooter", attrs.navFooter) : null,
      ]),
      m(".sectionedbody", m("section.pagesection", { key: shown.id }, shown.render())),
    ]),
  ]);
}
