# nullbird notes

A simple, self-hosted note-taking application. Its sync handles concurrent edits and always shows
its state. License: GPLv2.

## Layout

| Path | What |
|---|---|
| `src/notes-core` | Java backend: embedded Tomcat, JSON API, JSON files on disk. Builds the runnable jar. |
| `src/notes-webapp` | Web UI: TypeScript + mithril. Bundled into the jar. |
| `src/notes-apple` | iOS / macOS app (Swift). Not started. |
| `src/notes-android` | Android app (Java). Not started. |

The web UI's API types and calls (`src/notes-webapp/src/ts/auto/`) are generated from the
backend's Java classes during the Maven build: never edit them.

## Build

Requires Java 17+, Maven, Node.js and git.

```
mvn package
```

Produces `src/notes-core/target/notes-core-<version>.jar`.

## Run

```
java -jar notes-core-<version>.jar <configDir>
```

`<configDir>` holds:

- `config.json` (optional, see `config.example.json`):
  - `httpPort`: defaults to 8080.
  - `address`: the interface to listen on; blank for all.
  - `dataFolder`: relative to `<configDir>`; defaults to `data`.
  - `trustedProxies`: IPs or CIDR ranges of reverse proxies whose `X-Forwarded-For` is believed.
  - `secureCookie`: set to `true` when served over HTTPS.
- `credentials.json` (required, see `credentials.example.json`): one entry per user. It is re-read
  when it changes, so there's no need to restart after adding or removing a user. Usernames are
  lowercase letters, digits, `.`, `_` and `-`.

To get the `password` object for a user:

```
java -jar notes-core-<version>.jar hash-password
```
