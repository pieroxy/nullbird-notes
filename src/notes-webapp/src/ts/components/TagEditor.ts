import m from "mithril";
import { TAG_RULE } from "../sync/Metadata";
import { leafOf, parentOf, SEPARATOR, Suggestion, suggestTags } from "../utils/TagTree";
import { TagOptions } from "../auto/api-types";
import { tagClasses, tagDot } from "../utils/TagLook";
import { iconButton } from "./Icons";

export interface TagEditorAttrs {
  tags: string[];
  /** @return false if the tag is invalid: it stays in the input. */
  onAdd: (tag: string) => boolean;
  onRemove: (tag: string) => void;
  /** How each tag looks (see TagLook). */
  tagOptions: { [tag: string]: TagOptions };
  /** The tags to suggest from, in the panel's order. */
  knownTags: string[];
}

/**
 * The note's tags as chips, then an input: whitespace or Enter adds what's typed (tags can't hold
 * whitespace), Backspace in the empty input removes the last tag.
 *
 * While a tag is typed, the tags it could be are listed above the input (see TagTree.suggestTags),
 * the best one next to it. None is picked until an arrow key goes into the list: until then, Enter
 * adds what's typed, as it would without the list. Enter or a click adds the picked one, Tab puts it
 * (or the best one) in the input, to type on: a tag under it. Escape closes the list.
 */
export class TagEditor implements m.ClassComponent<TagEditorAttrs> {
  private draft = "";
  private invalid = false;
  private focused = false;
  /** Escape closed the list: closed until something's typed. */
  private dismissed = false;
  /** The picked suggestion; -1: none. */
  private active = -1;

  view({ attrs }: m.Vnode<TagEditorAttrs>): m.Children {
    const suggestions = this.focused && !this.dismissed ? suggestTags(attrs.knownTags, this.draft, attrs.tags) : [];
    if (this.active >= suggestions.length) this.active = -1;
    const open = suggestions.length > 0;
    return m(".tags", [
      attrs.tags.map((tag) => m("span.tag" + tagClasses(attrs.tagOptions[tag]), { key: tag }, [
        // A nested tag (see TagTree): the tags above it dimmed, "work/clients/" before "acme".
        m("span.tagname", [tagDot(attrs.tagOptions[tag]), parentOf(tag) ? m("span.tagpath", parentOf(tag) + SEPARATOR) : null, leafOf(tag)]),
        iconButton("x", "Remove tag " + tag, () => attrs.onRemove(tag), "tagremove"),
      ])),
      m("input.taginput" + (this.invalid ? ".invalid" : ""), {
        value: this.draft,
        placeholder: attrs.tags.length === 0 ? "Add a tag…" : "",
        "aria-label": "Add a tag",
        role: "combobox",
        "aria-autocomplete": "list",
        "aria-expanded": String(open),
        "aria-controls": "tagsuggest",
        "aria-activedescendant": open && this.active >= 0 ? "tagsuggest-" + this.active : undefined,
        title: this.invalid ? TAG_RULE : undefined,
        autocomplete: "off",
        autocapitalize: "none",
        spellcheck: false,
        oninput: (e: Event) => {
          const parts = (e.target as HTMLInputElement).value.split(/[\s\p{Zs}]+/u);
          this.draft = parts.pop()!;
          this.invalid = false;
          this.dismissed = false;
          this.active = -1;
          for (const part of parts) this.add(part, attrs);
        },
        onkeydown: (e: KeyboardEvent) => this.onKey(e, suggestions, attrs),
        onfocus: () => (this.focused = true),
        onblur: () => {
          this.focused = false;
          this.active = -1;
          this.add(this.draft, attrs);
        },
      }),
      open ? m("ul.tagsuggest#tagsuggest", {
        role: "listbox",
        "aria-label": "Tags",
        onupdate: ({ dom }: m.VnodeDOM) => fit(dom as HTMLElement),
        oncreate: ({ dom }: m.VnodeDOM) => fit(dom as HTMLElement),
      }, suggestions.map((s, i) => m("li" + (i === this.active ? ".active" : ""), {
        key: s.tag,
        id: "tagsuggest-" + i,
        role: "option",
        "aria-selected": String(i === this.active),
        // The focus stays in the input: leaving it would add what's typed.
        onmousedown: (e: MouseEvent) => e.preventDefault(),
        onclick: () => this.pick(s.tag, attrs),
      }, [tagDot(attrs.tagOptions[s.tag]), m("span.tagname", label(s))]))) : null,
    ]);
  }

  private onKey(e: KeyboardEvent, suggestions: Suggestion[], attrs: TagEditorAttrs) {
    const open = suggestions.length > 0;
    // The list goes up from the input: Up goes away from it, to worse matches, Down back to it.
    if (open && e.key === "ArrowUp") {
      e.preventDefault();
      this.active = Math.min(this.active + 1, suggestions.length - 1);
    } else if (open && e.key === "ArrowDown") {
      e.preventDefault();
      this.active = this.active < 0 ? 0 : this.active - 1;
    } else if (open && e.key === "Tab" && !e.shiftKey && !e.altKey && !e.ctrlKey && !e.metaKey) {
      e.preventDefault();
      this.draft = suggestions[Math.max(this.active, 0)].tag;
      this.invalid = false;
      this.active = -1;
    } else if (open && e.key === "Escape") {
      // Only the list closes, not what's around (a dialog, the editor).
      e.preventDefault();
      e.stopPropagation();
      this.dismissed = true;
      this.active = -1;
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (open && this.active >= 0) this.pick(suggestions[this.active].tag, attrs);
      else this.add(this.draft, attrs);
    } else if (e.key === "Backspace" && this.draft === "" && attrs.tags.length > 0) {
      attrs.onRemove(attrs.tags[attrs.tags.length - 1]);
    }
  }

  /** A suggestion: added in place of what's typed. */
  private pick(tag: string, attrs: TagEditorAttrs) {
    this.active = -1;
    if (attrs.onAdd(tag)) {
      this.draft = "";
      this.invalid = false;
    }
  }

  /** An invalid tag goes back to the input, flagged. */
  private add(tag: string, attrs: TagEditorAttrs) {
    if (tag === "") return;
    if (attrs.onAdd(tag)) {
      if (tag === this.draft) this.draft = "";
    } else {
      this.draft = tag;
      this.invalid = true;
    }
  }
}

/** Its name, the tags above it dimmed, what's typed in bold. */
function label(s: Suggestion): m.Children {
  const pathEnd = s.tag.lastIndexOf(SEPARATOR) + 1;
  const cuts = [...new Set([0, pathEnd, s.at, s.at + s.length, s.tag.length])].sort((a, b) => a - b);
  const pieces: m.Children[] = [];
  for (let i = 0; i + 1 < cuts.length; i++) {
    const [from, to] = [cuts[i], cuts[i + 1]];
    if (from === to) continue;
    const text = s.tag.slice(from, to);
    const bold = from >= s.at && to <= s.at + s.length;
    const piece = bold ? m("b", text) : text;
    pieces.push(to <= pathEnd ? m("span.tagpath", piece) : piece);
  }
  return pieces;
}

/** No higher than the editor above it: it scrolls past that, its best (bottom) one and the picked one in sight. */
function fit(list: HTMLElement) {
  const editor = list.closest(".editor");
  const bar = list.parentElement;
  if (editor && bar) list.style.maxHeight = Math.max(80, bar.getBoundingClientRect().top - editor.getBoundingClientRect().top - 8) + "px";
  list.querySelector("li.active")?.scrollIntoView({ block: "nearest" });
}
