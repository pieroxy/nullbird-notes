import { applyTextSize, DEFAULT_TEXT_SIZE, loadTextSize, saveTextSize, textSize, TEXT_SIZES } from "../../../ts/utils/TextSize";

/** Node has neither: just what TextSize uses of them. */
const stored = new Map<string, string>();
const properties = new Map<string, string>();
let storageFails = false;

beforeEach(() => {
  stored.clear();
  properties.clear();
  storageFails = false;
  const check = () => {
    if (storageFails) throw new Error("SecurityError");
  };
  Object.assign(globalThis, {
    localStorage: {
      getItem: (key: string) => (check(), stored.get(key) ?? null),
      setItem: (key: string, value: string) => (check(), stored.set(key, value)),
      removeItem: (key: string) => (check(), stored.delete(key)),
    },
    document: {
      documentElement: {
        style: {
          setProperty: (name: string, value: string) => properties.set(name, value),
          removeProperty: (name: string) => properties.delete(name),
        },
      },
    },
  });
});

describe("TextSize", () => {
  test("four sizes, from the smallest, Normal being the stylesheet's own", () => {
    expect(TEXT_SIZES.map((size) => size.id)).toEqual(["small", "normal", "large", "larger"]);
    expect(textSize(DEFAULT_TEXT_SIZE).pixels).toBe(15);
  });

  test("an unknown or missing size is Normal", () => {
    expect(loadTextSize()).toBe("normal");
    stored.set("notes.textsize", "huge");
    expect(loadTextSize()).toBe("normal");
  });

  test("kept and applied at once, as a scale of the stylesheet's sizes", () => {
    saveTextSize("larger");
    expect(loadTextSize()).toBe("larger");
    expect(Number(properties.get("--text-scale")) * 15).toBeCloseTo(19, 10);
    saveTextSize("small");
    expect(Number(properties.get("--text-scale")) * 15).toBeCloseTo(13, 10);
  });

  test("back to Normal: nothing stored, nothing set", () => {
    saveTextSize("large");
    saveTextSize("normal");
    expect(stored.size).toBe(0);
    expect(properties.size).toBe(0);
  });

  test("without storage, it still applies, for the page", () => {
    storageFails = true;
    saveTextSize("large");
    expect(Number(properties.get("--text-scale")) * 15).toBeCloseTo(17, 10);
    expect(loadTextSize()).toBe("normal");
    applyTextSize("normal");
    expect(properties.size).toBe(0);
  });
});
