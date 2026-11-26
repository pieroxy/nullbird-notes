import m from "mithril";
import { ApiEndpoints } from "../auto/ApiEndpoints";
import { AdminMailOut, Security } from "../auto/api-types";

/** The form's fields, as typed. */
interface Fields {
  host: string;
  port: string;
  security: Security;
  username: string;
  /** Empty: the saved one stays. */
  password: string;
  from: string;
}

const SECURITIES: { value: Security; label: string; port: number }[] = [
  { value: Security.STARTTLS, label: "STARTTLS (usually port 587)", port: 587 },
  { value: Security.TLS, label: "TLS (usually port 465)", port: 465 },
  { value: Security.NONE, label: "None (usually port 25)", port: 25 },
];

function defaultPort(security: Security): number {
  return SECURITIES.find((option) => option.value === security)!.port;
}

function fieldsOf(mail: AdminMailOut): Fields {
  return { host: mail.host, port: String(mail.port), security: mail.security, username: mail.username, password: "", from: mail.from };
}

function changed(mail: AdminMailOut, fields: Fields): boolean {
  return fields.host.trim() !== mail.host || fields.port.trim() !== String(mail.port) || fields.security !== mail.security
    || fields.username.trim() !== mail.username || fields.password !== "" || fields.from.trim() !== mail.from;
}

/**
 * The SMTP server Notes sends mail through, saved to config.json: it applies to the next mail sent,
 * no restart. The saved password is never shown: typing one replaces it.
 */
export class MailOutSection implements m.ClassComponent {
  private mail?: AdminMailOut;
  private fields?: Fields;
  private loadError?: string;
  private saving = false;
  private error?: string;
  private saved = false;
  /** Where the test mail goes: the admin's own address at first. */
  private testTo?: string;
  private testing = false;
  private testError?: string;
  private testSent?: string;

  oninit() {
    this.load();
  }

  private load() {
    ApiEndpoints.AdminMailOut.call({})
      .then((mail) => {
        this.mail = mail;
        this.fields = fieldsOf(mail);
        this.testTo ??= mail.yourEmail;
        this.loadError = undefined;
      })
      .catch((err: Error) => (this.loadError = err.message));
  }

  view(): m.Children {
    const mail = this.mail;
    const fields = this.fields;
    if (!mail || !fields) return [m("h2", "Mail out"), this.loadError ? m("p.errormessage", { role: "alert" }, this.loadError) : m("p.muted", "Loading…")];
    const dirty = changed(mail, fields);
    const port = /^\s*\d+\s*$/.test(fields.port) ? Number(fields.port) : NaN;
    const off = fields.host.trim() === "";
    const inClear = !off && fields.security === Security.NONE && fields.username.trim() !== "";

    const text = (key: "host" | "port" | "username" | "password" | "from", label: string, hint: m.Children, attrs: Record<string, unknown> = {}) =>
      m("label.field", [
        m("span.fieldlabel", label),
        m("input", {
          type: "text", autocomplete: "off", spellcheck: false, ...attrs, value: fields[key], disabled: this.saving,
          oninput: (e: Event) => {
            fields[key] = (e.target as HTMLInputElement).value;
            this.saved = false;
          },
        }),
        m("span.fieldhint", hint),
      ]);

    return [
      m("h2", "Mail out"),
      m("p.muted", "The SMTP server Notes sends mail through. Saved to config.json, it applies to the next mail sent: no restart."),
      m("form.stackedform.configform", { onsubmit: (e: Event) => this.submit(e, port) }, [
        m("h3", "Server"),
        text("host", "SMTP server", "Its host name or IP address, e.g. smtp.example.com. Blank: Notes sends no mail."),
        m("label.field", [
          m("span.fieldlabel", "Connection security"),
          m("select", {
            value: fields.security, disabled: this.saving,
            onchange: (e: Event) => {
              const security = (e.target as HTMLSelectElement).value as Security;
              // A port left at the previous security's default follows the new one's.
              if (fields.port.trim() === "" || Number(fields.port) === defaultPort(fields.security)) fields.port = String(defaultPort(security));
              fields.security = security;
              this.saved = false;
            },
          }, SECURITIES.map((option) => m("option", { value: option.value }, option.label))),
          m("span.fieldhint", "TLS and STARTTLS both encrypt the connection; none is only for a relay on this machine or its network."),
        ]),
        text("port", "Port", "Changing the security above moves a default port along.", { inputmode: "numeric" }),
        m("h3", "Sending"),
        text("from", "Sender address", "The From of every mail Notes sends, e.g. notes@example.com. The server must allow it."),
        m("h3", "Authentication"),
        text("username", "User name", "Blank: no authentication, and the saved password is removed."),
        text("password", "Password",
          mail.passwordSet ? "A password is saved: leave this blank to keep it, or type a new one." : "Needed with a user name.",
          { type: "password", autocomplete: "new-password", placeholder: mail.passwordSet ? "Saved" : "" }),
        inClear ? m("ul.warnings", { role: "alert" }, m("li", "Without TLS or STARTTLS, the user name and password cross the network in clear.")) : null,
        m(".buttons", [
          m("button", { type: "submit", disabled: this.saving || !dirty }, "Save"),
          m("button.secondary", {
            type: "button", disabled: this.saving || !dirty,
            onclick: () => {
              this.fields = fieldsOf(mail);
              this.error = undefined;
            },
          }, "Undo changes"),
        ]),
        this.error ? m("p.errormessage", { role: "alert" }, this.error) : null,
        this.saved ? m("p.successmessage", { role: "status" }, "Saved.") : null,
      ]),
      this.testForm(mail, dirty),
    ];
  }

