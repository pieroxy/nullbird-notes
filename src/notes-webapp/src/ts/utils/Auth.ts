import m from "mithril";
import { ApiEndpoints } from "../auto/ApiEndpoints";
import { Api } from "./api/Api";

export enum AuthStatus {
  LOGGED_IN,
  NO_SESSION,
  CHECKING,
  /** SessionApi couldn't be reached; retried every RETRY_DELAY_MS. */
  UNREACHABLE,
  /** The server runs another build (see Api.outdated): nothing more is asked of it. */
  OUTDATED,
}

const RETRY_DELAY_MS = 5000;

/**
 * Client-side session state. The session itself is an HttpOnly cookie this code can't see: on
 * page load, SessionApi tells whether it's (still) valid.
 */
export class Auth {
  /** Undefined until SessionApi has answered; null when not logged in. */
  private static username: string | null | undefined;
  private static checkInProgress = false;
  private static unreachable = false;

  static getStatus(): AuthStatus {
    if (typeof Auth.username === "string") return AuthStatus.LOGGED_IN;
    if (Auth.username === null) return AuthStatus.NO_SESSION;
    if (Api.outdated) return AuthStatus.OUTDATED;
    if (!Auth.checkInProgress && !Auth.unreachable) Auth.check();
    return Auth.unreachable ? AuthStatus.UNREACHABLE : AuthStatus.CHECKING;
  }

  /** Null unless logged in. */
  static getUsername(): string | null {
    return Auth.username ?? null;
  }

  static setLoggedIn(username: string) {
    Auth.username = username;
  }

  static clearSession() {
    Auth.username = null;
  }

  /** Logged out client-side even if the server can't be reached. */
  static async logout(): Promise<void> {
    try {
      await ApiEndpoints.Logout.call({});
    } catch {
      // The server-side session then lives on until it expires.
    }
    Auth.clearSession();
  }

  private static check() {
    Auth.checkInProgress = true;
    ApiEndpoints.Session.call({})
      .then((output) => {
        Auth.username = output.authenticated ? output.username ?? null : null;
        Auth.unreachable = false;
      })
      .catch(() => {
        if (Api.outdated) return;
        Auth.unreachable = true;
        setTimeout(() => {
          Auth.unreachable = false;
          m.redraw();
        }, RETRY_DELAY_MS);
      })
      .finally(() => {
        Auth.checkInProgress = false;
        m.redraw();
      });
  }
}
