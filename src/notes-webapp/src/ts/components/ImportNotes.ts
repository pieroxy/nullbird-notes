import m from "mithril";
import { ApiEndpoints } from "../auto/ApiEndpoints";
import { ImportSummary } from "../auto/api-types";
import { Api } from "../utils/api/Api";
import { importLines, size } from "../utils/ImportText";

type Step =
  | { kind: "idle" }
  | { kind: "checking"; fileName: string }
  | { kind: "preview"; summary: ImportSummary }
  | { kind: "running"; summary: ImportSummary }
  | { kind: "done"; summary: ImportSummary };

/**
 * Imports an export, this app's or Simplenote's: the file is uploaded and checked first, and
 * nothing happens before the user has seen what would and runs it (see ImportService). The
 * imported notes then come to this device, and the others, through the sync.
 */
export class ImportNotes implements m.ClassComponent {
  private step: Step = { kind: "idle" };
  private error?: string;
  private input?: HTMLInputElement;

  onremove() {
    // Leaving the page with a file checked: no need to keep it on the server for 15 minutes.
    if (this.step.kind === "preview" && this.step.summary.token) {
      void ApiEndpoints.ImportCancel.call({ token: this.step.summary.token }).catch(() => undefined);
    }
  }

  view(): m.Children {
    const step = this.step;
    return m("div.importnotes", [
      step.kind === "idle" || step.kind === "checking" ? this.picker(step.kind === "checking") : this.summary(step),
      step.kind === "checking" ? m("p.muted", { role: "status" }, "Checking " + step.fileName + "…") : null,
      this.error ? m("p.errormessage", { role: "alert" }, this.error) : null,
    ]);
  }

  /** A button, not the browser's file input: that one can't be styled, and a label wrapping it can't be focused. */
  private picker(busy: boolean): m.Children {
    return [
      m("button", { type: "button", disabled: busy, onclick: () => this.input?.click() }, "Choose a file to import…"),
      m("input.hidden", {
        type: "file",
        oncreate: (vnode: m.VnodeDOM) => (this.input = vnode.dom as HTMLInputElement),
        accept: ".zip,.json,application/zip,application/json",
        disabled: busy,
        onchange: (e: Event) => {
          const input = e.target as HTMLInputElement;
          const file = input.files?.[0];
          // Choosing the same file again must fire again.
          input.value = "";
          if (file) void this.check(file);
        },
      }),
    ];
  }

  private summary(step: Extract<Step, { summary: ImportSummary }>): m.Children {
    const summary = step.summary;
    const done = step.kind === "done";
    const lines = importLines(summary, done);
    const nothing = summary.imported === 0;
    return [
      m("p", [m("strong", summary.fileName), ": " + summary.notesInFile.toLocaleString() + (summary.notesInFile === 1 ? " note." : " notes.")]),
      m("ul.importsummary", { class: done ? "done" : "" }, lines.map((line) => m("li", line))),
      summary.tooLarge.length > 0
        ? m("ul.toolarge", summary.tooLarge.map((note) => m("li", [m("span.title", note.title || "(empty first line)"), " (" + size(note.size) + ")"])))
        : null,
      done
        ? [
            nothing ? null : m("p.successmessage", { role: "status" }, "Done. The notes reach your devices with the next sync."),
            m("div.buttons", m("button.secondary", { type: "button", onclick: () => this.reset() }, "Close")),
          ]
        : m("div.buttons", [
            nothing ? null : m("button", { type: "button", disabled: step.kind === "running", onclick: () => void this.run(summary) },
              step.kind === "running" ? "Importing…" : "Run the import"),
            m("button.secondary", { type: "button", disabled: step.kind === "running", onclick: () => this.cancel(summary) },
              nothing ? "Close" : "Cancel"),
          ]),
    ];
  }

  private async check(file: File) {
    this.error = undefined;
    this.step = { kind: "checking", fileName: file.name };
    m.redraw();
    try {
      const summary = await Api.upload<ImportSummary>("/import?name=" + encodeURIComponent(file.name), file);
      this.step = { kind: "preview", summary };
    } catch (e) {
      this.error = (e as Error).message;
      this.step = { kind: "idle" };
    }
    m.redraw();
  }

  private async run(summary: ImportSummary) {
    if (!summary.token) return;
    this.error = undefined;
    this.step = { kind: "running", summary };
    try {
      this.step = { kind: "done", summary: await ApiEndpoints.ImportRun.call({ token: summary.token }) };
    } catch (e) {
      this.error = (e as Error).message;
      this.step = { kind: "idle" };
    }
    m.redraw();
  }

  private cancel(summary: ImportSummary) {
    if (summary.token) void ApiEndpoints.ImportCancel.call({ token: summary.token }).catch(() => undefined);
    this.reset();
  }

  private reset() {
    this.error = undefined;
    this.step = { kind: "idle" };
  }
}