  /** With the saved settings: unsaved changes would test something else than what's used. */
  private testForm(mail: AdminMailOut, dirty: boolean): m.Children {
    const blocked = mail.host === "" ? "Save an SMTP server first." : dirty ? "Save your changes first: the test uses the saved settings." : undefined;
    return m("form.stackedform.configform", { onsubmit: (e: Event) => this.sendTest(e) }, [
      m("h3", "Test"),
      m("label.field", [
        m("span.fieldlabel", "Send a test mail to"),
        m("input", {
          type: "email", autocomplete: "email", spellcheck: false, value: this.testTo ?? "", disabled: this.testing,
          oninput: (e: Event) => {
            this.testTo = (e.target as HTMLInputElement).value;
            this.testSent = undefined;
          },
        }),
        m("span.fieldhint", blocked ?? "A short mail, sent right away. Any error the server gives is shown here."),
      ]),
      m(".buttons", m("button.secondary", { type: "submit", disabled: this.testing || blocked !== undefined || !this.testTo?.trim() },
        this.testing ? "Sending…" : "Send test mail")),
      this.testError ? m("p.errormessage", { role: "alert" }, this.testError) : null,
      this.testSent ? m("p.successmessage", { role: "status" }, "Sent to " + this.testSent + ": check that it arrived, spam folder included.") : null,
    ]);
  }

  private sendTest(e: Event) {
    e.preventDefault();
    const to = (this.testTo ?? "").trim();
    this.testing = true;
    this.testError = undefined;
    this.testSent = undefined;
    ApiEndpoints.AdminTestMailOut.call({ to })
      .then((result) => {
        if (result.ok) this.testSent = to;
        else this.testError = result.error ?? "The test mail couldn't be sent.";
      })
      .catch((err: Error) => (this.testError = err.message))
      .finally(() => (this.testing = false));
  }

  private submit(e: Event, port: number) {
    e.preventDefault();
    const fields = this.fields!;
    if (isNaN(port)) {
      this.error = "The port must be a number.";
      return;
    }
    this.saving = true;
    this.error = undefined;
    this.saved = false;
    ApiEndpoints.AdminSaveMailOut.call({
      host: fields.host, port, security: fields.security, smtpUsername: fields.username,
      password: fields.password === "" ? undefined : fields.password, from: fields.from,
    })
      .then((result) => {
        if (result.ok) {
          this.saved = true;
          this.load();
        } else {
          this.error = result.error ?? "The settings couldn't be saved.";
        }
      })
      .catch((err: Error) => (this.error = err.message))
      .finally(() => (this.saving = false));
  }
}
