import m from "mithril";
import { ApiEndpoints } from "../../auto/ApiEndpoints";
import { Auth } from "../../utils/Auth";
import { Endpoints } from "../../utils/navigation/Endpoints";
import { Routing } from "../../utils/navigation/Routing";
import { AbstractPage } from "./AbstractPage";

export class LoginPage extends AbstractPage {
  private login = "";
  private password = "";
  private submitting = false;
  private error: string | undefined;

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
      ]));
  }

  private submit(e: Event) {
    e.preventDefault();
    this.submitting = true;
    this.error = undefined;
    ApiEndpoints.Login.call({ login: this.login, password: this.password })
      .then((output) => {
        if (output.ok) {
          Auth.setLoggedIn(output.username);
          Routing.goToScreen(Endpoints.HOME);
        } else {
          this.error = "Invalid login or password.";
        }
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
