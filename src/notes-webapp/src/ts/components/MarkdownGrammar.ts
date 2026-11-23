import { Autolink, parser, Strikethrough, TaskList } from "@lezer/markdown";

/**
 * The Markdown notes are written in: CommonMark with GFM's strikethrough, task lists and bare URLs,
 * minus what plain-text notes would trip on: a line underlined with "---" or "===" (a separator
 * under a line would make it a heading), a block indented by 4 spaces (indented lines would become
 * code), and HTML. The editor colors it (MarkdownStyle); the view renders it, tables added
 * (MarkdownTree). Here, without CodeMirror: the published page has no editor.
 */
export const markdownGrammar = parser.configure([
  Strikethrough,
  TaskList,
  Autolink,
  { remove: ["SetextHeading", "IndentedCode", "HTMLBlock", "HTMLTag"] },
]);
