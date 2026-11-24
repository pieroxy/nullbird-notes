import m from "mithril";
import { TagOptions } from "../auto/api-types";
import { isValidTag } from "../sync/Metadata";
import { noteStripe, TAG_COLORS, tagClasses, tagDot } from "../utils/TagLook";
import { iconButton } from "./Icons";

export interface TagSettingsAttrs {
  /** Not "tag": mithril takes an object with a tag property for a child, not attributes. */
  tagName: string;
  /** Notes with it, deleted ones included: as many as renaming or removing it changes. */
  count: number;
  /** Every tag in use: renaming to one of them merges the two. */
  allTags: string[];
  /** Resolves once done; rejects with why not (offline…). */
  onRename: (to: string) => Promise<void>;
  onRemove: () => Promise<void>;
  onClose: () => void;
  /** Its options now; undefined for none. */
  options: TagOptions | undefined;
  /**
   * Changes some of them, the others as they are now: applied at once, synced. A change, not the
   * whole: clicks quicker than redraws would each start from the options as last drawn.
   */
  onOptions: (change: Partial<TagOptions>) => void;
}

/** The colors a tag can have, as the dialog lists them. */
const COLOR_SLOTS: { key: keyof TagOptions; label: string }[] = [
  { key: "dot", label: "Dot" },
  { key: "stripe", label: "Left stripe" },
  { key: "background", label: "Background" },
  { key: "text", label: "Text" },
  { key: "notes", label: "Stripe on its notes" },
];

function notes(count: number): string {
  return count === 1 ? "1 note" : count + " notes";
}

/**
 * A tag's settings, from the tag panel's edit mode: its name (renaming to a tag in use merges
 * them), and removing it from its notes. A dialog over the notes; the whole screen on a phone.
 * More options come here (color, hidden…).
 */
export class TagSettings implements m.ClassComponent<TagSettingsAttrs> {
  private name = "";
  private error?: string;
  private busy = false;
  private attrs?: TagSettingsAttrs;
  /** Before the page's own keys: Escape puts the name back first, a second one closes. */
  private readonly onKeyDown = (e: KeyboardEvent) => {
    if (e.key !== "Escape" || this.busy || !this.attrs) return;
    e.stopPropagation();
    e.preventDefault();
    if (this.name !== this.attrs.tagName) {
      this.name = this.attrs.tagName;
      this.error = undefined;
    } else {
      this.attrs.onClose();
    }
    m.redraw();
  };

  oninit({ attrs }: m.Vnode<TagSettingsAttrs>) {
    this.name = attrs.tagName;
  }

  oncreate() {
    document.addEventListener("keydown", this.onKeyDown, true);
  }

  onremove() {
    document.removeEventListener("keydown", this.onKeyDown, true);
  }

