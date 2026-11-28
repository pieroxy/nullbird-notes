# Native apps

The iOS app (Swift) and the Android app (Java) are thin containers around the web app. They exist to be
on the stores, and later to offer native features through a bridge. In v1, each app:

- asks for the server's address once;
- downloads the web app from that server and keeps it on the device, so it opens offline;
- runs it in a webview, and tells it that it's in a native app.

The server and the web app are already ready for this (`AppManifest`, `Platform.ts`). Apart from that,
the server knows nothing about the apps: they call the same API as a browser, with the same session
cookie.

## Decisions

| Topic | Decision |
|---|---|
| Stack | Fully native: Swift on iOS, Java on Android. No cross-platform framework. |
| Getting the web app | `GET /app/manifest.json`, then each file separately, checked against its SHA-256. No ZIP: iOS has no public API to unzip. |
| Loading on iOS | Custom scheme `nullbird-notes://app/`: files from the local copy, everything else proxied to the server. |
| Loading on Android | The server's own `https://` origin: files in the manifest answered from the local copy, everything else goes to the network untouched. |
| Updates | Downloaded in the background. The switch happens through the existing build check (409 → "Reload"). |
| Changing server | A native settings entry. |
| Plain `http://` | Never, local network included. |
| Export, import, installing fonts | Not in v1: hidden by the web app on native. |
| Service worker | Not registered on native: the apps keep the files themselves. |
| Bridge | `window.NullbirdNative = { platform, serverUrl }`, defined before the page's script runs. |

## The server side

### `GET /app/manifest.json`

Anonymous, since the login page is part of the download. It's built at startup from the web app the
server serves (`AppManifest.of`), so it always matches the files actually served.

```json
{
  "build": "…",
  "files": [
    { "path": "bundle-3f2a….js", "size": 812345, "sha256": "9c1e…", "type": "text/javascript" },
    { "path": "index.html", "size": 1234, "sha256": "ab12…", "type": "text/html;charset=utf-8" }
  ],
  "headers": {
    "Content-Security-Policy": "default-src 'none'; …",
    "X-Content-Type-Options": "nosniff",
    "…": "…"
  }
}
```

- `build`: the server's build ID (`BuildInfo.ID`), the same value the web app sends as `X-Notes-Build`.
  If the app already has this build, it downloads nothing.
- `files`: sorted by path. `path` is relative to the server's root, with forward slashes and no leading
  one; each file is fetched at `<server>/<path>`. `sha256` is lowercase hex. `type` is the
  `Content-Type` to serve it with.
- `headers`: the security headers (`SecurityHeadersFilter.HEADERS`), to send with every file the app
  serves. The CSP is static (no nonce), so a downloaded copy can reuse it as is. Don't hardcode it in
  the apps.

Left out: source maps, `sw.js`, and the published-note page (`published.html`, opened at `/p/…` in the
browser) along with the bundles only it uses.

### Nothing else changes

- The server doesn't check `Origin` or `Referer`, so requests proxied by the iOS app are accepted as is.
- The session cookie (`SameSite=Strict`, `Secure`) and the `X-Notes-Build` header work as in a browser.
- `/fonts/…` (installed fonts) and `/export` stay on the server. The apps never serve them from the
  local copy.

## The web app side: `Platform.ts`

The native app defines this object **before the page's script runs**:

```js
window.NullbirdNative = { platform: "ios", serverUrl: "https://notes.example.com" };
```

- `platform`: `"ios"` or `"android"`.
- `serverUrl`: exactly `https://host` or `https://host:port`, with no path and no trailing slash
  (`^https://[^/?#]+$`).

If the object is missing, or not exactly in that shape, the web app assumes it's in a browser. It's
read once, when the script loads.

The web app's code asks what it can do, never which platform it's on:

| Function | Browser | Native v1 | Used for |
|---|---|---|---|
| `isNative()` | false | true | |
| `serverOrigin()` | `location.origin` | `serverUrl` | Published note links, the authenticator app label |
| `overHttps()` | from `location` | from `serverUrl` | The HTTPS warning in Admin › Config |
| `canExport()` | true | false | Account › Your notes |
| `canImport()` | true | false | Account › Your notes (hidden entirely on native) |
| `canUploadFonts()` | true | false | Admin › Fonts: install and change hidden, delete kept |
| `usesServiceWorker()` | true | false | Service worker registration |

When an app learns to do one of these things, only its flag changes. Later, flags could be read from
the object (for example, `NullbirdNative.canExport`) rather than hardcoded to false.

## The app

The steps are the same on both platforms. Only the loading mechanism differs (see below).

### First launch

1. **Server address.** No server is saved, so a native screen asks for it. `notes.example.com` becomes
   `https://notes.example.com`; any path and trailing `/` are removed. `http://` is refused.
2. **Probe.** `GET <server>/app/manifest.json`. A valid manifest proves it's a Notes server, so there's
   no separate probe. Show distinct errors for: unreachable, certificate problem, not a Notes server
   (not JSON, or no `build` and `files`).
3. **Download.** Fetch each file in `files` into a temporary folder. Check its size and SHA-256; on a
   mismatch, discard everything and report an error.
