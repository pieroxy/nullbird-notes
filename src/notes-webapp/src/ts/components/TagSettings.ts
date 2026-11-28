import m from "mithril";
import { FontFamily, NoteDefaults, TagOptions } from "../auto/api-types";
import { refreshFonts } from "../utils/Fonts";
import { SORT_ORDERS } from "../utils/NoteOptions";
import { isValidTag, MAX_TAG_PATH_LENGTH, TAG_RULE } from "../sync/Metadata";
import { isUnder, normalizeTag } from "../utils/TagTree";
import { noteStripe, TAG_COLORS, tagClasses, tagDot } from "../utils/TagLook";
import { iconButton } from "./Icons";

export interface TagSettingsAttrs {
  /** Not "tag": mithril takes an object with a tag property for a child, not attributes. */
  tagName: string;
  /** Notes with it, deleted ones included: as many as renaming or removing it changes. */
  count: number;
  /** Every tag in use, those above them included (see TagTree): renaming to one of them merges the two. */
  allTags: string[];
  /** How many tags are under it: they go along when it's renamed or removed. */
  under: number;
  /**
   * What the tags above it say for an option, the nearest first to say, and which one: what it
   * follows while it doesn't set its own (see TagTree.nearest). Nothing for a tag at the top.
   */
  inherited: <T>(pick: (options: TagOptions) => T | undefined | null) => { value?: T; tag?: string };
  /** The user's defaults for their notes: what a flag no tag sets follows. */
  noteDefaults: NoteDefaults;
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

type ColorKey = "dot" | "stripe" | "background" | "text" | "notes";

/** The tag's own colors, as the dialog lists them. */
const TAG_COLOR_SLOTS: { key: ColorKey; label: string }[] = [
  { key: "dot", label: "Dot" },
  { key: "stripe", label: "Left stripe" },
  { key: "background", label: "Background" },
  { key: "text", label: "Text" },
];

/** Its notes' flags: not set, on or off. */
const FLAGS: { key: "markdown" | "monospace" | "wrap"; label: string }[] = [
  { key: "markdown", label: "Markdown" },
  { key: "monospace", label: "Fixed-width text" },
  { key: "wrap", label: "Wrap long lines" },
];

function notes(count: number): string {
  return count === 1 ? "1 note" : count + " notes";
}

function tags(count: number): string {
  return count === 1 ? "tag" : count + " tags";
}

/**
 * A tag's settings, from the tag panel's edit mode: its name (renaming to a tag in use merges
 * them), how it looks, what its notes get, and removing it from its notes. A dialog over the
 * notes; the whole screen on a phone.
 */
export class TagSettings implements m.ClassComponent<TagSettingsAttrs> {
  private name = "";
  private error?: string;
  private busy = false;
  private attrs?: TagSettingsAttrs;
  /** The fonts installed, to choose from: undefined offline, or before the server said. */
  private fonts?: FontFamily[];
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
    void refreshFonts().then((fonts) => {
      this.fonts = fonts;
      m.redraw();
    });
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
              maxlength: MAX_TAG_PATH_LENGTH,
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
          m("p.muted", "Renaming to a tag you already have merges the two. A \"/\" puts it under another tag: work/clients."
            + (attrs.under > 0 ? " The " + tags(attrs.under) + " under it go along." : "")),
          // Enabled whatever the name: Enter right after typing may come before the redraw, and a disabled
          // submit button stops it. The same name does nothing.
          m(".buttons", m("button", { type: "submit", disabled: this.busy }, "Rename")),
        ]),
        this.error ? m("p.errormessage", this.error) : null,
        this.looks(attrs),
        this.forNotes(attrs),
        m(".dangerzone", [
          m("button.danger", { type: "button", disabled: this.busy || count === 0, onclick: () => void this.remove() },
            count > 0 ? "Remove from its " + notes(count) : "No note has it"),
          m("p.muted", (attrs.under > 0 ? "The " + tags(attrs.under) + " under it go too. " : "") + "The notes themselves stay."),
        ]),
      ]));
  }

  /** A row of swatches for one color of the tag's options. */
  private colorRow(attrs: TagSettingsAttrs, key: ColorKey, label: string): m.Children {
    const options = attrs.options ?? {};
    return m(".colorrow", { role: "radiogroup", "aria-label": label }, [
      m("span.colorlabel", label),
      m(".swatches", [
        swatch(undefined, "None", options[key] === undefined, () => attrs.onOptions({ [key]: undefined })),
        TAG_COLORS.map((color) => swatch(color.id, color.label, options[key] === color.id, () => attrs.onOptions({ [key]: color.id }))),
      ]),
    ]);
  }

  /** How the tag looks wherever it shows, and how it'll look. */
  private looks(attrs: TagSettingsAttrs): m.Children {
    const options = attrs.options ?? {};
    return m("section.tagsection", [
      m("h3", "The tag"),
      TAG_COLOR_SLOTS.map(({ key, label }) => this.colorRow(attrs, key, label)),
      m(".tagpreview", { "aria-hidden": "true" }, m("span.tag" + tagClasses(options), m("span.tagname", [tagDot(options), attrs.tagName]))),
    ]);
  }

  /** What its notes get, unless they say otherwise (see NoteOptions). */
  private forNotes(attrs: TagSettingsAttrs): m.Children {
    const options = attrs.options ?? {};
    const tag = attrs.tagName;
    const fonts = this.fonts;
    /** "Not set", or what it follows meanwhile, and from which tag. */
    const notSet = <T>(pick: (o: TagOptions) => T | undefined | null, say: (value: T) => string) => {
      const from = attrs.inherited(pick);
      return from.tag === undefined ? "Not set" : "As " + from.tag + ": " + say(from.value as T);
    };
    /** A flag no tag above sets follows the user's default: off, but wrapping, unless they said. */
    const notSetFlag = (key: "markdown" | "monospace" | "wrap") => {
      const from = attrs.inherited((o) => o[key]);
      if (from.tag !== undefined) return "As " + from.tag + ": " + (from.value ? "on" : "off");
      const byDefault = attrs.noteDefaults[key] ?? key === "wrap";
      return "Not set: your default, " + (byDefault ? "on" : "off");
    };
    const fontName = (id: string) => fonts?.find((f) => f.id === id)?.name ?? "a font";
    /** A box set or not: when not, what it follows meanwhile. */
    const followed = (pick: (o: TagOptions) => boolean | undefined | null, own: boolean | undefined, what: string) => {
      const from = attrs.inherited(pick);
      return !own && from.value === true ? m("p.muted.inherited", what + ", as " + from.tag + ".") : null;
    };
    const fontSelect = (key: "font" | "monoFont", label: string, monospaceOnly: boolean) => {
      const chosen = options[key];
      // Offline, only the one chosen is known: still shown.
      const listed = fonts?.filter((f) => !monospaceOnly || f.monospace) ?? [];
      const unknown = chosen && !listed.some((f) => f.id === chosen);
      return m("label.optionrow", [
        m("span.colorlabel", label),
        m("select", {
          value: chosen ?? "",
          onchange: (e: Event) => attrs.onOptions({ [key]: (e.target as HTMLSelectElement).value || undefined }),
        }, [
          m("option", { value: "" }, notSet((o) => o[key], fontName)),
          listed.map((f) => m("option", { value: f.id }, f.name)),
          unknown ? m("option", { value: chosen }, fonts ? "A deleted font" : "The one chosen") : null,
        ]),
      ]);
    };
    return m("section.tagsection", [
      m("h3", "For its notes"),
      m("p.muted", "Unless a note says otherwise. With several such tags, the first in your order wins. A tag under another"
        + " follows it, unless set here; stripes stack."),
      this.colorRow(attrs, "notes", "Stripe in the list"),
      m("label.checkfield.hiddenfield", [
        m("input", {
          type: "checkbox",
          checked: options.hidden === true,
          onchange: (e: Event) => attrs.onOptions({ hidden: (e.target as HTMLInputElement).checked ? true : undefined }),
        }),
        m("span", [m("strong", "Hidden"), ": its notes show only when it's selected. For an archive."]),
      ]),
      followed((o) => o.hidden, options.hidden, "Hidden"),
      FLAGS.map(({ key, label }) => m("label.optionrow", [
        m("span.colorlabel", label),
        m("select", {
          value: options[key] === true ? "on" : options[key] === false ? "off" : "",
          onchange: (e: Event) => {
            const value = (e.target as HTMLSelectElement).value;
            attrs.onOptions({ [key]: value === "on" ? true : value === "off" ? false : undefined });
          },
        }, [m("option", { value: "" }, notSetFlag(key)), m("option", { value: "on" }, "On"),
          m("option", { value: "off" }, "Off")]),
      ])),
      fontSelect("font", "Font", false),
      fontSelect("monoFont", "Fixed-width font", true),
      fonts !== undefined && fonts.length === 0 ? m("p.muted", "No fonts are installed: an admin installs them.") : null,
      m("label.optionrow", [
        m("span.colorlabel", "Sort the list"),
        m("select", {
          value: options.sort ?? "",
          onchange: (e: Event) => attrs.onOptions({ sort: (e.target as HTMLSelectElement).value || undefined }),
        }, [m("option", { value: "" }, notSet((o) => o.sort, (sort) => (SORT_ORDERS.find((o) => o.id === sort)?.label ?? sort).toLowerCase())),
          SORT_ORDERS.map((o) => m("option", { value: o.id }, o.label))]),
      ]),
      m("p.muted", "While it's selected."),
      m("label.checkfield", [
        m("input", {
          type: "checkbox",
          checked: options.rendered === true,
          onchange: (e: Event) => attrs.onOptions({ rendered: (e.target as HTMLInputElement).checked ? true : undefined }),
        }),
        m("span", "Markdown notes open rendered, not in the editor"),
      ]),
      followed((o) => o.rendered, options.rendered, "Rendered"),
      m(".tagpreview", { "aria-hidden": "true" }, m(".previewnote", [
        noteStripe(options.notes ? [options.notes] : []),
        m("strong", "A note tagged " + tag),
        m("span.muted", "In the list"),
      ])),
    ]);
  }

  private async rename() {
    const attrs = this.attrs!;
    // Empty levels go: "/work//clients/" is "work/clients".
    const to = normalizeTag(this.name.trim());
    if (to === attrs.tagName) return;
    if (!isValidTag(to)) {
      this.error = TAG_RULE;
      return;
    }
    if (isUnder(to, attrs.tagName)) {
      this.error = "A tag can't move under itself.";
      return;
    }
    if (attrs.allTags.includes(to) && !confirm("Merge \"" + attrs.tagName + "\" into \"" + to + "\"?\n\nThe " + notes(attrs.count)
      + " tagged \"" + attrs.tagName + "\" will be tagged \"" + to + "\" instead, and \"" + attrs.tagName + "\" disappears.")) return;
    await this.run(() => attrs.onRename(to), "Not renamed: ");
  }

  private async remove() {
    const attrs = this.attrs!;
    const under = attrs.under > 0 ? " and the " + tags(attrs.under) + " under it" : "";
    if (!confirm("Remove the tag \"" + attrs.tagName + "\"" + under + " from " + notes(attrs.count) + "?\n\nThe notes themselves stay.")) return;
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
