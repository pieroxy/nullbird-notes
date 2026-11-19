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

The web UI's icons are copied from [Lucide](https://lucide.dev) (ISC and MIT licenses, see
`src/notes-webapp/src/licenses/lucide.txt`).

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
  - `keepLogFiles`: days of logs to keep; defaults to 30, `0` keeps them forever.
  - `maxNoteSizeMb`: the largest note; defaults to 10. Larger requests are refused with HTTP 413.
- `credentials.json` (required, see `credentials.example.json`): one entry per user. It is re-read
  when it changes, so there's no need to restart after adding or removing a user. Usernames are
  lowercase letters, digits, `.`, `_` and `-`.

To get the `password` object for a user:

```
java -jar notes-core-<version>.jar hash-password
```

A login lasts until 30 days without use. Sessions survive restarts: they're kept in
`<dataFolder>/sessions.json`, which holds only hashes of the session cookies. Deleting it logs
everyone out.

`<dataFolder>/format.json` holds the version of the data's format. On startup, data in an older
format is migrated; data in a newer one (written by a later version) stops the server: going back
to an older version needs a backup made before the upgrade.

## Behind a reverse proxy

- Set `trustedProxies` to the proxy's address, and `secureCookie` to `true` if it serves HTTPS.
- Over HTTPS, have the proxy send `Strict-Transport-Security`: the server can't know the site is
  served over HTTPS, so it doesn't.
- Clients wait for changes with requests held open up to 25 s: the proxy's read timeout must be
  longer (nginx's default of 60 s is fine).
- The proxy's own request body limit must allow twice `maxNoteSizeMb`.

## Logs

In `<dataFolder>/logs`, rotated at midnight, with older days compressed to lz4 and deleted after
`keepLogFiles` days:

- `log.txt`: everything logged (application, Tomcat). Archives are `log.txt.1.lz4` (yesterday),
  `log.txt.2.lz4`, etc.
- `access-yyyy-MM-dd.log`: one line per HTTP request, in the Combined Log Format followed by the
  response time. Query strings of `/api/` calls are left out.

Read an archive with `lz4 -dc log.txt.1.lz4`.
