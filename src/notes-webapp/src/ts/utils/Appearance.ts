/**
 * The appearance chosen in My Account: text size and spacing. Each choice sets CSS variables that
 * style.scss multiplies its sizes by (--text-scale, --space-scale, --bar-height). Kept per device,
 * never synced: a phone and a desktop want their own.
 */
export interface Choice {
  id: string;
  label: string;
  /** Set on the root element; the default choice's are the stylesheet's own, and set nothing. */
  variables: Record<string, string>;
}

export interface Setting {
  storageKey: string;
  choices: Choice[];
  defaultId: string;
}

/** The body's text size at each, in CSS pixels: the stylesheet's sizes are for 15. */
export const TEXT_PIXELS: Record<string, number> = { small: 13, normal: 15, large: 17, larger: 19 };

export const TEXT_SIZE: Setting = {
  storageKey: "notes.textsize",
  defaultId: "normal",
  choices: [["small", "Small"], ["normal", "Normal"], ["large", "Large"], ["larger", "Larger"]].map(([id, label]) =>
    ({ id, label, variables: { "--text-scale": String(TEXT_PIXELS[id] / TEXT_PIXELS.normal) } })),
};

/** Rows' vertical padding (lists, tags, menus) and the top bars' height. */
export const SPACING: Setting = {
  storageKey: "notes.spacing",
  defaultId: "normal",
  choices: [
    { id: "compact", label: "Compact", variables: { "--space-scale": "0.5", "--bar-height": "40px" } },
    { id: "normal", label: "Normal", variables: { "--space-scale": "1", "--bar-height": "48px" } },
    { id: "comfortable", label: "Comfortable", variables: { "--space-scale": "1.5", "--bar-height": "56px" } },
  ],
};

export const SETTINGS = [TEXT_SIZE, SPACING];

export function choice(setting: Setting, id: string): Choice {
  return setting.choices.find((c) => c.id === id) ?? choice(setting, setting.defaultId);
}

/** Storage can be unavailable (private browsing, blocked site data): then the default. */
export function loadChoice(setting: Setting): string {
  try {
    return choice(setting, localStorage.getItem(setting.storageKey) ?? setting.defaultId).id;
  } catch {
    return setting.defaultId;
  }
}

/** Applies it at once; the default isn't stored. */
export function saveChoice(setting: Setting, id: string) {
  const chosen = choice(setting, id).id;
  try {
    if (chosen === setting.defaultId) localStorage.removeItem(setting.storageKey);
    else localStorage.setItem(setting.storageKey, chosen);
  } catch {
    // See loadChoice: it lasts the page.
  }
  applyChoice(setting, chosen);
}

/** Through the CSSOM: the CSP refuses inline styles, not this. */
export function applyChoice(setting: Setting, id: string) {
  const style = document.documentElement.style;
  const isDefault = choice(setting, id).id === setting.defaultId;
  for (const [name, value] of Object.entries(choice(setting, id).variables)) {
    if (isDefault) style.removeProperty(name);
    else style.setProperty(name, value);
  }
}

/** At startup; and when another tab of this app changes one, this one follows. */
export function initAppearance(onChange: () => void) {
  for (const setting of SETTINGS) applyChoice(setting, loadChoice(setting));
  window.addEventListener("storage", (event) => {
    const changed = SETTINGS.filter((setting) => event.key === null || event.key === setting.storageKey);
    if (!changed.length) return;
    for (const setting of changed) applyChoice(setting, loadChoice(setting));
    onChange();
  });
}
