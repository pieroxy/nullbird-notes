import m from "mithril";
import { ApiEndpoints } from "../auto/ApiEndpoints";
import { AccountResult, SecondFactorStatus } from "../auto/api-types";
import { encodeQr, qrPath } from "../utils/QrCode";
import { CopyableText } from "./CopyableText";

export interface SecondFactorSectionAttrs {
  username: string;
}

/** What the section shows besides the status. */
type Step =
  | { kind: "status" }
  /** The password, to get a new secret. */
  | { kind: "totpPassword" }
  /** The secret for the app, then a code from it. */
  | { kind: "totpCode"; secret: string }
  /** The password, to turn the app off. */
  | { kind: "totpRemove" }
  /** The address and password, to mail a code. */
  | { kind: "mailAddress" }
  /** The code mailed to the address. */
  | { kind: "mailCode"; address: string }
  /** The password, to turn mail off. */
  | { kind: "mailRemove" };

const QR_MARGIN = 4;

function formatDate(iso: string | undefined): string {
  return iso ? ", since " + new Date(iso).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" }) : "";
}

/** As the apps list it: "Notes", then the account on this server, by the name in the address bar. */
function otpauthUri(username: string, secret: string): string {
  return "otpauth://totp/Notes:" + encodeURIComponent(username + "@" + location.hostname) + "?secret=" + secret + "&issuer=Notes";
}

/** The key in groups of 4, easier to type. */
function grouped(secret: string): string {
  return secret.replace(/(.{4})(?=.)/g, "$1 ");
}

/**
 * Two-step verification: codes from an authenticator app, or mailed. Setting either up, replacing
 * it and turning it off take the password; with both, the user picks the one asked first.
 */
export class SecondFactorSection implements m.ClassComponent<SecondFactorSectionAttrs> {
  private status?: SecondFactorStatus;
  private loadError?: string;
  private step: Step = { kind: "status" };
  private address = "";
  private password = "";
  private code = "";
  private busy = false;
  private error?: string;
  private success?: string;

  oninit() {
    this.load();
  }

  private load() {
    ApiEndpoints.SecondFactor.call({})
      .then((status) => {
        this.status = status;
        this.loadError = undefined;
        // A code mailed earlier, still valid: back to typing it.
        if (status.pendingAddress && this.step.kind === "status") this.step = { kind: "mailCode", address: status.pendingAddress };
      })
      .catch((err: Error) => (this.loadError = err.message));
  }

  view({ attrs }: m.Vnode<SecondFactorSectionAttrs>): m.Children {
    const status = this.status;
    const heading = m("h2", "Two-step verification");
    if (!status) return [heading, this.loadError ? m("p.errormessage", { role: "alert" }, this.loadError) : m("p.muted", "Loading…")];
    const step = this.step;
    const totpStep = step.kind.startsWith("totp");
    const mailStep = step.kind.startsWith("mail");
    return [
      heading,
      m("p.muted", "After your password, a code is asked at login: your password alone doesn't let anyone in."),
      m("h3", "Authenticator app"),
      m("p", status.totp ? "On" + formatDate(status.totp.confirmedAt) + "." : "Off."),
      totpStep ? this.totpStep(status, attrs.username) : step.kind === "status" ? this.totpButtons(status) : null,
      m("h3", "Mail"),
      m("p", status.mail ? ["Codes are mailed to ", m("strong", status.mail.address), formatDate(status.mail.confirmedAt) + "."] : "Off."),
      mailStep ? this.mailStep(status, attrs.username) : step.kind === "status" ? this.mailButtons(status) : null,
      status.totp && status.mail ? this.preferred(status) : null,
      step.kind === "status" && this.error ? m("p.errormessage", { role: "alert" }, this.error) : null,
      this.success ? m("p.successmessage", { role: "status" }, this.success) : null,
    ];
  }

  private totpButtons(status: SecondFactorStatus): m.Children {
    return m(".buttons", status.totp
      ? [this.button("Move to another app", () => this.goTo({ kind: "totpPassword" }), true), this.button("Turn off", () => this.goTo({ kind: "totpRemove" }), true)]
      : this.button("Set up an app", () => this.goTo({ kind: "totpPassword" })));
  }