4. **Install.** Move the temporary folder to `<app data>/webapp/<build>/`, then save the server address
   and the build. Saving last means a half-done install is never used.
5. **Launch.** Open the webview (see below). The login screen shows.

### Later launches

1. Open the installed copy right away, without waiting for the network, so the app starts offline.
2. In the background, fetch the manifest. If `build` changed, download the new copy as in steps 3–4,
   and keep it ready next to the current one.
3. **The switch.** After a server upgrade, the old copy's API calls get a 409. The web app shows its
   "out of date" banner, and "Reload" reloads the page. On that reload the app serves the new copy. If
   it isn't fully downloaded yet, the app finishes downloading it first. No bridge is needed.
4. Delete the previous copy once the new one has loaded.

The app also switches when it's opened and a newer copy is ready.

### Changing server

From the native settings entry, the app:

1. clears the webview's website data (local storage, IndexedDB, cookies), plus on iOS the proxy's own
   cookie store, because the notes on the device belong to the previous server's account. Unsynced
   changes are lost: say so before confirming;
2. deletes the downloaded copies;
3. starts over at the first launch.

### Leaving the web app

Any navigation outside the app's own origin (`nullbird-notes://app/` on iOS, the server's origin on
Android) opens in the system browser, never in the webview. This covers links in notes and published
note links (`/p/…`), which are on the server's origin but aren't part of the app. On Android, check this
case by path.

## iOS: `nullbird-notes://app/`

iOS doesn't let an app intercept `https://` requests, so the webview loads a custom scheme handled by a
`WKURLSchemeHandler`. Everything the page loads goes through that handler: one origin, so the
web app needs no change. Its paths are already relative (`/api/…`, `publicPath: '/'`), and its routes
are in the fragment (`#!/…`).

The handler:

- **`/` or `/index.html`, or a path in the manifest:** serve the file from the current copy, with its
  `type` as `Content-Type`, the manifest's `headers`, and `Cache-Control: no-cache`.
- **Anything else** (`/api/…`, `/fonts/…`): forward to `<server><path>?<query>` with a `URLSession`.
  Carry over the method, body, headers (including `X-Notes-Build`) and status. Stream the response, so
  the long poll keeps working. When the webview stops a task, cancel the matching request.
- **Cookies:** the `URLSession` keeps the session cookie in the app's own cookie store. The webview
  never sees it, and doesn't need to.

Bridge: a `WKUserScript` injected at `.atDocumentStart`, main frame only, that defines
`window.NullbirdNative`. The CSP doesn't apply to user scripts.

The origin `nullbird-notes://app` must never change: local storage and IndexedDB, including unsynced
changes, belong to it.

## Android: the server's own origin

Android can intercept `https://` requests, but its interception API can't read POST bodies, so a proxy
like iOS's won't work. The webview loads the server's real address instead:

- `shouldInterceptRequest`: for a `GET` to the server's origin whose path is `/`, `/index.html` or a
  path in the manifest, answer from the current copy with a `WebResourceResponse`: the manifest's
  `type`, its `headers`, and `Cache-Control: no-cache`. Return `null` for everything else, so it goes to
  the network untouched: the real cookie, POST bodies as sent, the long poll unaffected.
- Offline, the files still load, and the web app handles failing API calls as it does in a browser.

Bridge: `WebViewCompat.addDocumentStartJavaScript` from `androidx.webkit` (when
`WebViewFeature.DOCUMENT_START_SCRIPT` is supported), restricted to the server's origin. Injecting it
into `index.html` instead wouldn't work: the CSP (`script-src 'self'`) blocks inline scripts.

## To test first, on a device

Before building on this design:

- **iOS:** POST bodies (the API's JSON) reach the scheme handler whole. The raw uploads, `/import` and
  `/fonts/`, are hidden in v1.
- **iOS:** `nullbird-notes://app` counts as a secure context, so `crypto.subtle` works for the content
  hashes.
- **iOS:** local storage and IndexedDB survive an app restart on the custom scheme.
- **iOS:** the long poll streams through the handler, and stopping it cancels the upstream request.
- **Android:** document-start scripts are available on the target WebView versions.

If one of the iOS checks fails, the fallback is to load the web app straight from the server, as on
Android but without the local copy. That's simpler, but the app can't start offline.

## Security notes

- The hashes arrive over the same TLS connection as the files. They check that each file arrived
  whole, not that the server is genuine. Only a signature, with a key kept off the server, would
  protect against a compromised server. Out of scope: a compromised server can already serve a
  malicious web app to browsers.
- No `http://`, and no certificate exceptions.
- The webview shows only the app's own pages. Everything else opens in the system browser, which will
  matter once the bridge exposes real native calls.

## Later

- Export: on iOS, catch the navigation to `/export`, download it natively, and open the share sheet. On
  Android, use `DownloadManager` with the webview's cookie.
- Import and installing fonts: native file pickers (`WKUIDelegate` file input on iOS,
  `onShowFileChooser` on Android).
- Native features through the bridge: share extension, widgets, notifications and so on.
