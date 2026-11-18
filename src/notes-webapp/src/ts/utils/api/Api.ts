import m from "mithril";
import { Auth } from "../Auth";

export interface ApiResponse<T> {
  ok: boolean;
  error?: string;
  result?: T;
}

export interface ApiCallOptions<I> {
  method: "GET" | "POST";
  endpoint: string;
  body: I;
}

export enum ApiErrorKind {
  /** No response at all: offline, DNS failure, server down. */
  UNREACHABLE,
  /** A response that isn't the API's JSON, e.g. a reverse proxy's error page. */
  BAD_RESPONSE,
  /** The API answered with an error. */
  API,
}

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  /** Undefined when UNREACHABLE. */
  readonly status: number | undefined;

  constructor(kind: ApiErrorKind, message: string, status?: number) {
    super(message);
    this.kind = kind;
    this.status = status;
  }
}

export class Api {
  /** @throws ApiError. A 401 also drops the client-side session (see Auth). */
  static async call<I, O>(options: ApiCallOptions<I>): Promise<O> {
    const url = "/api/" + options.endpoint;
    // The session travels as an HttpOnly cookie, sent by the browser on these same-origin calls.
    let response: Response;
    try {
      response = options.method === "GET"
        ? await fetch(url + "?input=" + encodeURIComponent(JSON.stringify(options.body)))
        : await fetch(url, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(options.body),
          });
    } catch {
      throw new ApiError(ApiErrorKind.UNREACHABLE, "Cannot reach the server.");
    }

    let payload: ApiResponse<O>;
    try {
      payload = await response.json();
    } catch {
      throw new ApiError(ApiErrorKind.BAD_RESPONSE, "Unexpected response from the server (HTTP " + response.status + ").", response.status);
    }

    if (!payload.ok) {
      if (response.status === 401) {
        Auth.clearSession();
        m.redraw();
      }
      throw new ApiError(ApiErrorKind.API, payload.error ?? ("API call to " + options.endpoint + " failed."), response.status);
    }
    return payload.result as O;
  }
}