  private mailButtons(status: SecondFactorStatus): m.Children {
    if (!status.mailAvailable) {
      return status.mail
        ? [m("p.muted", "This server can't send mail anymore: its administrator has to set it up again."),
          m(".buttons", this.button("Turn off", () => this.goTo({ kind: "mailRemove" }), true))]
        : m("p.muted", "Not available: this server's administrator hasn't set up outgoing mail.");
    }
    return m(".buttons", status.mail
      ? [this.button("Change address", () => this.goTo({ kind: "mailAddress" }, status.mail!.address), true),
        this.button("Turn off", () => this.goTo({ kind: "mailRemove" }), true)]
      : this.button("Set up by mail", () => this.goTo({ kind: "mailAddress" }, status.contactEmail)));
  }

  /** Who'll be told when the app is turned off or replaced, as the server picks it. */
  private totpNoticeTo(status: SecondFactorStatus): string | undefined {
    return status.mail?.address ?? (status.contactEmail || undefined);
  }

  private totpStep(status: SecondFactorStatus, username: string): m.Children {
    const step = this.step;
    const noticeTo = this.totpNoticeTo(status);
    switch (step.kind) {
      case "totpPassword":
        return this.form(username, () => this.startTotp(), [
          status.totp ? m("p", "Your current app keeps working until the new one gives its first code.") : null,
          this.passwordInput(),
        ], "Next", !this.password);
      case "totpCode":
        return this.form(username, () => this.confirmTotp(), [
          m("p", "Add Notes to an authenticator app, for example Apple Passwords (iPhone, Mac), Google Authenticator (Android) "
            + "or Ente Auth (any device): they keep your codes when you change phones."),
          this.qrCode(otpauthUri(username, step.secret)),
          m("a.button.secondary.otpauthlink", { href: otpauthUri(username, step.secret) }, "Open in my authenticator app"),
          m(".totpkey", [
            m("p.muted", "Or type this key in the app, as a time-based key:"),
            m(CopyableText, {
              text: grouped(step.secret), kind: "totpkey",
              hint: "Spaces don't matter.", selectedHint: "Selected: copy it with Ctrl+C or ⌘C.",
            }),
          ]),
          this.input("Code from the app", "text", "one-time-code", this.code, (v) => (this.code = v),
            status.totp && noticeTo ? "Once confirmed, the previous app stops working, and " + noticeTo + " is told." : undefined,
            { inputmode: "numeric", maxlength: 7 }),
        ], "Confirm", !this.code.trim());
      case "totpRemove":
        return this.form(username, () => this.removeTotp(), [
          m("p", (status.mail ? "Login will ask for codes by mail only." : "Without it, your password alone lets anyone in.")
            + (noticeTo ? " " + noticeTo + " will be told." : "")),
          this.passwordInput(),
        ], "Turn off", !this.password);
      default:
        return null;
    }
  }

  private mailStep(status: SecondFactorStatus, username: string): m.Children {
    const step = this.step;
    switch (step.kind) {
      case "mailAddress":
        return this.form(username, () => this.sendCode(), [
          this.input("Address", "email", "email", this.address, (v) => (this.address = v), status.mail
            ? "Where the codes will be mailed. Once confirmed, " + status.mail.address + " is told of the change."
            : "Where the codes will be mailed."),
          this.passwordInput(),
        ], "Send code", !this.address.trim() || !this.password);
      case "mailCode":
        return this.form(username, () => this.confirmMail(step.address), [
          m("p", ["A code was mailed to ", m("strong", step.address), ". It's valid for 10 minutes."]),
          this.input("Code", "text", "one-time-code", this.code, (v) => (this.code = v), "Not there? Check the spam folder, or send another one.",
            { inputmode: "numeric", maxlength: 7 }),
        ], "Confirm", !this.code.trim(), this.button("Send another one", () => this.goTo({ kind: "mailAddress" }, step.address), true));
      case "mailRemove":
        return this.form(username, () => this.removeMail(), [
          m("p", (status.totp ? "Login will ask for codes from your authenticator app only." : "Without it, your password alone lets anyone in.")
            + (status.mail ? " " + status.mail.address + " will be told." : "")),
          this.passwordInput(),
        ], "Turn off", !this.password);
      default:
        return null;
    }
  }

  /** On a light background whatever the theme: scanners read dark on light. */
  private qrCode(uri: string): m.Children {
    const qr = encodeQr(uri);
    const side = qr.size + 2 * QR_MARGIN;
    return m("svg.qrcode", { viewBox: "0 0 " + side + " " + side, role: "img", "aria-label": "QR code for your authenticator app" }, [
      m("rect", { width: side, height: side, fill: "#fff" }),
      m("path", { d: qrPath(qr, QR_MARGIN), fill: "#000" }),
    ]);
  }

