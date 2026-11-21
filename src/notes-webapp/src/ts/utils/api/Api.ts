import m from "mithril";
import { BUILD_ID } from "../../auto/BuildId";
import { Auth } from "../Auth";

export interface ApiResponse<T> {
  ok: boolean;
  error?: string;
  result?: T;
}

/** What callers of the generated ApiEndpoints can pass. */
export interface CallOptions {
  /** Aborting it fails the call as UNREACHABLE: how callers put a timeout on it. */
  signal?: AbortSignal;
}

export interface ApiCallOptions<I> extends CallOptions {
  method: "GET" | "POST";
  endpoint: string;
  body: I;
}

export enum ApiErrorKind {
  /** No response at all: offline, DNS failure, server down, or aborted (timed out). */
  UNREACHABLE,
  /** A response that isn't the API's JSON, e.g. a reverse proxy's error page. */
  BAD_RESPONSE,
  /** The API answered with an error. */
  API,
  /** The server runs another build: this page must be reloaded (see Api.outdated). */
  OUTDATED,
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
  /** Set once the server refused a call from this build: every page then offers to reload. */
  static outdated = false;

  /** @throws ApiError. A 401 also drops the client-side session (see Auth). */
  static async call<I, O>(options: ApiCallOptions<I>): Promise<O> {
    const url = "/api/" + options.endpoint;
    // The session travels as an HttpOnly cookie, sent by the browser on these same-origin calls.
    let response: Response;
    try {
      response = options.method === "GET"
        ? await fetch(url + "?input=" + encodeURIComponent(JSON.stringify(options.body)), {
            headers: { "X-Notes-Build": BUILD_ID },
            signal: options.signal,
          })
        : await fetch(url, {
            method: "POST",
            headers: { "Content-Type": "application/json", "X-Notes-Build": BUILD_ID },
            body: JSON.stringify(options.body),
            signal: options.signal,
          });
    } catch {
      throw new ApiError(ApiErrorKind.UNREACHABLE, options.signal?.aborted ? "The server took too long to answer." : "Cannot reach the server.");
    }
    return Api.result<O>(response, options.endpoint, options.signal);
  }

  /**
   * POSTs {@code file} as is to {@code path} (not an API endpoint: /import), which answers as the
   * API does.
   *
   * @throws ApiError, as call does.
   */
  static async upload<O>(path: string, file: Blob, signal?: AbortSignal): Promise<O> {
    let response: Response;
    try {
      response = await fetch(path, {
        method: "POST",
        headers: { "Content-Type": "application/octet-stream", "X-Notes-Build": BUILD_ID },
        body: file,
        signal,
      });
    } catch {
      throw new ApiError(ApiErrorKind.UNREACHABLE, "Cannot reach the server.");
    }
    return Api.result<O>(response, path, signal);
  }

  private static async result<O>(response: Response, endpoint: string, signal?: AbortSignal): Promise<O> {
    let payload: ApiResponse<O>;
    try {
      payload = await response.json();
    } catch {
      if (signal?.aborted) throw new ApiError(ApiErrorKind.UNREACHABLE, "The server took too long to answer.");
      throw new ApiError(ApiErrorKind.BAD_RESPONSE, "Unexpected response from the server (HTTP " + response.status + ").", response.status);
    }

    if (!payload.ok) {
      if (response.status === 409) {
        Api.outdated = true;
        m.redraw();
        throw new ApiError(ApiErrorKind.OUTDATED, payload.error ?? "This page is out of date: reload it.", response.status);
      }
      if (response.status === 401) {
        Auth.clearSession();
        m.redraw();
      }
      throw new ApiError(ApiErrorKind.API, payload.error ?? ("API call to " + endpoint + " failed."), response.status);
    }
    return payload.result as O;
  }
}