  view({ attrs }: m.Vnode<TagSettingsAttrs>): m.Children {
    this.attrs = attrs;
    const { tagName: tag, count } = attrs;
    return m(".modal", { onclick: (e: MouseEvent) => { if (e.target === e.currentTarget && !this.busy) attrs.onClose(); } },
      m(".dialog.tagsettings", { role: "dialog", "aria-modal": "true", "aria-labelledby": "tagsettingstitle" }, [
        m(".dialoghead", [
          m("h2#tagsettingstitle", "Tag: " + tag),
          iconButton("x", "Close", () => attrs.onClose(), "dialogclose"),
        ]),
        m("form.stackedform", { onsubmit: (e: Event) => { e.preventDefault(); void this.rename(); } }, [
          m("label.field", [
            m("span.fieldlabel", "Name"),
            m("input#tagname", {
              value: this.name,
              maxlength: 64,
              autocomplete: "off",
              autocapitalize: "off",
              spellcheck: false,
              disabled: this.busy,
              oncreate: ({ dom }: m.VnodeDOM) => (dom as HTMLInputElement).select(),
              oninput: (e: Event) => {
                this.name = (e.target as HTMLInputElement).value;
                this.error = undefined;
              },
            }),
          ]),
          m("p.muted", "Renaming to a tag you already have merges the two."),
          // Enabled whatever the name: Enter right after typing may come before the redraw, and a disabled
          // submit button stops it. The same name does nothing.
          m(".buttons", m("button", { type: "submit", disabled: this.busy }, "Rename")),
        ]),
        this.error ? m("p.errormessage", this.error) : null,
        m("label.checkfield.hiddenfield", [
          m("input", {
            type: "checkbox",
            checked: attrs.options?.hidden === true,
            onchange: (e: Event) => attrs.onOptions({ hidden: (e.target as HTMLInputElement).checked ? true : undefined }),
          }),
          m("span", [m("strong", "Hidden"), ": its notes show only when it's selected. For an archive."]),
        ]),
        this.colors(attrs),
        m(".dangerzone", [
          m("button.danger", { type: "button", disabled: this.busy || count === 0, onclick: () => void this.remove() },
            count > 0 ? "Remove from its " + notes(count) : "No note has it"),
          m("p.muted", "The notes themselves stay."),
        ]),
      ]));
  }

  /** A row of swatches per color, then how the tag and its notes look with them. */
  private colors(attrs: TagSettingsAttrs): m.Children {
    const options = attrs.options ?? {};
    const tag = attrs.tagName;
    return m(".tagcolors", [
      COLOR_SLOTS.map(({ key, label }) => m(".colorrow", { role: "radiogroup", "aria-label": label }, [
        m("span.colorlabel", label),
        m(".swatches", [
          swatch(undefined, "None", options[key] === undefined, () => attrs.onOptions({ [key]: undefined })),
          TAG_COLORS.map((color) => swatch(color.id, color.label, options[key] === color.id, () => attrs.onOptions({ [key]: color.id }))),
        ]),
      ])),
      m(".tagpreview", { "aria-hidden": "true" }, [
        m("span.tag" + tagClasses(options), m("span.tagname", [tagDot(options), tag])),
        m(".previewnote", [
          noteStripe(options.notes ? [options.notes] : []),
          m("strong", "A note tagged " + tag),
          m("span.muted", "In the list"),
        ]),
      ]),
    ]);
  }

  private async rename() {
    const attrs = this.attrs!;
    const to = this.name.trim();
    if (to === attrs.tagName) return;
    if (!isValidTag(to)) {
      this.error = "A tag is 1 to 64 characters, without spaces.";
      return;
    }
    if (attrs.allTags.includes(to) && !confirm("Merge \"" + attrs.tagName + "\" into \"" + to + "\"?\n\nThe " + notes(attrs.count)
      + " tagged \"" + attrs.tagName + "\" will be tagged \"" + to + "\" instead, and \"" + attrs.tagName + "\" disappears.")) return;
    await this.run(() => attrs.onRename(to), "Not renamed: ");
  }

  private async remove() {
    const attrs = this.attrs!;
    if (!confirm("Remove the tag \"" + attrs.tagName + "\" from its " + notes(attrs.count) + "?\n\nThe notes themselves stay.")) return;
    await this.run(() => attrs.onRemove(), "Not removed: ");
  }

  private async run(action: () => Promise<void>, failure: string) {
    this.busy = true;
    this.error = undefined;
    try {
      await action();
    } catch (err) {
      this.error = failure + (err as Error).message;
    } finally {
      this.busy = false;
      m.redraw();
    }
  }
}

/** A color to pick, as a radio button; none is a crossed-out one. */
function swatch(color: string | undefined, label: string, chosen: boolean, onclick: () => void): m.Children {
  return m("button.swatch" + (color ? ".tagfill-" + color : ".none") + (chosen ? ".chosen" : ""), {
    type: "button", role: "radio", "aria-checked": String(chosen), title: label, "aria-label": label, onclick,
  });
}
