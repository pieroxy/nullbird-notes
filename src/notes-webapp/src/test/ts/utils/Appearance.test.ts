import { applyChoice, choice, loadChoice, saveChoice, SETTINGS, SPACING, TEXT_SIZE } from "../../../ts/utils/Appearance";

/** Node has neither: just what Appearance uses of them. */
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

const scale = () => Number(properties.get("--text-scale"));

describe("Appearance", () => {
  test("four text sizes, three spacings, Normal by default", () => {
    expect(TEXT_SIZE.choices.map((c) => c.id)).toEqual(["small", "normal", "large", "larger"]);
    expect(SPACING.choices.map((c) => c.id)).toEqual(["compact", "normal", "comfortable"]);
    for (const setting of SETTINGS) expect(loadChoice(setting)).toBe(setting.defaultId);
    expect([TEXT_SIZE, SPACING].map((s) => s.defaultId)).toEqual(["normal", "normal"]);
  });

  test("the defaults are the stylesheet's own", () => {
    expect(choice(TEXT_SIZE, "normal").variables).toEqual({ "--text-scale": "1" });
    expect(choice(SPACING, "normal").variables).toEqual({ "--space-scale": "1", "--bar-height": "48px" });
  });

  test("an unknown choice is the default", () => {
    stored.set("notes.textsize", "huge");
    stored.set("notes.spacing", "airy");
    expect(loadChoice(TEXT_SIZE)).toBe("normal");
    expect(loadChoice(SPACING)).toBe("normal");
  });

  test("kept and applied at once", () => {
    saveChoice(TEXT_SIZE, "larger");
    expect(loadChoice(TEXT_SIZE)).toBe("larger");
    expect(scale() * 15).toBeCloseTo(19, 10);
    saveChoice(SPACING, "compact");
    expect(loadChoice(SPACING)).toBe("compact");
    expect(properties.get("--space-scale")).toBe("0.5");
    expect(properties.get("--bar-height")).toBe("40px");
    expect(scale() * 15).toBeCloseTo(19, 10);
  });

  test("back to Normal: nothing stored, nothing set", () => {
    saveChoice(TEXT_SIZE, "large");
    saveChoice(SPACING, "comfortable");
    saveChoice(TEXT_SIZE, "normal");
    saveChoice(SPACING, "normal");
    expect(stored.size).toBe(0);
    expect(properties.size).toBe(0);
  });

  test("without storage, it still applies, for the page", () => {
    storageFails = true;
    saveChoice(TEXT_SIZE, "small");
    expect(scale() * 15).toBeCloseTo(13, 10);
    expect(loadChoice(TEXT_SIZE)).toBe("normal");
    applyChoice(TEXT_SIZE, "normal");
    expect(properties.size).toBe(0);
  });
});
