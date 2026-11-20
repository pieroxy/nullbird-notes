// Copied from Lucide v1.76.0 (https://lucide.dev), ISC license; plus, chevron-left, trash-2, check
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
  check: [["path", { d: "M20 6 9 17l-5-5" }]],
  x: [["path", { d: "M18 6 6 18" }], ["path", { d: "m6 6 12 12" }]],
  "panel-left": [["rect", { width: 18, height: 18, x: 3, y: 3, rx: 2 }], ["path", { d: "M9 3v18" }]],
  list: [
    ["path", { d: "M3 12h.01" }],
    ["path", { d: "M3 18h.01" }],
    ["path", { d: "M3 6h.01" }],
    ["path", { d: "M8 12h13" }],
    ["path", { d: "M8 18h13" }],
    ["path", { d: "M8 6h13" }],
  ],
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
