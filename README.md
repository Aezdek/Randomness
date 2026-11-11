# Hush

Hush is a small private photo-sharing app, in the style of Instagram, that you run on your own server for the
people you invite. It has a feed, stories, reels, direct messages and video calls. Everything people post or send
is encrypted on their own device before it leaves it, so the server stores things it cannot read.

It was built for two people and is meant for a handful: a server takes ten members by default and fifty at most.
There is a web app, which also installs to a phone's home screen, and an Android app.

## What's in it

Posts hold up to twenty photos or videos, with cropping, filters, captions, mentions, hashtags and tags. People can
like, comment, save, archive and pin posts. Stories can be photos, video or text, with polls, questions and the
other usual stickers, a close friends list, highlights and replies that land in your messages. Reels play in a
full-screen vertical feed.

Messages work one to one and in groups: photos, video, voice notes, view-once and disappearing messages, reactions,
replies, forwarding, polls, editing and unsending, read receipts you can turn off, nicknames, pinned messages and
chat themes. Calls are video or audio, one to one or in a group, and go directly between the people in them when
the network allows it.

Around that there is search, an activity page, private accounts with follow requests, blocking, an app lock, a way
to check that you are really talking to who you think you are, and an admin panel for whoever runs the server.

## Run your own

You need a Linux machine with Docker and its compose plugin, and a way for people to reach it: a Cloudflare Tunnel
on that machine, or a reverse proxy you already run (Caddy, nginx, Traefik). A home server is enough. Hush keeps
itself to about half a gigabyte of memory.

```bash
curl -fsSLO https://github.com/Aezdek/Randomness/releases/download/hush-latest/install.sh
```

```bash
sudo bash install.sh
```

The installer asks for the address people will use, how the server is reached, and where to put things. It shows
what it is about to do and waits for a yes before changing anything. If you would rather have menus than
questions, add `--gui`: on a desktop you get windows, and in a terminal (over SSH, say) you get full-screen menus.
To run it without questions, add `--yes` and give the answers as variables, which `bash install.sh --help` lists.

Near the end it asks you to choose a server password. That password encrypts the server's database, and you will
need it again if you ever restore a backup or move to another machine, so keep it somewhere safe. Then it prints
the first invite code. Whoever signs up with that code becomes the admin, and invites everyone else from the admin
panel.

For the Android app, open `/download` on your server from the phone. The app updates itself from your server
afterwards.

To update the server, run `sudo ./update.sh` in the folder Hush was installed in, or use the Server tab of the
admin panel. Nothing updates on its own: the update shows what changed and asks first, and it goes back to the
previous version by itself if the new one fails to start.

[`deploy/RUNBOOK.md`](deploy/RUNBOOK.md) covers the rest: backups, restoring, the admin panel's server controls
and what to do when something is wrong.

## How it is put together

```
 Android app ─┐  HTTPS and WebSocket               ┌─ SQLite database (encrypted)
 Web app ─────┼─► your address ─► Hush server ──────┼─ media files (encrypted by the sender)
              │   (tunnel or reverse proxy)         └─ push notifications, call relay credentials
 Calls go from device to device, or through a relay that only ever sees encrypted traffic.
```

| Folder | What is in it |
| --- | --- |
| `server/` | The server (Kotlin, Ktor, SQLite). It stores ciphertext, decides who may fetch what, and passes call signals along |
| `web/` | The web app (TypeScript, React) |
| `android/` | The Android app (Kotlin, Compose). It is its own Gradle build, and includes the root one |
| `shared/` | Code the server and the Android app share: the API types, the cryptography, and the Android app's non-UI layer |
| `deploy/` | The installer, the update, backup and restore scripts, and their tests |
| `crypto-vectors/` | Test vectors that keep the Kotlin and TypeScript cryptography producing identical bytes |

## Security

Posts, comments, stories, messages, reactions, profiles and media are encrypted on the device that makes them.
Each item has its own key, and that key is sealed separately for each person allowed to see the item. Every item
is signed by its author. Your private keys are kept on the server only in a form sealed with a key derived from
your password, which never leaves your device, so signing in on a new phone brings everything back and the server
still cannot read any of it. Call setup is encrypted the same way, so a relay in the middle only carries
ciphertext.

The server does see who is a member, who follows and messages whom, when, and how large things are. It needs that
to deliver anything. The database that holds it is encrypted on disk under the server password, so a stolen disk
or a copy of a backup is unreadable without that password. The admin panel shows the admin this same information
about members, and never content.

Some things to know before you rely on it:

- The web app is only as trustworthy as whatever serves it. Your server sends the JavaScript, and a proxy or
  tunnel in front of it sees the connection. Someone who controlled either could serve a page that steals a
  password. The Android app does not have this problem: it only accepts updates signed with a key that exists
  on your server and nowhere else.
- There is no forward secrecy. Keys are long-lived, because the history is kept on the server and has to open on
  a new device.
- Removing a follower or a member stops them fetching anything more. What their device already downloaded stays
  on their device.
- Changing your password re-seals the same keys. If you think the server's data was copied while you used the
  old password, use Reset keys in the security settings, which gives you new ones.
- Passwords need to be good. Anyone holding a copy of the server's data can try guesses against it for as long
  as they like.
- Disappearing and unsent messages are deleted from the server, but can remain in its backups for up to thirty
  days.

## Building it

The server and the shared code need JDK 21. The Android app needs the Android SDK.

```bash
./gradlew :shared:crypto:test :server:test :shared:client:test
```

```bash
cd web && npm ci && npm run typecheck && npm test
```

```bash
bash deploy/tests/run.sh
```

```bash
./gradlew -p android :app:testDebugUnitTest :app:assembleDebug
```

The end-to-end test in `web/e2e` drives three browsers against a real server; `.github/workflows/hush.yml` shows
how it is started. The same workflow builds the server image and the Android app on every push to `main` and
publishes them as the `hush-latest` release, which is what `install.sh` and `update.sh` download. It works the same
in a fork: the installer it publishes points at the repository it was built in.

An Android build made with `HUSH_SERVER_URL` set talks to that one server. A build made without it asks for the
server's address the first time it is opened.
