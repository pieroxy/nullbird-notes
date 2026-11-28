/**
 * Where the web app runs: a browser, or one of the native apps, which download it from the server
 * (see AppManifest on the server) and run it in a webview. A native app says so by defining
 * {@code window.NullbirdNative} before the page's script runs; without it, the page is in a browser.
 * <p>
 * The page asks what it can do here (canExport…), never which platform it's on: when a native app
 * learns to do something, only its flag changes.
 */
export interface NativeInfo {
  platform: "ios" | "android";
  /** The server's address, as https://host[:port]: the page's own may be the app's (nullbird-notes://app on iOS). */
  serverUrl: string;
}

interface NativeWindow {
  NullbirdNative?: unknown;
}

/** What the native app said, if it said something valid: anything else is taken for a browser. */
function readNative(): NativeInfo | undefined {
  const said = (window as NativeWindow).NullbirdNative;
  if (!said || typeof said !== "object") return undefined;
  const { platform, serverUrl } = said as Partial<NativeInfo>;
  if (platform !== "ios" && platform !== "android") return undefined;
  if (typeof serverUrl !== "string" || !/^https:\/\/[^/?#]+$/.test(serverUrl)) return undefined;
  return { platform, serverUrl };
}

const native = readNative();

export const Platform = {
  /** In one of the native apps. */
  isNative(): boolean {
    return native !== undefined;
  },

  /** The server's address, as https://host[:port] (no trailing slash): for links and names shown to the user. */
  serverOrigin(): string {
    return native?.serverUrl ?? location.origin;
  },

  /** Whether the server is reached over HTTPS. */
  overHttps(): boolean {
    return Platform.serverOrigin().startsWith("https:");
  },

  /** Downloading the export: the apps don't handle downloads yet. */
  canExport(): boolean {
    return native === undefined;
  },

  /** Picking files to import: the apps don't handle file pickers yet. */
  canImport(): boolean {
    return native === undefined;
  },

  /** Picking font files to install, likewise. */
  canUploadFonts(): boolean {
    return native === undefined;
  },

  /** The service worker keeps the app on the device: the apps do that themselves. */
  usesServiceWorker(): boolean {
    return native === undefined;
  },
};
