import m from "mithril";
import { Api } from "../../utils/api/Api";
import { OutdatedBanner } from "../OutdatedBanner";

/** Page base: sets the browser tab title, and offers to reload once the server runs another build. */
export abstract class AbstractPage<A = {}> implements m.ClassComponent<A> {
  abstract getPageTitle(): string;
  abstract render(vnode: m.Vnode<A, this>): m.Children;

  view(vnode: m.Vnode<A, this>): m.Children {
    window.document.title = this.getPageTitle();
    return [this.render(vnode), Api.outdated ? m(OutdatedBanner) : null];
  }
}
