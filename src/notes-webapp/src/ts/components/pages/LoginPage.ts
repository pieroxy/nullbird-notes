import m from "mithril";
import { ApiEndpoints } from "../../auto/ApiEndpoints";
import { Auth } from "../../utils/Auth";
import { Endpoints } from "../../utils/navigation/Endpoints";
import { Routing } from "../../utils/navigation/Routing";
import { AbstractPage } from "./AbstractPage";

/** Fetched once: logging out recreates the page. */
let version: string | undefined;

export class LoginPage extends AbstractPage {
  private login = "";
  private password = "";
  private submitting = false;
  private error: string | undefined;

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
      m("form", { onsubmit: (e: Event) => this.submit(e) }, [
        m("h1", "Notes"),
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
        m("button", { type: "submit", disabled: this.submitting }, "Log in"),
        this.error ? m(".errormessage", this.error) : null,
        version ? m("p.loginversion", "Version " + version) : null,
      ]));
  }

  private submit(e: Event) {
    e.preventDefault();
    this.submitting = true;
    this.error = undefined;
    ApiEndpoints.Login.call({ login: this.login, password: this.password })
      .then(async (output) => {
        if (!output.ok || !output.username) {
          this.error = "Invalid login or password.";
          return;
        }
        if (!(await cookieKept())) {
          this.error = "Logged in, but the browser didn't keep the session: this server sends it over HTTPS only. "
            + "Use https://, or set \"secureCookie\" to false in the server's config.json.";
          return;
        }
        Auth.setLoggedIn(output.username, output.admin);
        Routing.goToScreen(Endpoints.HOME);
      })
      .catch((err: Error) => {
        this.error = err.message;
      })
      .finally(() => {
        this.submitting = false;
        m.redraw();
      });
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
