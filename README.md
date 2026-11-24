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

Each release's jar is on the [Releases](https://github.com/pieroxy/nullbird-notes/releases) page,
and its Docker image is `ghcr.io/pieroxy/nullbird-notes`. To build it yourself instead, with Java
17+, Maven, Node.js and git:

```
mvn package
```

Produces `src/notes-core/target/notes-core-<version>.jar`.

## Quick start

With Java 17+ and a release's jar:

```
mkdir notes
java -jar notes-core-<version>.jar hash-password
```

Write `notes/credentials.json` from `credentials.example.json`, with the printed object as
`password`. Then:

```
java -jar notes-core-<version>.jar notes
```

and open http://localhost:8080. The notes are stored in `notes/data`. Chrome and Firefox accept the
session cookie, HTTPS-only by default, on `localhost`; for other browsers, or to reach the server
over plain HTTP from another machine, write `{"secureCookie": false}` in `notes/config.json`.

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
  - `secureCookie`: the session cookie travels over HTTPS only; defaults to `true`. Set it to `false`
    to serve over plain HTTP, or nobody can log in.
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

The image is tagged with each version, and `latest`, for amd64 and arm64 (ARM servers, a Raspberry Pi
with a 64-bit OS).

```
docker run --rm -it ghcr.io/pieroxy/nullbird-notes hash-password
```

`/config` is the config directory. The container runs as uid 10001, which must own it. Without HTTPS
in front of it, `notes/config.json` needs `{"secureCookie": false}`.

```
mkdir notes          # then write notes/credentials.json
chown -R 10001 notes
docker run -d --name notes --restart unless-stopped -p 8080:8080 -v "$PWD/notes:/config" ghcr.io/pieroxy/nullbird-notes
```

To build the image yourself: `mvn clean package` (the image takes the only jar in `target`), then
`docker build -t nullbird-notes .` from the repository's root.

## Upgrade

Back up, replace the jar (or `docker pull` the image and recreate the container), restart. The
data is migrated on startup if needed.

## Backups

Copying the files while the server runs can capture a note half written: either stop the server
while copying (a few seconds; devices retry on their own), or copy from a filesystem snapshot
(ZFS, btrfs, LVM): the server reads it like data left by a power cut, which is always consistent.

Copy `<configDir>` and `<dataFolder>`. `<dataFolder>/tomcat` and `<dataFolder>/webapp-ui` are
recreated on startup, `<dataFolder>/logs` is only logs, `<dataFolder>/imports` only files waiting to
be imported (emptied on startup).

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

## Export and import

In My Account > Your notes, each user can export their notes as a .zip file laid out as
Simplenote's export, and import one: this app's or Simplenote's, the .zip file or the
`source/notes.json` in it (up to 64 MB, 100,000 notes).

The import first shows what it would do, then waits for the user to run it or cancel (for 15
minutes at most). It only adds notes, with their dates, tags, trashed state and options (pinned, Markdown, fixed-width
font, no line wrap: each a field of the note set to true, as Simplenote's `pinned` and `markdown`).
It skips:

- a note the user already has: same ID (whatever its text), or same text (whatever its ID);
- a note larger than `maxNoteSizeMb`.

Simplenote's notes get new IDs; this app's keep theirs, so importing the same file twice imports
nothing the second time. The logs get a line per note of the file, with its ID there: imported
(INFO), skipped as already there along with the existing note's ID (INFO), or skipped as too large
(WARNING).

## Appearance and fonts

In My Account > Appearance, each user chooses the text size, the spacing, and the fonts: for the
interface, the notes, and fixed-width text. These are kept per device, not per account: a phone and
a desktop each have their own.

The fonts to choose from are those an admin installs, in Administration > Fonts: one to four files
per font (regular, bold, italic, bold italic), WOFF2, WOFF, TTF or OTF, 5 MB each at most. A
variable font covers every weight in one file, so a regular file and an italic one are enough. The
browser fakes the styles missing, not as well. Install only fonts whose licence allows it: their
files are served to every logged-in user.

A font chosen is downloaded to the device at once, to work offline. A font deleted, or changed, is
dropped from a device the next time it's online: it goes back to the default font.

The files are in `<dataFolder>/fonts`, with their list in `fonts.json`.

The emoji button, by the note's, opens a picker with a search. By default it shows on computers
only: phones and tablets have emojis on their keyboard. Appearance has it shown or hidden instead.
Its list comes from Unicode, in `src/notes-webapp/src/ts/utils/EmojiData.ts`; to update it for a
new Unicode release, see `src/notes-webapp/scripts/emoji-data.mjs`.

## Browsers

Chrome and Edge 111, Firefox 119, Safari 16.4 (macOS and iOS), or later.

An older browser gets a message saying so instead of the app. On iOS, the version of Safari is the
version of iOS: 16.4 or later. Other browsers on iOS (Chrome, Firefox...) use Safari's engine, so
the same applies to them.

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

- Set `trustedProxies` to the proxy's address, and `secureCookie` to `false` if it serves plain HTTP.
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
