import m from "mithril";

/** Page base: sets the browser tab title. */
export abstract class AbstractPage<A = {}> implements m.ClassComponent<A> {
  abstract getPageTitle(): string;
  abstract render(vnode: m.Vnode<A, this>): m.Children;

  view(vnode: m.Vnode<A, this>): m.Children {
    window.document.title = this.getPageTitle();
    return this.render(vnode);
  }
}
