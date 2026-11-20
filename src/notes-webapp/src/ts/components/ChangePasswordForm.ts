import m from "mithril";
import { ApiEndpoints } from "../auto/ApiEndpoints";
import { MIN_PASSWORD_LENGTH, newPasswordProblem } from "../utils/PasswordRules";

export interface ChangePasswordFormAttrs {
  username: string;
}

/**
 * Current password, then the new one twice. Checked here first, then by the server, which also
 * logs the user out of their other sessions.
 */
export class ChangePasswordForm implements m.ClassComponent<ChangePasswordFormAttrs> {
  private current = "";
  private next = "";
  private again = "";
  private submitting = false;
  private error?: string;
  private done = false;

  view({ attrs }: m.Vnode<ChangePasswordFormAttrs>): m.Children {
    const field = (label: string, value: string, autocomplete: string, set: (value: string) => void, hint?: string) =>
      m("label.field", [
        m("span.fieldlabel", label),
        m("input", {
          type: "password", autocomplete, value, disabled: this.submitting,
          oninput: (e: Event) => {
            set((e.target as HTMLInputElement).value);
            this.done = false;
          },
        }),
        hint ? m("span.fieldhint", hint) : null,
      ]);
    return m("form.changepassword", { onsubmit: (e: Event) => this.submit(e) }, [
      // For password managers: which account this password belongs to.
      m("input.hidden", { type: "text", autocomplete: "username", value: attrs.username, readonly: true, tabindex: -1, "aria-hidden": "true" }),
      field("Current password", this.current, "current-password", (v) => (this.current = v)),
      field("New password", this.next, "new-password", (v) => (this.next = v), "At least " + MIN_PASSWORD_LENGTH + " characters."),
      field("New password, again", this.again, "new-password", (v) => (this.again = v)),
      m("button", { type: "submit", disabled: this.submitting || !this.current || !this.next || !this.again }, "Change password"),
      this.error ? m("p.errormessage", { role: "alert" }, this.error) : null,
      this.done ? m("p.successmessage", { role: "status" }, "Password changed. Your other devices have been logged out.") : null,
    ]);
  }

  private submit(e: Event) {
    e.preventDefault();
    this.done = false;
    this.error = newPasswordProblem(this.next, this.again);
    if (this.error) return;
    this.submitting = true;
    ApiEndpoints.ChangePassword.call({ currentPassword: this.current, newPassword: this.next })
      .then((output) => {
        if (output.ok) {
          this.current = this.next = this.again = "";
          this.done = true;
        } else {
          this.error = output.error ?? "The password couldn't be changed.";
        }
      })
      .catch((err: Error) => (this.error = err.message))
      .finally(() => {
        this.submitting = false;
        m.redraw();
      });
  }
}
