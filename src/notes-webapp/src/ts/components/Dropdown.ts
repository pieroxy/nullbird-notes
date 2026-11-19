import m from "mithril";

export interface DropdownAttrs {
  /** The trigger's tooltip and accessible name. */
  label: string;
  trigger: m.Children;
  triggerClass?: string;
  align: "left" | "right";
}

/** A button opening a panel (its children). Clicking anywhere, or Escape, closes it. */
export class Dropdown implements m.ClassComponent<DropdownAttrs> {
  private open = false;
  private dom?: Element;
  private readonly onDocumentClick = (e: MouseEvent) => {
    if (this.open && !this.dom?.querySelector(".dropdowntrigger")?.contains(e.target as Node)) this.close();
  };
  private readonly onKeyDown = (e: KeyboardEvent) => {
    if (this.open && e.key === "Escape") this.close();
  };

  oncreate({ dom }: m.VnodeDOM<DropdownAttrs>) {
    this.dom = dom;
    document.addEventListener("click", this.onDocumentClick);
    document.addEventListener("keydown", this.onKeyDown);
  }

  onremove() {
    document.removeEventListener("click", this.onDocumentClick);
    document.removeEventListener("keydown", this.onKeyDown);
  }

  view({ attrs, children }: m.Vnode<DropdownAttrs>): m.Children {
    return m(".dropdown", [
      m("button.dropdowntrigger" + (attrs.triggerClass ? "." + attrs.triggerClass : ""), {
        type: "button", title: attrs.label, "aria-label": attrs.label, "aria-expanded": String(this.open),
        onclick: () => (this.open = !this.open),
      }, attrs.trigger),
      this.open ? m(".dropdownpanel." + attrs.align, children) : null,
    ]);
  }

  private close() {
    this.open = false;
    m.redraw();
  }
}
