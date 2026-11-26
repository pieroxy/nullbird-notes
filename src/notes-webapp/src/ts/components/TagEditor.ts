import m from "mithril";
import { TAG_RULE } from "../sync/Metadata";
import { leafOf, parentOf, SEPARATOR } from "../utils/TagTree";
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
}

/**
 * The note's tags as chips, then an input: whitespace or Enter adds what's typed (tags can't hold
 * whitespace), Backspace in the empty input removes the last tag.
 */
export class TagEditor implements m.ClassComponent<TagEditorAttrs> {
  private draft = "";
  private invalid = false;

  view({ attrs }: m.Vnode<TagEditorAttrs>): m.Children {
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
        title: this.invalid ? TAG_RULE : undefined,
        autocapitalize: "none",
        spellcheck: false,
        oninput: (e: Event) => {
          const parts = (e.target as HTMLInputElement).value.split(/[\s\p{Zs}]+/u);
          this.draft = parts.pop()!;
          this.invalid = false;
          for (const part of parts) this.add(part, attrs);
        },
        onkeydown: (e: KeyboardEvent) => {
          if (e.key === "Enter") {
            e.preventDefault();
            this.add(this.draft, attrs);
          } else if (e.key === "Backspace" && this.draft === "" && attrs.tags.length > 0) {
            attrs.onRemove(attrs.tags[attrs.tags.length - 1]);
          }
        },
        onblur: () => this.add(this.draft, attrs),
      }),
    ]);
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
