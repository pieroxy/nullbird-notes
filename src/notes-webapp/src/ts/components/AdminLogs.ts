import m from "mithril";
import { ApiEndpoints } from "../auto/ApiEndpoints";
import { AdminLogEntry, AdminLogFile } from "../auto/api-types";
import { iconButton } from "./Icons";

/** What the filter chips stand for, in java.util.logging's level names. */
const SEVERITIES = [
  { id: "error", label: "Errors", levels: ["SEVERE"] },
  { id: "warning", label: "Warnings", levels: ["WARNING"] },
  { id: "info", label: "Info", levels: ["INFO"] },
  { id: "debug", label: "Debug", levels: ["CONFIG", "FINE", "FINER", "FINEST"] },
];
const SEARCH_DELAY_MS = 300;

function severityOf(level: string): string {
  return SEVERITIES.find((severity) => severity.levels.includes(level))?.id ?? "other";
}

function fileLabel(file: AdminLogFile): string {
  const name = file.index === 0 ? "Current file" : "Archive " + file.index;
  return file.firstAt ? name + ", from " + file.firstAt : name;
}

/** {@code text} with each occurrence of {@code query} (case-insensitive) marked. */
function highlight(text: string, query: string): m.Children {
  if (!query) return text;
  const lower = text.toLowerCase();
  const wanted = query.toLowerCase();
  const parts: m.Children[] = [];
  let from = 0;
  for (let at = lower.indexOf(wanted); at >= 0; at = lower.indexOf(wanted, from)) {
    if (at > from) parts.push(text.slice(from, at));
    parts.push(m("mark", text.slice(at, at + wanted.length)));
    from = at + wanted.length;
  }
  parts.push(text.slice(from));
  return parts;
}

/**
 * The application log: a file at a time (the current one or an archive), newest first, filtered by
 * severity and searched on the server, a page at a time.
 */
export class LogsSection implements m.ClassComponent {
  private files: AdminLogFile[] = [];
  private file = 0;
  private query = "";
  private searched = "";
  private readonly shown = new Set(SEVERITIES.map((severity) => severity.id));
  private entries: AdminLogEntry[] = [];
  private counts: Record<string, number> = {};
  private more = false;
  private loading = false;
  private error?: string;
  private readonly expanded = new Set<number>();
  /** Only the latest request's answer counts: the filters may have changed since an earlier one. */
  private request = 0;
  private searchTimer?: ReturnType<typeof setTimeout>;

  oninit() {
    this.load(false);
  }

  onremove() {
    clearTimeout(this.searchTimer);
  }

  private levels(): string[] {
    // All shown: no filter at all, entries of no known level included.
    if (this.shown.size === SEVERITIES.length) return [];
    return SEVERITIES.filter((severity) => this.shown.has(severity.id)).flatMap((severity) => severity.levels);
  }

  private load(older: boolean) {
    const request = ++this.request;
    this.loading = true;
    this.error = undefined;
    const before = older && this.entries.length ? this.entries[this.entries.length - 1].index : undefined;
    const query = this.query.trim();
    ApiEndpoints.AdminLogs.call({ file: this.file, levels: this.levels(), query: query || undefined, before })
      .then((result) => {
        if (request !== this.request) return;
        this.files = result.files;
        this.entries = older ? this.entries.concat(result.entries) : result.entries;
        this.counts = result.counts;
        this.more = result.more;
        this.searched = query;
        if (!older) this.expanded.clear();
      })
      .catch((err: Error) => {
        if (request === this.request) this.error = err.message;
      })
      .finally(() => {
        if (request === this.request) this.loading = false;
      });
  }

  view(): m.Children {
    return [
      m(".sectionheader", [
        m("h2", "Logs"),
        iconButton("refresh", "Reload", () => this.load(false), "reload"),
      ]),
      m(".logfilters", [
        m("select.logfile", {
          "aria-label": "Log file",
          disabled: this.files.length === 0,
          onchange: (e: Event) => {
            this.file = Number((e.target as HTMLSelectElement).value);
            this.load(false);
          },
        }, this.files.length === 0 ? m("option", "No log file") : this.files.map((file) =>
          m("option", { value: file.index, selected: file.index === this.file }, fileLabel(file)))),
        m("input.logsearch", {
          type: "search", placeholder: "Search", "aria-label": "Search the log", value: this.query, spellcheck: false,
          oninput: (e: Event) => {
            this.query = (e.target as HTMLInputElement).value;
            clearTimeout(this.searchTimer);
            this.searchTimer = setTimeout(() => this.load(false), SEARCH_DELAY_MS);
          },
        }),
      ]),
      m(".severities", { role: "group", "aria-label": "Severities shown" }, SEVERITIES.map((severity) => {
        const count = severity.levels.reduce((sum, level) => sum + (this.counts[level] ?? 0), 0);
        const on = this.shown.has(severity.id);
        return m("button.severity.sev-" + severity.id + (on ? ".on" : ""), {
          type: "button", "aria-pressed": String(on),
          onclick: () => {
            if (on) this.shown.delete(severity.id);
            else this.shown.add(severity.id);
            this.load(false);
          },
        }, [severity.label, m("span.count", count)]);
      })),
      this.error ? m("p.errormessage", { role: "alert" }, this.error) : null,
      this.entries.length === 0 && !this.loading && !this.error
        ? m("p.muted", this.files.length === 0 ? "There's no log file yet." : "Nothing matches.")
        : m("ol.logentries", this.entries.map((entry) => this.entry(entry))),
      this.loading ? m("p.muted", "Loading…") : null,
      this.more && !this.loading ? m("button.secondary.older", { type: "button", onclick: () => this.load(true) }, "Older entries") : null,
    ];
  }

  private entry(entry: AdminLogEntry): m.Children {
    const open = this.expanded.has(entry.index);
    const lines = entry.details ? entry.details.split("\n").length : 0;
    return m("li.logentry.sev-" + severityOf(entry.level), { key: entry.index }, [
      m(".logline", [
        m("span.logtime", { title: "Run " + entry.run + ": it changes when the service restarts" }, entry.time),
        entry.level ? m("span.loglevel", entry.level) : null,
        m("span.logsource", { title: entry.source }, entry.source),
        m("span.logmessage", highlight(entry.message, this.searched)),
      ]),
      lines > 0 ? m("button.link.logmore", {
        type: "button", "aria-expanded": String(open),
        onclick: () => (open ? this.expanded.delete(entry.index) : this.expanded.add(entry.index)),
      }, (open ? "Hide " : "Show ") + lines + (lines > 1 ? " more lines" : " more line")) : null,
      open ? m("pre.logdetails", highlight(entry.details, this.searched)) : null,
    ]);
  }
}
