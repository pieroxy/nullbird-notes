// Copied from Lucide v1.76.0 (https://lucide.dev), ISC license; plus, the chevrons, trash-2, check
// and x come from Feather, MIT license. Notices: src/licenses/lucide.txt, shipped as /licenses/lucide.txt.
import m from "mithril";

type Shape = [tag: string, attrs: Record<string, string | number>];

const ICONS = {
  plus: [["path", { d: "M5 12h14" }], ["path", { d: "M12 5v14" }]],
  ellipsis: [["circle", { cx: 12, cy: 12, r: 1 }], ["circle", { cx: 19, cy: 12, r: 1 }], ["circle", { cx: 5, cy: 12, r: 1 }]],
  "trash-2": [
    ["path", { d: "M10 11v6" }],
    ["path", { d: "M14 11v6" }],
    ["path", { d: "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" }],
    ["path", { d: "M3 6h18" }],
    ["path", { d: "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" }],
  ],
  "chevron-left": [["path", { d: "m15 18-6-6 6-6" }]],
  "chevron-right": [["path", { d: "m9 18 6-6-6-6" }]],
  "chevron-up": [["path", { d: "m18 15-6-6-6 6" }]],
  "chevron-down": [["path", { d: "m6 9 6 6 6-6" }]],
  check: [["path", { d: "M20 6 9 17l-5-5" }]],
  "grip-vertical": [
    ["circle", { cx: 9, cy: 12, r: 1 }], ["circle", { cx: 9, cy: 5, r: 1 }], ["circle", { cx: 9, cy: 19, r: 1 }],
    ["circle", { cx: 15, cy: 12, r: 1 }], ["circle", { cx: 15, cy: 5, r: 1 }], ["circle", { cx: 15, cy: 19, r: 1 }],
  ],
  pin: [
    ["path", { d: "M12 17v5" }],
    ["path", { d: "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z" }],
  ],
  refresh: [
    ["path", { d: "M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8" }],
    ["path", { d: "M21 3v5h-5" }],
  ],
  copy: [
    ["rect", { width: 14, height: 14, x: 8, y: 8, rx: 2, ry: 2 }],
    ["path", { d: "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2" }],
  ],
  x: [["path", { d: "M18 6 6 18" }], ["path", { d: "m6 6 12 12" }]],
  globe: [
    ["circle", { cx: 12, cy: 12, r: 10 }],
    ["path", { d: "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20" }],
    ["path", { d: "M2 12h20" }],
  ],
  eye: [
    ["path", { d: "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0" }],
    ["circle", { cx: 12, cy: 12, r: 3 }],
  ],
  "eye-off": [
    ["path", { d: "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49" }],
    ["path", { d: "M14.084 14.158a3 3 0 0 1-4.242-4.242" }],
    ["path", { d: "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143" }],
    ["path", { d: "m2 2 20 20" }],
  ],
  pencil: [
    ["path", { d: "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z" }],
    ["path", { d: "m15 5 4 4" }],
  ],
  settings: [
    ["path", { d: "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915" }],
    ["circle", { cx: 12, cy: 12, r: 3 }],
  ],
  smile: [
    ["circle", { cx: 12, cy: 12, r: 10 }],
    ["path", { d: "M8 14s1.5 2 4 2 4-2 4-2" }],
    ["line", { x1: 9, x2: 9.01, y1: 9, y2: 9 }],
    ["line", { x1: 15, x2: 15.01, y1: 9, y2: 9 }],
  ],
  "panel-left-close": [["rect", { width: 18, height: 18, x: 3, y: 3, rx: 2 }], ["path", { d: "M9 3v18" }], ["path", { d: "m16 15-3-3 3-3" }]],
  "panel-left-open": [["rect", { width: 18, height: 18, x: 3, y: 3, rx: 2 }], ["path", { d: "M9 3v18" }], ["path", { d: "m14 9 3 3-3 3" }]],
  type: [["path", { d: "M12 4v16" }], ["path", { d: "M4 7V5a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v2" }], ["path", { d: "M9 20h6" }]],
  bold: [["path", { d: "M6 12h9a4 4 0 0 1 0 8H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h7a4 4 0 0 1 0 8" }]],
  italic: [["line", { x1: 19, x2: 10, y1: 4, y2: 4 }], ["line", { x1: 14, x2: 5, y1: 20, y2: 20 }], ["line", { x1: 15, x2: 9, y1: 4, y2: 20 }]],
  list: [
    ["path", { d: "M3 5h.01" }], ["path", { d: "M3 12h.01" }], ["path", { d: "M3 19h.01" }],
    ["path", { d: "M8 5h13" }], ["path", { d: "M8 12h13" }], ["path", { d: "M8 19h13" }],
  ],
  "list-ordered": [
    ["path", { d: "M11 5h10" }], ["path", { d: "M11 12h10" }], ["path", { d: "M11 19h10" }],
    ["path", { d: "M4 4h1v5" }], ["path", { d: "M4 9h2" }], ["path", { d: "M6.5 20H3.4c0-1 2.6-1.925 2.6-3.5a1.5 1.5 0 0 0-2.6-1.02" }],
  ],
  "list-todo": [
    ["path", { d: "M13 5h8" }], ["path", { d: "M13 12h8" }], ["path", { d: "M13 19h8" }],
    ["path", { d: "m3 17 2 2 4-4" }], ["rect", { x: 3, y: 4, width: 6, height: 6, rx: 1 }],
  ],
  "indent-decrease": [["path", { d: "M21 5H11" }], ["path", { d: "M21 12H11" }], ["path", { d: "M21 19H11" }], ["path", { d: "m7 8-4 4 4 4" }]],
  "indent-increase": [["path", { d: "M21 5H11" }], ["path", { d: "M21 12H11" }], ["path", { d: "M21 19H11" }], ["path", { d: "m3 8 4 4-4 4" }]],
} satisfies Record<string, Shape[]>;

export type IconName = keyof typeof ICONS;

export function icon(name: IconName): m.Vnode {
  return m("svg.icon", {
    xmlns: "http://www.w3.org/2000/svg", viewBox: "0 0 24 24", fill: "none", stroke: "currentColor",
    "stroke-width": 2, "stroke-linecap": "round", "stroke-linejoin": "round", "aria-hidden": "true",
  }, (ICONS[name] as Shape[]).map(([tag, attrs]) => m(tag, { ...attrs })));
}

/** A button showing only an icon; {@code label} is its tooltip and accessible name. */
export function iconButton(name: IconName, label: string, onclick: () => void, cls = ""): m.Vnode {
  return m("button.icon" + (cls ? "." + cls : ""), { type: "button", title: label, "aria-label": label, onclick }, icon(name));
}
