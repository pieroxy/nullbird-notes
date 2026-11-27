import m from "mithril";
import { ApiEndpoints } from "../../auto/ApiEndpoints";
import { LoginApiOutput } from "../../auto/api-types";
import { Auth } from "../../utils/Auth";
import { Endpoints } from "../../utils/navigation/Endpoints";
import { Routing } from "../../utils/navigation/Routing";
import { logo } from "../Logo";
import { AbstractPage } from "./AbstractPage";

/** Fetched once: logging out recreates the page. */
let version: string | undefined;

export class LoginPage extends AbstractPage {
  private login = "";
  private password = "";
  private submitting = false;
  /** On the button that sent the request, while it runs: the mail may take seconds. */
  private waitingLabel = "";
  private error: string | undefined;
  /** The password was right: a code is asked, as the server said (see LoginApiOutput). */
  private challenge: string | undefined;
  private method: string | undefined;
  private sentTo: string | undefined;
  private alternative: string | undefined;
  private code = "";

  oninit() {
    if (version === undefined) {
      ApiEndpoints.Version.call({})
        .then((output) => (version = output.version + " (" + output.revision + ")"))
        .catch(() => undefined);
    }
  }

  getPageTitle(): string {
    return "Notes - Login";
  }

  render(): m.Children {
    return m("main.loginpage",
      m("form", { onsubmit: (e: Event) => (this.challenge ? this.submitCode(e) : this.submit(e)) }, [
        m("h1", logo()),
        // Keyed: switching steps creates the fields anew, so that the code field doesn't reuse the password's.
        [this.challenge ? m.fragment({ key: "code" }, this.codeFields()) : m.fragment({ key: "password" }, this.passwordFields())],
        this.error ? m(".errormessage", { role: "alert" }, this.error) : null,
        version ? m("p.loginversion", "Version " + version) : null,
      ]));
  }

  private passwordFields(): m.ChildArray {
    return [
      m("input", {
        type: "text",
        placeholder: "Login",
        autocomplete: "username",
        autocapitalize: "none",
        value: this.login,
        disabled: this.submitting,
        oninput: (e: Event) => (this.login = (e.target as HTMLInputElement).value),
      }),
      m("input", {
        type: "password",
        placeholder: "Password",
        autocomplete: "current-password",
        value: this.password,
        disabled: this.submitting,
        oninput: (e: Event) => (this.password = (e.target as HTMLInputElement).value),
      }),
      m("button", { type: "submit", disabled: this.submitting }, this.submitting ? this.waitingLabel : "Log in"),
    ];
  }

  private codeFields(): m.ChildArray {
    return [
      m("p.loginhint", this.method === "mail"
        ? ["A code was mailed to ", m("strong", this.sentTo), ". Type it to finish logging in."]
        : "Type the code your authenticator app shows for Notes."),
      m("input", {
        type: "text",
        placeholder: "Code",
        autocomplete: "one-time-code",
        inputmode: "numeric",
        maxlength: 7,
        value: this.code,
        disabled: this.submitting,
        oncreate: (vnode: m.VnodeDOM) => (vnode.dom as HTMLInputElement).focus(),
        oninput: (e: Event) => (this.code = (e.target as HTMLInputElement).value),
      }),
      m("button", { type: "submit", disabled: this.submitting || !this.code.trim() }, this.submitting ? this.waitingLabel : "Log in"),
      m(".loginlinks", [
        this.method === "mail" ? this.methodLink("mail", "Send a new code", "Sending code…") : null,
        this.alternative === "mail" ? this.methodLink("mail", "Mail me a code instead", "Sending code…") : null,
        this.alternative === "totp" ? this.methodLink("totp", "Use my authenticator app instead", "Switching…") : null,
        m("button.link", { type: "button", disabled: this.submitting, onclick: () => this.restart() }, "Back"),
      ]),
    ];
  }

  private methodLink(method: string, label: string, waitingLabel: string): m.Children {
    return m("button.link", { type: "button", disabled: this.submitting, onclick: () => this.askMethod(method, waitingLabel) }, label);
  }

  /** The server's answer to a step with a challenge: the challenge as it now is. */
  private challenged(output: LoginApiOutput) {
    this.challenge = output.challenge;
    this.method = output.method;
    this.sentTo = output.sentTo;
    this.alternative = output.alternative;
    this.error = output.error;
  }

  private askMethod(method: string, waitingLabel: string) {
    this.submitting = true;
    this.waitingLabel = waitingLabel;
    this.error = undefined;
    ApiEndpoints.LoginMethod.call({ challenge: this.challenge!, method })
      .then((output) => {
        if (output.challenge) {
          const switched = output.method !== this.method;
          this.challenged(output);
          if (switched) this.code = "";
        } else {
          this.restart(output.error);
        }
      })
      .catch((err: Error) => {
        this.error = err.message;
      })
      .finally(() => (this.submitting = false));
  }

  private restart(error?: string) {
    this.challenge = this.method = this.sentTo = this.alternative = undefined;
    this.code = "";
    this.error = error;
  }

  private submit(e: Event, waitingLabel = "Logging in…") {
    e.preventDefault();
    this.submitting = true;
    this.waitingLabel = waitingLabel;
    this.error = undefined;
    ApiEndpoints.Login.call({ login: this.login, password: this.password })
      .then((output) => {
        if (output.challenge) {
          this.challenged(output);
          this.code = "";
          return;
        }
        this.restart();
        return this.finish(output);
      })
      .catch((err: Error) => {
        this.error = err.message;
      })
      .finally(() => (this.submitting = false));
  }

  private submitCode(e: Event) {
    e.preventDefault();
    this.submitting = true;
    this.waitingLabel = "Checking…";
    this.error = undefined;
    ApiEndpoints.LoginCode.call({ challenge: this.challenge!, code: this.code })
      .then((output) => {
        // A wrong code keeps the challenge; any other refusal ends it: password again.
        if (!output.ok && output.challenge) {
          this.challenged(output);
          return;
        }
        if (!output.ok) {
          this.restart(output.error);
          return;
        }
        return this.finish(output);
      })
      .catch((err: Error) => {
        this.error = err.message;
      })
      .finally(() => (this.submitting = false));
  }

  private async finish(output: LoginApiOutput) {
    if (!output.ok || !output.username) {
      this.error = output.error ?? "Invalid login or password.";
      return;
    }
    if (!(await cookieKept())) {
      this.error = "Logged in, but the browser didn't keep the session: this server sends it over HTTPS only. "
        + "Use https://, or set \"secureCookie\" to false in the server's config.json.";
      return;
    }
    this.password = "";
    Auth.setLoggedIn(output.username, output.admin);
    Routing.goToScreen(Endpoints.HOME);
  }
}

/**
 * Whether the session cookie just set is sent back: a secure cookie is dropped over plain HTTP,
 * which the browser doesn't report. Assumed kept if the server can't tell.
 */
async function cookieKept(): Promise<boolean> {
  try {
    return (await ApiEndpoints.Session.call({})).authenticated;
  } catch {
    return true;
  }
}
