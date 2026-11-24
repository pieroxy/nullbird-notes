import { FontFamily, FontStyle } from "../../../ts/auto/api-types";
import { ApiEndpoints } from "../../../ts/auto/ApiEndpoints";
import { applyFonts, chooseFont, choiceOf, cssValue, face, familyName, loadFont, refreshFonts, styleFromFileName } from "../../../ts/utils/Fonts";

jest.mock("../../../ts/auto/ApiEndpoints", () => ({ ApiEndpoints: { Fonts: { call: jest.fn() } } }));
const fontsCall = ApiEndpoints.Fonts.call as jest.Mock;

/** Node has none of these: just what Fonts uses of them. */
const stored = new Map<string, string>();
const properties = new Map<string, string>();
const documentFonts = new Set<{ family: string; source: string }>();
const cached = new Set<string>();
let offline = false;

class FakeFontFace {
  constructor(readonly family: string, readonly source: string, readonly descriptors: object) {}
}

beforeEach(() => {
  stored.clear();
  properties.clear();
  documentFonts.clear();
  cached.clear();
  offline = false;
  fontsCall.mockReset();
  const cache = {
    keys: async () => [...cached].map((path) => ({ url: "http://notes.test" + path })),
    delete: async (request: { url: string }) => cached.delete(new URL(request.url).pathname),
    match: async (url: string) => (cached.has(url) ? {} : undefined),
    add: async (url: string) => {
      if (offline) throw new TypeError("Failed to fetch");
      cached.add(url);
    },
  };
  Object.assign(globalThis, {
    localStorage: {
      getItem: (key: string) => stored.get(key) ?? null,
      setItem: (key: string, value: string) => stored.set(key, value),
      removeItem: (key: string) => stored.delete(key),
    },
    document: {
      documentElement: { style: { setProperty: (n: string, v: string) => properties.set(n, v), removeProperty: (n: string) => properties.delete(n) } },
      fonts: { add: (f: FakeFontFace) => documentFonts.add(f), delete: (f: FakeFontFace) => documentFonts.delete(f) },
    },
    FontFace: FakeFontFace,
    caches: { open: async () => cache },
  });
  applyFonts(); // Forgets the faces of the test before.
});

const SANS: FontFamily = {
  id: "0123456789abcdef", name: "Sans", monospace: false, files: [
    { style: FontStyle.REGULAR, file: "a".repeat(64) + ".ttf", format: "truetype", size: 100, variable: true, weightMin: 100, weightMax: 900 },
    { style: FontStyle.ITALIC, file: "b".repeat(64) + ".woff2", format: "woff2", size: 100, variable: true },
    { style: FontStyle.BOLD, file: "c".repeat(64) + ".otf", format: "opentype", size: 100, variable: false },
  ],
};

describe("Fonts", () => {
  test("each file's descriptors: its style, its weights when variable", () => {
    expect(SANS.files.map(face)).toEqual([
      { url: "/fonts/" + "a".repeat(64) + ".ttf", weight: "100 900", style: "normal" },
      { url: "/fonts/" + "b".repeat(64) + ".woff2", weight: "1 1000", style: "italic" },
      { url: "/fonts/" + "c".repeat(64) + ".otf", weight: "700", style: "normal" },
    ]);
  });

  test("the variable: the font by its ID, then the defaults", () => {
    expect(cssValue("ui", choiceOf(SANS))).toBe('"nbfont-0123456789abcdef", var(--font-ui-default)');
    expect(cssValue("note", choiceOf(SANS))).toBe('"nbfont-0123456789abcdef", var(--font-ui)');
    expect(cssValue("mono", undefined)).toBeUndefined();
  });

  test("a file's name says its style, as fonts are usually named", () => {
    expect(styleFromFileName("Inter-BoldItalic.woff2")).toBe(FontStyle.BOLD_ITALIC);
    expect(styleFromFileName("Inter-Italic.woff2")).toBe(FontStyle.ITALIC);
    expect(styleFromFileName("SourceSans3-Black.otf")).toBe(FontStyle.BOLD);
    expect(styleFromFileName("Inter-Regular.ttf")).toBe(FontStyle.REGULAR);
    expect(styleFromFileName("UbuntuMono-BI.ttf")).toBe(FontStyle.BOLD_ITALIC);
    expect(styleFromFileName("UbuntuMono-RI.ttf")).toBe(FontStyle.ITALIC);
    expect(styleFromFileName("UbuntuMono-B.ttf")).toBe(FontStyle.BOLD);
    expect(styleFromFileName("UbuntuSans[wdth,wght].ttf")).toBeUndefined();
  });

  test("chosen: its files downloaded, its faces registered, the variable set", async () => {
    await chooseFont("ui", SANS);
    expect(loadFont("ui")?.id).toBe(SANS.id);
    expect(cached.size).toBe(3);
    expect([...documentFonts].map((f) => f.family)).toEqual(Array(3).fill(familyName(SANS.id)));
    expect(properties.get("--font-ui")).toContain("nbfont-");
  });

  test("the same font in two places: registered and kept once", async () => {
    await chooseFont("ui", SANS);
    await chooseFont("note", SANS);
    expect(documentFonts.size).toBe(3);
    expect(cached.size).toBe(3);
    await chooseFont("ui", undefined);
    expect(cached.size).toBe(3);
    expect(properties.has("--font-ui")).toBe(false);
    expect(properties.has("--font-note")).toBe(true);
  });

  test("back to the default: its files and faces gone", async () => {
    await chooseFont("mono", SANS);
    await chooseFont("mono", undefined);
    expect(cached.size).toBe(0);
    expect(documentFonts.size).toBe(0);
    expect(stored.size).toBe(0);
  });

  test("offline, a font can't be chosen: the choice stays as it was", async () => {
    offline = true;
    await expect(chooseFont("ui", SANS)).rejects.toThrow("couldn't be downloaded");
    expect(loadFont("ui")).toBeUndefined();
    expect(properties.has("--font-ui")).toBe(false);
  });

  test("a font the admin deleted goes back to the default; one they changed takes its new files", async () => {
    await chooseFont("ui", SANS);
    await chooseFont("mono", { ...SANS, id: "fedcba9876543210", name: "Gone" });
    const changed: FontFamily = { ...SANS, files: [{ ...SANS.files[0], file: "d".repeat(64) + ".ttf" }] };
    fontsCall.mockResolvedValue({ fonts: [changed] });
    expect(await refreshFonts()).toEqual([changed]);
    expect(loadFont("mono")).toBeUndefined();
    expect(loadFont("ui")?.faces.map((f) => f.url)).toEqual(["/fonts/" + "d".repeat(64) + ".ttf"]);
    expect([...cached]).toEqual(["/fonts/" + "d".repeat(64) + ".ttf"]);
    expect(documentFonts.size).toBe(1);
  });

  test("offline, refreshing changes nothing", async () => {
    await chooseFont("ui", SANS);
    fontsCall.mockRejectedValue(new Error("Cannot reach the server."));
    expect(await refreshFonts()).toBeUndefined();
    expect(loadFont("ui")?.id).toBe(SANS.id);
  });

  test("a garbled choice is none", () => {
    stored.set("notes.font.ui", JSON.stringify({ id: "x", name: "X", faces: [{ url: "https://elsewhere/font.ttf", weight: "400", style: "normal" }] }));
    expect(loadFont("ui")).toBeUndefined();
    stored.set("notes.font.ui", "{not json");
    expect(loadFont("ui")).toBeUndefined();
  });
});
