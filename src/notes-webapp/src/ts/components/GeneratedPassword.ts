import m from "mithril";
import { icon } from "./Icons";

export interface GeneratedPasswordAttrs {
  password: string;
}

const COPIED_FOR_MS = 2000;

/**
 * A password the server picked, for the admin to pass on, with a button to copy it. The clipboard
 * only works on HTTPS or localhost: elsewhere, the button selects the password for Ctrl+C instead.
 */
export class GeneratedPassword implements m.ClassComponent<GeneratedPasswordAttrs> {
  private copied = false;
  private selectHint = false;
  private timer?: ReturnType<typeof setTimeout>;
  private text?: HTMLElement;

  onremove() {
    clearTimeout(this.timer);
  }

  view({ attrs }: m.Vnode<GeneratedPasswordAttrs>): m.Children {
    const label = this.copied ? "Copied" : "Copy";
    return m(".generatedpassword", [
      m(".passwordrow", [
        m("code.password", { oncreate: (vnode: m.VnodeDOM) => (this.text = vnode.dom as HTMLElement) }, attrs.password),
        m("button.icon.copy", { type: "button", title: label, "aria-label": label, onclick: () => void this.copy(attrs.password) },
          icon(this.copied ? "check" : "copy")),
      ]),
      m("p.muted", this.selectHint
        ? "Selected: press Ctrl+C (⌘C on a Mac) to copy it. It won't be shown again."
        : "Pass it on to them now: it won't be shown again."),
    ]);
  }

  private async copy(password: string) {
    try {
      if (!navigator.clipboard) throw new Error("no clipboard here");
      await navigator.clipboard.writeText(password);
      this.copied = true;
      clearTimeout(this.timer);
      this.timer = setTimeout(() => {
        this.copied = false;
        m.redraw();
      }, COPIED_FOR_MS);
    } catch {
      this.select();
      this.selectHint = true;
    }
    m.redraw();
  }

  private select() {
    if (!this.text) return;
    const range = document.createRange();
    range.selectNodeContents(this.text);
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
  }
}
