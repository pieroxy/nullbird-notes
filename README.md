# nullbird notes

A simple, self-hosted note-taking application. Its sync handles concurrent edits and always shows
its state. License: GPLv2.

Edits made on several devices are always merged, never dropped. Where two devices changed the same
words, both versions are kept, one after the other, below the line
`[Auto-merged below: watch out for duplicated text]`.

## Layout

| Path | What |
|---|---|
| `src/notes-core` | Java backend: embedded Tomcat, JSON API, JSON files on disk. Builds the runnable jar. |
| `src/notes-webapp` | Web UI: TypeScript + mithril. Bundled into the jar. |
| `src/notes-apple` | iOS / macOS app (Swift). Not started. |
| `src/notes-android` | Android app (Java). Not started. |
| `deploy` | systemd unit. |

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

## Quick start

```
mkdir notes
java -jar notes-core-<version>.jar hash-password
```

Write `notes/credentials.json` from `credentials.example.json`, with the printed object as
`password`. Then:

```
java -jar notes-core-<version>.jar notes
```

and open http://localhost:8080. The notes are stored in `notes/data`.

## Run

```
java -jar notes-core-<version>.jar <configDir>
```

`<configDir>` holds:

- `config.json` (optional, see `config.example.json`). Admins can also edit it in Administration >
  Config, except `dataFolder`; either way, it's read at startup only, so changes apply at the next restart.
  - `httpPort`: defaults to 8080.
  - `address`: the interface to listen on; blank for all.
  - `dataFolder`: relative to `<configDir>`; defaults to `data`.
  - `trustedProxies`: IPs or CIDR ranges of reverse proxies whose `X-Forwarded-For` is believed.
  - `secureCookie`: set to `true` when served over HTTPS.
  - `keepLogFiles`: days of logs to keep; defaults to 30, `0` keeps them forever.
  - `maxNoteSizeMb`: the largest note; defaults to 10. Larger requests are refused with HTTP 413.
- `credentials.json` (required, see `credentials.example.json`): one entry per user. It is re-read
  when it changes, so there's no need to restart after adding or removing a user. Usernames are
  lowercase letters, digits, `.`, `_` and `-`. `"admin": true` gives a user the Administration
  pages, where admins add users and reset their passwords. Users added there also get `email`
  (optional) and `createdAt`.

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

The server stops cleanly on SIGTERM. Run one server per data folder.

### With systemd

```
useradd --system --home-dir /var/lib/nullbird-notes notes
mkdir -p /opt/nullbird-notes /var/lib/nullbird-notes
cp notes-core-<version>.jar /opt/nullbird-notes/notes-core.jar
cp deploy/nullbird-notes.service /etc/systemd/system/
```

Write `/var/lib/nullbird-notes/credentials.json` (and optionally `config.json`), then:

```
chown -R notes:notes /var/lib/nullbird-notes
systemctl enable --now nullbird-notes
```

### With Docker

The image packages the jar built by `mvn package`, from the repository's root:

```
docker build -t nullbird-notes .
docker run --rm -it nullbird-notes hash-password
```

`/config` is the config directory. The container runs as uid 10001, which must own it:

```
mkdir notes          # then write notes/credentials.json
chown -R 10001 notes
docker run -d --name notes --restart unless-stopped -p 8080:8080 -v "$PWD/notes:/config" nullbird-notes
```

## Upgrade

Back up, replace the jar (or rebuild the image), restart. The data is migrated on startup if
needed.

## Backups

Copying the files while the server runs can capture a note half written: either stop the server
while copying (a few seconds; devices retry on their own), or copy from a filesystem snapshot
(ZFS, btrfs, LVM): the server reads it like data left by a power cut, which is always consistent.

Copy `<configDir>` and `<dataFolder>`. `<dataFolder>/tomcat` and `<dataFolder>/webapp-ui` are
recreated on startup, `<dataFolder>/logs` is only logs.

```
systemctl stop nullbird-notes
tar -czf /backups/notes-$(date +%F).tar.gz -C /var/lib \
  --exclude=nullbird-notes/data/tomcat --exclude=nullbird-notes/data/webapp-ui --exclude=nullbird-notes/data/logs \
  nullbird-notes
systemctl start nullbird-notes
```

To restore, with the server stopped:

1. Put the backup in place.
2. Delete every `<dataFolder>/users/*/user.json`: devices then sync again from scratch.
3. Start the server.

Edits a device hadn't synced yet are kept and merged. Edits synced after the backup was made are
lost, on the devices too.

## Browsers

Chrome and Edge 111, Firefox 119, Safari 16.4 (macOS and iOS), or later.

## Install as an app, offline

Served over HTTPS (or on localhost), the web app can be installed: the install icon in Chrome or
Edge's address bar, "Install app" in Chrome's menu on Android, Share > "Add to Home Screen" on iOS
(which then asks to log in once more: the installed app has its own storage).

It then also opens without the server, on the notes of the user who last logged in on that device:
edits are kept on the device and synced once the server is back. If the session ended meanwhile
(expired, or logged out by a password change), the login page shows then, and the edits are sent
after logging in. Logging out removes the notes from the device, and offline start with them. Over
plain HTTP, browsers allow neither: the app works online only.

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
