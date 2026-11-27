import m from "mithril";
import { ApiEndpoints } from "../auto/ApiEndpoints";
import { AccountResult, SecondFactorStatus } from "../auto/api-types";

export interface MailFactorSectionAttrs {
  username: string;
}

/** What the section shows besides the status. */
type Step =
  | { kind: "status" }
  /** Asking the address and password, to mail a code. */
  | { kind: "address" }
  /** Asking the code mailed to the address. */
  | { kind: "code"; address: string }
  /** Asking the password, to turn it off. */
  | { kind: "remove" };

function formatDate(iso: string | undefined): string | undefined {
  return iso ? new Date(iso).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" }) : undefined;
}

/**
 * Two-step verification by mail: setting it up mails a code to the address, which the user types
 * back. Setting it up, changing the address and turning it off all take the password.
 */
export class MailFactorSection implements m.ClassComponent<MailFactorSectionAttrs> {
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
        // A code sent earlier, still valid: back to typing it.
        if (status.pendingAddress && this.step.kind === "status") this.step = { kind: "code", address: status.pendingAddress };
      })
      .catch((err: Error) => (this.loadError = err.message));
  }

  view({ attrs }: m.Vnode<MailFactorSectionAttrs>): m.Children {
    const status = this.status;
    const heading = m("h2", "Two-step verification");
    if (!status) return [heading, this.loadError ? m("p.errormessage", { role: "alert" }, this.loadError) : m("p.muted", "Loading…")];
    const mail = status.mail;
    return [
      heading,
      m("p.muted", "After your password, a code sent to you is asked at login: your password alone doesn't let anyone in."),
      mail
        ? m("p", ["Codes are mailed to ", m("strong", mail.address), formatDate(mail.confirmedAt) ? ", since " + formatDate(mail.confirmedAt) + "." : "."])
        : m("p", "Off."),
      this.stepView(status, attrs.username),
      this.success ? m("p.successmessage", { role: "status" }, this.success) : null,
    ];
  }

  private stepView(status: SecondFactorStatus, username: string): m.Children {
    const step = this.step;
    switch (step.kind) {
      case "status":
        if (!status.mailAvailable) {
          return m("p.muted", status.mail
            ? "This server can't send mail anymore: its administrator has to set it up again."
            : "Not available: this server's administrator hasn't set up outgoing mail.");
        }
        return m(".buttons", status.mail
          ? [this.button("Change address", () => this.goTo({ kind: "address" }, status.mail!.address), true),
            this.button("Turn off", () => this.goTo({ kind: "remove" }), true)]
          : this.button("Set up by mail", () => this.goTo({ kind: "address" }, status.contactEmail)));
      case "address":
        return this.form(username, () => this.sendCode(), [
          this.input("Address", "email", "email", this.address, (v) => (this.address = v), status.mail
            ? "Where the codes will be mailed. Once confirmed, " + status.mail.address + " is told of the change."
            : "Where the codes will be mailed."),
          this.passwordInput(),
        ], "Send code", !this.address.trim() || !this.password);
      case "code":
        return this.form(username, () => this.confirm(step.address), [
          m("p", ["A code was mailed to ", m("strong", step.address), ". It's valid for 10 minutes."]),
          this.input("Code", "text", "one-time-code", this.code, (v) => (this.code = v), "Not there? Check the spam folder, or send another one.",
            { inputmode: "numeric", maxlength: 7 }),
        ], "Confirm", !this.code.trim(), this.button("Send another one", () => this.goTo({ kind: "address" }, step.address), true));
      case "remove":
        return this.form(username, () => this.remove(), [
          m("p", "Without it, your password alone lets anyone in. " + (status.mail ? status.mail.address + " will be told." : "")),
          this.passwordInput(),
        ], "Turn off", !this.password);
    }
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
        this.button("Cancel", () => this.cancel(), true),
      ]),
      this.error ? m("p.errormessage", { role: "alert" }, this.error) : null,
    ]);
  }

  private cancel() {
    this.goTo({ kind: "status" });
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

  private sendCode() {
    const address = this.address.trim();
    this.run(() => ApiEndpoints.SendMailFactorCode.call({ address, password: this.password }), () => this.goTo({ kind: "code", address }));
  }

  private confirm(address: string) {
    this.run(() => ApiEndpoints.ConfirmMailFactor.call({ code: this.code }), () => {
      this.goTo({ kind: "status" });
      this.success = "Two-step verification is on: codes will be mailed to " + address + ".";
      this.load();
    });
  }

  private remove() {
    this.run(() => ApiEndpoints.RemoveMailFactor.call({ password: this.password }), () => {
      this.goTo({ kind: "status" });
      this.success = "Two-step verification is off.";
      this.load();
    });
  }
}