  private preferred(status: SecondFactorStatus): m.Children {
    const choice = (value: "totp" | "mail", label: string) => m("label.checkfield", [
      m("input", {
        type: "radio", name: "preferredfactor", value, checked: status.preferred === value, disabled: this.busy,
        onchange: () => this.setPreferred(value),
      }),
      m("span", label),
    ]);
    return [
      m("h3", "Asked first at login"),
      m(".stackedform", { role: "radiogroup", "aria-label": "Asked first at login" }, [
        choice("totp", "Authenticator app"),
        choice("mail", "Mail"),
        m("p.muted", "Login offers the other one too."),
      ]),
    ];
  }

  private goTo(step: Step, address?: string) {
    this.step = step;
    if (address !== undefined) this.address = address;
    this.password = "";
    this.code = "";
    this.error = undefined;
    this.success = undefined;
  }

  private button(label: string, onclick: () => void, secondary = false): m.Children {
    return m("button" + (secondary ? ".secondary" : ""), { type: "button", disabled: this.busy, onclick }, label);
  }

  private input(label: string, type: string, autocomplete: string, value: string, set: (value: string) => void, hint?: string,
    attrs: Record<string, unknown> = {}): m.Children {
    return m("label.field", [
      m("span.fieldlabel", label),
      m("input", {
        type, autocomplete, spellcheck: false, ...attrs, value, disabled: this.busy,
        oninput: (e: Event) => set((e.target as HTMLInputElement).value),
      }),
      hint ? m("span.fieldhint", hint) : null,
    ]);
  }

  private passwordInput(): m.Children {
    return this.input("Your password", "password", "current-password", this.password, (v) => (this.password = v));
  }

  private form(username: string, submit: () => void, fields: m.Children[], label: string, incomplete: boolean, extra?: m.Children): m.Children {
    return m("form.stackedform", {
      onsubmit: (e: Event) => {
        e.preventDefault();
        submit();
      },
    }, [
      // For password managers: which account the password belongs to.
      m("input.hidden", { type: "text", autocomplete: "username", value: username, readonly: true, tabindex: -1, "aria-hidden": "true" }),
      ...fields,
      m(".buttons", [
        m("button", { type: "submit", disabled: this.busy || incomplete }, label),
        extra,
        this.button("Cancel", () => this.goTo({ kind: "status" }), true),
      ]),
      this.error ? m("p.errormessage", { role: "alert" }, this.error) : null,
    ]);
  }

  /** A refusal stays on the current step, with its error. */
  private run(call: () => Promise<AccountResult>, onOk: () => void) {
    this.busy = true;
    this.error = undefined;
    this.success = undefined;
    call()
      .then((result) => {
        if (result.ok) onOk();
        else this.error = result.error ?? "That didn't work.";
      })
      .catch((err: Error) => (this.error = err.message))
      .finally(() => (this.busy = false));
  }

  /** Back to the status, reloaded, saying what changed. */
  private done(success: string) {
    this.goTo({ kind: "status" });
    this.success = success;
    this.load();
  }

  private startTotp() {
    this.busy = true;
    this.error = undefined;
    ApiEndpoints.StartTotpFactor.call({ password: this.password })
      .then((setup) => {
        if (setup.ok && setup.secret) this.goTo({ kind: "totpCode", secret: setup.secret });
        else this.error = setup.error ?? "That didn't work.";
      })
      .catch((err: Error) => (this.error = err.message))
      .finally(() => (this.busy = false));
  }

  private confirmTotp() {
    this.run(() => ApiEndpoints.ConfirmTotpFactor.call({ code: this.code }), () => this.done("Your authenticator app is set up: login will ask for its codes."));
  }

  private removeTotp() {
    this.run(() => ApiEndpoints.RemoveTotpFactor.call({ password: this.password }), () => this.done("Your authenticator app is turned off."));
  }

  private sendCode() {
    const address = this.address.trim();
    this.run(() => ApiEndpoints.SendMailFactorCode.call({ address, password: this.password }), () => this.goTo({ kind: "mailCode", address }));
  }

  private confirmMail(address: string) {
    this.run(() => ApiEndpoints.ConfirmMailFactor.call({ code: this.code }), () => this.done("Codes will be mailed to " + address + "."));
  }

  private removeMail() {
    this.run(() => ApiEndpoints.RemoveMailFactor.call({ password: this.password }), () => this.done("Codes are no longer mailed."));
  }

  private setPreferred(method: "totp" | "mail") {
    this.run(() => ApiEndpoints.SetPreferredFactor.call({ method }), () => this.done(method === "totp"
      ? "Login will ask for your authenticator app's code first." : "Login will mail you a code first."));
  }
}
