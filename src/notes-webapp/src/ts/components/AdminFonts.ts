import m from "mithril";
import { FontFamily, FontStyle, UploadedFont } from "../auto/api-types";
import { ApiEndpoints } from "../auto/ApiEndpoints";
import { Api } from "../utils/api/Api";
import { Face, face, FONT_STYLES, styleFromFileName, styleLabel } from "../utils/Fonts";

/** A file of the font being edited: one already in it, or one just uploaded. */
interface Row {
  style: FontStyle;
  file: string;
  /** The uploaded file's name; absent for a file already in the font. */
  fileName?: string;
  format: string;
  size: number;
  variable: boolean;
  weightMin?: number;
  weightMax?: number;
}

interface Form {
  /** Absent: a new font. */
  id?: string;
  name: string;
  monospace: boolean;
  rows: Row[];
}

const MAX_FILES = 4;

/** Previews register each file under its own name, apart from the fonts chosen (see Fonts.ts). */
const previews = new Map<string, string>();

function previewFamily(faces: Face[]): string {
  const key = faces.map((f) => f.url + " " + f.weight + " " + f.style).join("|");
  let name = previews.get(key);
  if (name === undefined) {
    name = "nbpreview-" + previews.size;
    for (const f of faces) document.fonts.add(new FontFace(name, "url(" + f.url + ")", { weight: f.weight, style: f.style }));
    previews.set(key, name);
  }
  return name;
}

function rowFace(row: Row): Face {
  return face({ style: row.style, file: row.file, format: row.format, size: row.size, variable: row.variable, weightMin: row.weightMin, weightMax: row.weightMax });
}

function sizeText(bytes: number): string {
  return bytes < 1024 * 1024 ? Math.max(1, Math.round(bytes / 1024)) + " KB" : (bytes / (1024 * 1024)).toFixed(1) + " MB";
}

function describe(row: Row): string {
  const variable = !row.variable ? "" : row.weightMin !== undefined && row.weightMax !== undefined
    ? ", variable: weights " + row.weightMin + " to " + row.weightMax : ", variable";
  return row.format.toUpperCase() + ", " + sizeText(row.size) + variable;
}

const SAMPLE = "The quick brown fox jumps over the lazy dog";

/**
 * Administration's fonts: those installed, and installing or changing one, from one to four files,
 * each a style (see FontService). Users then choose among them in My Account.
 */
export class FontsSection implements m.ClassComponent {
  private fonts?: FontFamily[];
  private form?: Form;
  private uploading = 0;
  private saving = false;
  private error?: string;
  private loadError?: string;

  oninit() {
    ApiEndpoints.Fonts.call({})
      .then((output) => (this.fonts = output.fonts))
      .catch((err: Error) => (this.loadError = err.message));
  }

  view(): m.Children {
    return [
      m("h2", "Fonts"),
      m("p", "Fonts users can choose for the interface, their notes or fixed-width text, in My Account. "
        + "A font is one to four files: regular, bold, italic, bold italic. The browser makes up the styles missing, not as well."),
      m("p.muted", "WOFF2, WOFF, TTF or OTF files, 5 MB each at most. A variable font covers every weight in one file: "
        + "then one file for regular and one for italic are all it takes. Install only fonts whose licence allows it."),
      this.loadError ? m("p.errormessage", this.loadError) : null,
      this.form ? this.editor(this.form) : [
        this.list(),
        this.fonts ? m("button", { type: "button", onclick: () => this.edit() }, "Install a font") : null,
      ],
    ];
  }

  private list(): m.Children {
    if (!this.fonts) return this.loadError ? null : m("p.muted", "Loading…");
    if (this.fonts.length === 0) return m("p.muted", "No fonts installed.");
    return m("ul.fontlist", this.fonts.map((font) => {
      const family = previewFamily(font.files.map(face));
      return m("li", { key: font.id }, [
        m(".fonthead", [
          m("strong", font.name),
          font.monospace ? m("span.badge", "fixed-width") : null,
          m("span.spacer"),
          m("button.secondary", { type: "button", onclick: () => this.edit(font) }, "Change"),
          m("button.secondary", { type: "button", onclick: () => void this.remove(font) }, "Delete"),
        ]),
        m("p.fontsample", { style: { fontFamily: '"' + family + '", var(--font-ui-default)' } },
          [SAMPLE + ". ", m("b", "Bold"), ", ", m("i", "italic"), ", ", m("b", m("i", "both")), "."]),
        m("p.muted", font.files.map((f) => styleLabel(f.style)).join(", ")),
      ]);
    }));
  }

