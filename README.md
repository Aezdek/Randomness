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

What Hush does about the usual weak spots:

- **Passwords.** New passwords need at least 12 characters, and the ones attackers try first are refused: the 10,000
  most common, look-alikes such as `P@ssw0rd!2024`, two common words run together, runs like `abcd1234` or `qwerty`,
  and your own username. Someone who signs in with an older, weaker password is asked to change it.
- **Changing your password.** You choose: just the password, which keeps your keys and seals them under the new one,
  or the password and your keys together. The second gives you a new identity, and anything copied from the server
  before stops opening with your old password or old keys. Reset keys does the same while keeping your password.
- **Disappearing messages.** Any chat can be set so new messages disappear after 24 hours, 7 days or 90 days. They are
  deleted from the server and from every device in the chat, so nobody can read them afterwards, even with someone's
  password. The setting is shown to everyone in the chat.
- **Taking access away.** When you remove a follower, block someone, take someone out of a group or remove a member,
  their app deletes what it had of yours (posts, stories, chats and the decrypted photos and videos it kept), and the
  server stops sending them anything more. A removed member is signed out everywhere and their device is wiped.

Some things to know before you rely on it:

- **The web app trusts whatever serves it.** In a browser, the code that encrypts is downloaded from your server
  each time, through your tunnel or proxy. Someone who controlled either could send a changed page that copies your
  password as you type it. No website can prevent this; every end-to-end encrypted web app has the same limit. The
  Android app is installed once and only accepts updates signed with its key, so anyone between you and your server
  cannot change it. That key lives on your server, though, so someone who took over the server itself could sign a
  bad update. For anything sensitive, use the Android app and keep the server up to date.
- **Forward secrecy is per chat.** Your history is kept on the server so that it opens on a new device with your
  password, which means anyone who later gets your password and a copy of the server's data could read it too. Turn
  on disappearing messages for chats that shouldn't outlive the moment. The connection itself already uses forward
  secrecy (TLS 1.3), so recording the traffic gains nothing later.
- **What someone already saw.** Removing access deletes things from their app, but nothing can unsee a screenshot or
  stop a modified app from keeping a copy. A device that never comes back online keeps what it had until it does.
- **The server sees who, not what.** It knows who is a member, who follows and messages whom, when, and how large
  things are. It needs that to deliver anything.
- **Deleted things linger in backups.** Disappearing, deleted and unsent messages can remain in the server's
  encrypted backups for up to thirty days.
- **The admin panel's server controls ask for the server password**, which is checked on the server, so it travels
  (over HTTPS) to the server when you unlock them.

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
