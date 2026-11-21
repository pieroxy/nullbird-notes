import m from "mithril";
import { ApiEndpoints } from "../auto/ApiEndpoints";
import { Api, ApiError, ApiErrorKind } from "./api/Api";

export enum AuthStatus {
  LOGGED_IN,
  NO_SESSION,
  CHECKING,
  /** SessionApi couldn't be reached, and no user's notes can be opened without it; retried every RETRY_DELAY_MS. */
  UNREACHABLE,
  /** The server runs another build (see Api.outdated): nothing more is asked of it. */
  OUTDATED,
}

const RETRY_DELAY_MS = 5000;
/** Past it, the server counts as unreachable: a server that doesn't answer mustn't keep the notes away. */
const CHECK_TIMEOUT_MS = 5000;
/** Who last logged in on this device: their notes open without the server. */
const LAST_USER_KEY = "notes.lastUser";

function readLastUser(): string | null {
  try {
    return localStorage.getItem(LAST_USER_KEY);
  } catch {
    return null;
  }
}

function writeLastUser(username: string | null) {
  try {
    if (username === null) localStorage.removeItem(LAST_USER_KEY);
    else localStorage.setItem(LAST_USER_KEY, username);
  } catch {
    // Private mode or storage disabled: no offline start, that's all.
  }
}

/**
 * Client-side session state. The session itself is an HttpOnly cookie this code can't see: on
 * page load, SessionApi tells whether it's (still) valid.
 * <p>
 * If the server can't be reached, the user who last logged in on this device is taken as logged
 * in, unverified (see {@link Auth.isOffline}): their notes are on the device anyway, and the sync
 * catches up once the server is back. SessionApi is asked again every RETRY_DELAY_MS until it
 * answers; if it then says the session is gone, the user is sent to the login page as usual, their
 * unsynced edits kept on the device. Only logging out forgets the user.
 */
export class Auth {
  /** Undefined until SessionApi has answered; null when not logged in. */
  private static username: string | null | undefined;
  /** As the server said at login or at the last session check. */
  private static admin = false;
  /** Logged in as the last user without the server's word for it. */
  private static offline = false;
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

  /**
   * Whether to show the Administration pages. Only the UI: the server checks for itself whatever
   * they ask of it. Never while offline: the server's word is needed.
   */
  static isAdmin(): boolean {
    return typeof Auth.username === "string" && Auth.admin && !Auth.offline;
  }

  /** Whether the user was let in without the server, which couldn't be reached. */
  static isOffline(): boolean {
    return typeof Auth.username === "string" && Auth.offline;
  }

  static setLoggedIn(username: string, admin: boolean) {
    Auth.username = username;
    Auth.admin = admin;
    Auth.offline = false;
    writeLastUser(username);
  }

  /** The server says there's no session: back to the login page. The device keeps the user's notes, and offline start. */
  static clearSession() {
    Auth.username = null;
    Auth.admin = false;
    Auth.offline = false;
  }

  /** Logged out client-side even if the server can't be reached. */
  static async logout(): Promise<void> {
    try {
      await ApiEndpoints.Logout.call({});
    } catch {
      // The server-side session then lives on until it expires.
    }
    writeLastUser(null);
    Auth.clearSession();
  }

  private static check() {
    Auth.checkInProgress = true;
    const timeout = new AbortController();
    const timer = setTimeout(() => timeout.abort(), CHECK_TIMEOUT_MS);
    ApiEndpoints.Session.call({}, { signal: timeout.signal })
      .then((output) => {
        Auth.unreachable = false;
        if (!output.authenticated || !output.username) {
          Auth.clearSession();
          return;
        }
        Auth.setLoggedIn(output.username, output.admin);
      })
      .catch((err: unknown) => {
        if (Api.outdated) return;
        const lastUser = readLastUser();
        const cantReach = err instanceof ApiError && (err.kind === ApiErrorKind.UNREACHABLE || err.kind === ApiErrorKind.BAD_RESPONSE);
        if (cantReach && lastUser && Auth.username === undefined) {
          Auth.username = lastUser;
          Auth.admin = false;
          Auth.offline = true;
        }
        if (Auth.offline) {
          setTimeout(() => Auth.check(), RETRY_DELAY_MS);
          return;
        }
        Auth.unreachable = true;
        setTimeout(() => {
          Auth.unreachable = false;
          m.redraw();
        }, RETRY_DELAY_MS);
      })
      .finally(() => {
        clearTimeout(timer);
        Auth.checkInProgress = false;
        m.redraw();
      });
  }
}