  private editor(form: Form): m.Children {
    const taken = new Map<FontStyle, number>();
    for (const row of form.rows) taken.set(row.style, (taken.get(row.style) ?? 0) + 1);
    const twice = [...taken.values()].some((n) => n > 1);
    return m("form.stackedform.fontform", { onsubmit: (e: Event) => { e.preventDefault(); void this.save(form); } }, [
      m("h3", form.id ? "Change " + form.name : "Install a font"),
      m("label.field", [
        m("span.fieldlabel", "Name"),
        m("input", { value: form.name, maxlength: 60, oninput: (e: Event) => (form.name = (e.target as HTMLInputElement).value) }),
      ]),
      m("label.checkfield", [
        m("input", { type: "checkbox", checked: form.monospace, onchange: (e: Event) => (form.monospace = (e.target as HTMLInputElement).checked) }),
        m("span", "Fixed-width: offered for fixed-width text too"),
      ]),
      m("ul.fontfiles", form.rows.map((row, index) => m("li", { key: row.file + index }, [
        m("select", {
          "aria-label": "Style",
          value: row.style,
          onchange: (e: Event) => (row.style = (e.target as HTMLSelectElement).value as FontStyle),
        }, FONT_STYLES.map((style) => m("option", { value: style }, styleLabel(style)))),
        m(".fontfileinfo", [
          m("p.fontsample", { style: {
            fontFamily: '"' + previewFamily([rowFace(row)]) + '", var(--font-ui-default)',
            fontWeight: row.style === FontStyle.BOLD || row.style === FontStyle.BOLD_ITALIC ? "700" : "400",
            fontStyle: row.style === FontStyle.ITALIC || row.style === FontStyle.BOLD_ITALIC ? "italic" : "normal",
          } }, SAMPLE),
          m("p.muted", (row.fileName ? row.fileName + ": " : "") + describe(row)),
        ]),
        m("button.secondary", { type: "button", onclick: () => form.rows.splice(index, 1) }, "Remove"),
      ]))),
      twice ? m("p.errormessage", "Two files have the same style: each style takes one file.") : null,
      m("input.hidden#fontfiles", {
        type: "file",
        multiple: true,
        accept: ".woff2,.woff,.ttf,.otf",
        onchange: (e: Event) => {
          const input = e.target as HTMLInputElement;
          void this.add(form, [...(input.files ?? [])]);
          input.value = "";
        },
      }),
      m(".buttons", [
        m("button.secondary", {
          type: "button",
          disabled: form.rows.length >= MAX_FILES || this.uploading > 0,
          onclick: () => document.getElementById("fontfiles")?.click(),
        }, this.uploading > 0 ? "Uploading…" : form.rows.length ? "Add files" : "Choose files"),
      ]),
      this.error ? m("p.errormessage", this.error) : null,
      m(".buttons", [
        m("button", { type: "submit", disabled: this.saving || this.uploading > 0 || twice || form.rows.length === 0 }, form.id ? "Save" : "Install"),
        m("button.secondary", { type: "button", onclick: () => { this.form = undefined; this.error = undefined; } }, "Cancel"),
      ]),
    ]);
  }

  private edit(font?: FontFamily) {
    this.error = undefined;
    this.form = font
      ? { id: font.id, name: font.name, monospace: font.monospace, rows: font.files.map((f) => ({ ...f })) }
      : { name: "", monospace: false, rows: [] };
  }

  /** Each file uploaded, then placed in the style its name or content says, else the first free one. */
  private async add(form: Form, files: File[]) {
    this.error = undefined;
    if (form.rows.length + files.length > MAX_FILES) {
      this.error = "A font has " + MAX_FILES + " files at most.";
      return;
    }
    this.uploading += files.length;
    for (const file of files) {
      try {
        const uploaded = await Api.upload<UploadedFont>("/fonts/", file);
        const said = styleFromFileName(file.name) ?? uploaded.suggestedStyle;
        const used = new Set(form.rows.map((r) => r.style));
        const style = !used.has(said) ? said : FONT_STYLES.find((s) => !used.has(s)) ?? said;
        form.rows.push({ style, file: uploaded.file, fileName: file.name, format: uploaded.format, size: uploaded.size,
          variable: uploaded.variable, weightMin: uploaded.weightMin, weightMax: uploaded.weightMax });
        if (!form.name.trim() && uploaded.familyName) form.name = uploaded.familyName;
        if (form.rows.length === 1 && !form.id && uploaded.monospace) form.monospace = true;
      } catch (err) {
        this.error = file.name + ": " + (err as Error).message;
      } finally {
        this.uploading--;
        m.redraw();
      }
    }
  }

  private async save(form: Form) {
    this.saving = true;
    this.error = undefined;
    try {
      const output = await ApiEndpoints.AdminSaveFont.call({
        id: form.id, name: form.name, monospace: form.monospace, files: form.rows.map((r) => ({ style: r.style, file: r.file })),
      });
      this.fonts = output.fonts;
      this.form = undefined;
    } catch (err) {
      this.error = (err as Error).message;
    } finally {
      this.saving = false;
    }
  }

  private async remove(font: FontFamily) {
    if (!confirm("Delete the font " + font.name + "? Devices that use it go back to the default font.")) return;
    try {
      this.fonts = (await ApiEndpoints.AdminDeleteFont.call({ id: font.id })).fonts;
    } catch (err) {
      this.loadError = (err as Error).message;
    }
  }
}
