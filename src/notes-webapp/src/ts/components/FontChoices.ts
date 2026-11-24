import m from "mithril";
import { FontFamily } from "../auto/api-types";
import { Auth } from "../utils/Auth";
import { chooseFont, FONT_SLOTS, FontSlot, loadFont, refreshFonts } from "../utils/Fonts";
import { Endpoints } from "../utils/navigation/Endpoints";

/**
 * My Account's choice of fonts, among those installed (see Fonts.ts): for the interface, the
 * notes, and fixed-width text. Each shows a sample in the font in use.
 */
export class FontChoices implements m.ClassComponent {
  private fonts?: FontFamily[];
  private loading = true;
  private busy?: FontSlot;
  private error?: string;

  oninit() {
    void refreshFonts().then((fonts) => {
      this.fonts = fonts;
      this.loading = false;
      m.redraw();
    });
  }

  view(): m.Children {
    return [
      m("h2", "Fonts"),
      this.intro(),
      m(".fontchoices", FONT_SLOTS.map(({ slot, label, monospaceOnly }) => {
        const chosen = loadFont(slot);
        // Offline, only what's chosen is known: still shown, not changeable.
        const options = this.fonts?.filter((f) => !monospaceOnly || f.monospace)
          ?? (chosen ? [{ id: chosen.id, name: chosen.name, monospace: monospaceOnly, files: [] }] : []);
        return m(".fontchoice", { key: slot }, [
          m("label", { for: "font-" + slot }, label),
          m("select", {
            id: "font-" + slot,
            disabled: this.fonts === undefined || this.busy !== undefined,
            value: chosen?.id ?? "",
            onchange: (e: Event) => void this.choose(slot, (e.target as HTMLSelectElement).value),
          }, [m("option", { value: "" }, "Default"), options.map((f) => m("option", { value: f.id }, f.name))]),
          m("p.fontsample." + slot, this.busy === slot ? "Downloading…"
            : ["The quick brown fox jumps over the lazy dog. ", m("b", "Bold"), ", ", m("i", "italic"), ", ", m("b", m("i", "both")), "."]),
        ]);
      })),
      this.error ? m("p.errormessage", this.error) : null,
    ];
  }

  private intro(): m.Children {
    if (this.loading) return m("p.muted", "Loading the installed fonts…");
    if (this.fonts === undefined) return m("p.muted", "The installed fonts can't be listed offline: the ones chosen still apply.");
    if (this.fonts.length === 0) {
      return m("p.muted", Auth.isAdmin()
        ? ["No fonts are installed yet: install some in ", m(m.route.Link, { href: Endpoints.ADMIN + "/fonts" }, "Administration"), "."]
        : "No fonts are installed yet: an admin installs them.");
    }
    return m("p.muted", "Among the fonts installed on the server. Each one chosen is downloaded to this device, to work offline.");
  }

  private async choose(slot: FontSlot, id: string) {
    const family = id ? this.fonts?.find((f) => f.id === id) : undefined;
    this.busy = slot;
    this.error = undefined;
    try {
      await chooseFont(slot, family);
    } catch (err) {
      this.error = (err as Error).message;
    } finally {
      this.busy = undefined;
      m.redraw();
    }
  }
}
