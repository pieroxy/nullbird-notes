import m from "mithril";
import { icon } from "./Icons";

export interface CopyableTextAttrs {
  text: string;
  /** Under it. */
  hint: string;
  /** Instead of the hint, once the text is selected for lack of a clipboard. */
  selectedHint: string;
  /** Its own look: "password", "link". */
  kind: string;
}

const COPIED_FOR_MS = 2000;

/**
 * A text to pass on, with a button to copy it. The clipboard only works on HTTPS or localhost:
 * elsewhere, the button selects the text for Ctrl+C instead.
 */
export class CopyableText implements m.ClassComponent<CopyableTextAttrs> {
  private copied = false;
  private selectHint = false;
  private timer?: ReturnType<typeof setTimeout>;
  private text?: HTMLElement;

  onremove() {
    clearTimeout(this.timer);
  }

  view({ attrs }: m.Vnode<CopyableTextAttrs>): m.Children {
    const label = this.copied ? "Copied" : "Copy";
    return m(".copyable." + attrs.kind, [
      m(".copyrow", [
        m("code.copytext", { oncreate: (vnode: m.VnodeDOM) => (this.text = vnode.dom as HTMLElement) }, attrs.text),
        m("button.icon.copy", { type: "button", title: label, "aria-label": label, onclick: () => void this.copy(attrs.text) },
          icon(this.copied ? "check" : "copy")),
      ]),
      m("p.muted", this.selectHint ? attrs.selectedHint : attrs.hint),
    ]);
  }

  private async copy(text: string) {
    try {
      if (!navigator.clipboard) throw new Error("no clipboard here");
      await navigator.clipboard.writeText(text);
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
