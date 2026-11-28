import m from "mithril";
import { ApiEndpoints } from "../auto/ApiEndpoints";
import { AdminBackup, AdminResult, Frequency } from "../auto/api-types";

/** The form's fields, as typed. */
interface Fields {
  folder: string;
  every: Frequency;
  keep: string;
}

const FREQUENCIES: { value: Frequency; label: string }[] = [
  { value: Frequency.HOURLY, label: "Every hour" },
  { value: Frequency.DAILY, label: "Every day" },
];

function fieldsOf(backup: AdminBackup): Fields {
  return { folder: backup.folder, every: backup.every, keep: String(backup.keep) };
}

function changed(backup: AdminBackup, fields: Fields): boolean {
  return fields.folder.trim() !== backup.folder || fields.every !== backup.every || fields.keep.trim() !== String(backup.keep);
}

function formatTime(millis: number): string {
  return new Date(millis).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + " bytes";
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return value.toLocaleString(undefined, { maximumFractionDigits: value < 10 ? 1 : 0 }) + " " + units[unit];
}

/**
 * Backups: a zip in a folder of the server, every hour or day when something changed, the oldest
 * beyond a number deleted (see BackupService). Off, a form to activate them: the folder is checked
 * first, and a backup made right away. On, how they went, the same form to change them, and buttons
 * to make one now or to stop them.
 */
export class BackupSection implements m.ClassComponent {
  private backup?: AdminBackup;
  private fields?: Fields;
  private loadError?: string;
  private busy = false;
  private error?: string;
  private poll?: number;

  oninit() {
    this.load();
  }

  onremove() {
    window.clearTimeout(this.poll);
  }

  /** Again and again while one is being made, to show how it went. */
  private load() {
    window.clearTimeout(this.poll);
    ApiEndpoints.AdminBackup.call({})
      .then((backup) => {
        const first = !this.backup;
        const settingsChanged = this.backup && (this.backup.active !== backup.active || this.backup.folder !== backup.folder
          || this.backup.every !== backup.every || this.backup.keep !== backup.keep);
        this.backup = backup;
        // Deactivated: the settings stay in the form, to activate them again.
        if (first || (settingsChanged && backup.active)) this.fields = fieldsOf(backup);
        this.loadError = undefined;
        if (backup.running) this.poll = window.setTimeout(() => this.load(), 1000);
      })
      .catch((err: Error) => (this.loadError = err.message));
  }

  view(): m.Children {
    const backup = this.backup;
    const fields = this.fields;
    if (!backup || !fields) return [m("h2", "Backups"), this.loadError ? m("p.errormessage", { role: "alert" }, this.loadError) : m("p.muted", "Loading…")];
    const dirty = changed(backup, fields);

    return [
      m("h2", "Backups"),
      m("p.muted", "A zip of every user's notes, the users and the settings, made in a folder of this server when something changed. "
        + "It holds what logging in needs (password hashes, two-step verification secrets) and the SMTP password: only the server's user can read it."),
      backup.active ? this.status(backup) : null,
      m("form.stackedform.configform", { onsubmit: (e: Event) => this.save(e) }, [
        backup.active ? m("h3", "Settings") : null,
        m("label.field", [
          m("span.fieldlabel", "Folder"),
          m("input", {
            type: "text", autocomplete: "off", spellcheck: false, placeholder: "/mnt/backups/notes", value: fields.folder, disabled: this.busy,
            oninput: (e: Event) => (fields.folder = (e.target as HTMLInputElement).value),
          }),
          m("span.fieldhint", "A full path to a folder of the server that exists, empty or with backups only. Notes never creates it: "
            + "if its drive isn't mounted, the backup fails rather than fill this server's disk. With Docker, a folder of a mounted volume."),
        ]),
        m("label.field", [
          m("span.fieldlabel", "How often"),
          m("select", {
            value: fields.every, disabled: this.busy,
            onchange: (e: Event) => (fields.every = (e.target as HTMLSelectElement).value as Frequency),
          }, FREQUENCIES.map((option) => m("option", { value: option.value }, option.label))),
          m("span.fieldhint", "Only if something changed since the last one."),
        ]),
        m("label.field", [
          m("span.fieldlabel", "Backups to keep"),
          m("input", {
            type: "number", min: 1, max: backup.maxKeep, inputmode: "numeric", value: fields.keep, disabled: this.busy,
            oninput: (e: Event) => (fields.keep = (e.target as HTMLInputElement).value),
          }),
          m("span.fieldhint", "The oldest beyond this are deleted."),
        ]),
        m(".buttons", backup.active ? [
          m("button", { type: "submit", disabled: this.busy || !dirty }, "Update"),
          m("button.secondary", { type: "button", disabled: this.busy || backup.running, onclick: () => this.backUpNow() }, "Back up now"),
          m("button.secondary", { type: "button", disabled: this.busy, onclick: () => this.deactivate(backup) }, "Deactivate"),
        ] : m("button", { type: "submit", disabled: this.busy || fields.folder.trim() === "" }, this.busy ? "Checking…" : "Activate backups")),
        this.error ? m("p.errormessage", { role: "alert" }, this.error) : null,
      ]),
    ];
  }

  private status(backup: AdminBackup): m.Children {
    const failed = backup.lastErrorAt !== undefined && backup.lastError !== undefined;
    return [
      m("dl.userfacts", { "aria-live": "polite" }, [
        m("dt", "Status"), m("dd", backup.running ? "Backing up…" : failed ? "The last backup failed" : "Active"),
        m("dt", "Last backup"),
        m("dd", backup.lastBackupAt !== undefined
          ? [formatTime(backup.lastBackupAt), backup.lastBackupSize !== undefined ? ", " + formatSize(backup.lastBackupSize) : "",
            backup.lastBackupFile ? m("br") : null, backup.lastBackupFile ? m("span.muted", backup.lastBackupFile) : null]
          : "None yet"),
        m("dt", "Next"),
        m("dd", backup.nextAt === undefined ? "—" : backup.nextAt <= Date.now() ? "Within a minute, if something changed"
          : formatTime(backup.nextAt) + ", if something changed"),
      ]),
      failed ? m("p.errormessage", { role: "alert" }, "Failed " + formatTime(backup.lastErrorAt!) + ": " + backup.lastError) : null,
    ];
  }

  private save(e: Event) {
    e.preventDefault();
    const fields = this.fields!;
    const keep = /^\s*\d+\s*$/.test(fields.keep) ? Number(fields.keep) : NaN;
    if (isNaN(keep)) {
      this.error = "The number of backups to keep must be a number.";
      return;
    }
    this.act(ApiEndpoints.AdminSaveBackup.call({ folder: fields.folder, every: fields.every, keep }), "The backups couldn't be saved.");
  }

  private backUpNow() {
    this.act(ApiEndpoints.AdminBackupNow.call({}), "The backup couldn't be started.");
  }

  private deactivate(backup: AdminBackup) {
    if (!confirm("Stop the backups? Those already in " + backup.folder + " stay there.")) return;
    this.act(ApiEndpoints.AdminDeactivateBackup.call({}), "The backups couldn't be deactivated.");
  }

  private act(call: Promise<AdminResult>, failure: string) {
    this.busy = true;
    this.error = undefined;
    call
      .then((result) => {
        if (result.ok) this.load();
        else this.error = result.error ?? failure;
      })
      .catch((err: Error) => (this.error = err.message))
      .finally(() => (this.busy = false));
  }
}
