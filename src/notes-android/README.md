# Android app

The native Android app (Java): a webview around the web app, which it downloads from the user's
server and keeps on the device. See `docs/native-apps.md` for the design.

## Build

With JDK 17 and the Android SDK (platform 34), from this folder:

```
./gradlew testDebugUnitTest assembleDebug
```

Gradle needs to know where the SDK is: `ANDROID_HOME`, or `sdk.dir` in `local.properties` (not
committed). The APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Code

All in `app/src/main/java/net/pieroxy/notes/android/`:

- `SetupActivity`: asks for the server's address (`ServerAddress`), downloads its web app.
- `MainActivity`: the webview. It answers the manifest's files from the device (`Routes`), adds the
  bridge (`Bridge`), and opens anything else in the browser.
- `WebApp`: the copy in use, and its updates: downloaded in the background, used at the next page
  load.
- `WebAppStore`: the copies on the device, each file checked against the manifest (`AppManifest`).
- `SettingsActivity`: the server, and changing it. Opened from a launcher shortcut.

Only `MainActivity`, `SetupActivity`, `SettingsActivity` and `NotesApp` use Android: the rest is plain
Java, tested by the unit tests in `app/src/test`.

## Testing against a local server

The app only talks HTTPS, with a certificate Android trusts. For a server on your machine (reached as
`10.0.2.2` from the emulator), put a TLS proxy with your own CA in front of it, and trust that CA in
debug builds only, with files you don't commit:

- `app/src/debug/res/raw/test_ca.pem`: the CA's certificate;
- `app/src/debug/res/xml/test_network.xml`: a `network-security-config` whose `debug-overrides`
  trust `system` and `@raw/test_ca`;
- `app/src/debug/AndroidManifest.xml`: an `<application android:networkSecurityConfig="@xml/test_network"/>`.
