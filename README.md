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
chat themes. Plans let a chat agree on a time and place, with going, maybe or can't go. Live location shares where
you are for a set time. A shared album collects everyone's photos as a gallery, and Notes to self keeps things for
later. Search looks through every chat at once, on your device. Calls are video or audio, one to one or in a group,
with screen sharing from the web app, and go directly between the people in them when the network allows it.

Around that there is search, an activity page, On this day, private accounts with follow requests, blocking, an app
lock, a way to check that you are really talking to who you think you are, and an admin panel for whoever runs the
server, with invites as QR codes and alerts on the admin's phone when backups, disk space or updates need a look.

Chats also carry files of any kind (up to 250 MB), shared lists everyone can tick off, a question of the day whose
answers show once everyone has answered, a countdown to a date, nudges, and messages written now to arrive later. On
Android a chat can watch a film together from your own Jellyfin (each phone plays it straight from Jellyfin with
its owner's sign-in there; Hush only carries which film and where it is), there is a home screen widget with the newest photo someone sent you, and Hush can wear a disguise: see
Security.

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
- **Someone else signing in.** Every new sign-in to your account is shown at once on your other devices: "Was this
  you?". Answering no signs that device out and takes you to change your password, which also signs out everything
  else and removes every passkey. The server keeps a record of each sign-in for 30 days (Settings → Recent sign-ins),
  so one that signs straight out again, or signs your own devices out first, is still shown when you're back. A
  device's name is cleaned of invisible characters that could make an alert read as something else.
- **Passkeys.** In a browser you can sign in with a fingerprint, face or security key instead of typing the password.
  The passkey produces a secret only it can make (WebAuthn's PRF); from it come a key the server checks, like the
  password's, and a key that never leaves your device and unseals your own copy of your keys. Adding one needs the
  password. Changing your password or keys removes them all.
- **Notifications.** You choose what they show: the message, only who sent it, or nothing. On Android the lock screen
  shows only "New message" whatever you choose, and with "nothing" the server leaves names out of browser pushes too.
- **What other people's apps send.** Everything members send each other is encrypted, so the server can't check it.
  Both apps check it instead, as it arrives: anything that isn't the shape an honest app sends is dropped, and what
  is merely outsized is cut down to what an honest app could send (one emoji as a quick reaction, ten photos to a
  message, six poll options, long messages folded behind "Read more"). One bad message can't take a chat or the app
  down with it. CI checks this on every build by sending the Android app about fifty such messages.
- **Screenshots.** On Android, chats in vanish mode, chats with disappearing messages and view-once photos can't be
  screenshotted or screen-recorded. On Android 14 and later, if a screenshot gets through anyway, the chat is told.
- **Files and what else a chat holds.** Any file up to 250 MB is sent encrypted like a photo and never opened by the
  app: names are cleaned of folders and invisible direction marks before they are shown or saved, and the web app
  hands a download over as plain bytes, so a file that claims to be a page is never treated as one of the site's.
  A message sent later is sealed when it is written; the server holds it unread and posts it at its time through the
  same checks as any other. Answers to the question of the day are hidden by the apps until everyone has answered
  (they are delivered sealed like any message, so a modified app could look early).
- **Taking access away.** When you remove a follower, block someone, take someone out of a group or remove a member,
  their app deletes what it had of yours (posts, stories, chats and the decrypted photos and videos it kept), and the
  server stops sending them anything more. A removed member is signed out everywhere and their device is wiped.
- **Someone picking up your phone (Android).** Settings → Disguise makes Hush look like another app on the home
  screen: Videos, Calculator, Weather, Notes, or a blank spot with no name. Opening it shows that app "keeps
  stopping", drawn over the home screen like Android's own message, and App info, Close app and Back all close it. A
  secret move you choose (four taps on the message, holding Close app for three seconds, three taps in the top right
  corner, or volume down, down, up) brings up a PIN or password prompt instead, and the right one opens Hush. The PIN
  is only for the disguise, is kept on the phone as an Argon2id hash, and after five wrong tries each one makes you
  wait longer. Hush closes whenever it leaves the screen, shows the disguise's name and icon and no picture in the
  recent apps list, and stays silent unless you let notifications through, which then say only "New notification".

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
- **Screenshots in a browser, or of a screen.** No website can stop a screenshot, and nothing stops another camera
  photographing a phone. The Android protection covers that phone only, and stops pictures of the screen, not apps
  the phone's owner has given accessibility access: those can still read a protected chat's words.
- **Live location.** Where you are is encrypted like everything else and only the latest spot is kept, never a trail,
  but the server can see that the message changes, and so how often you moved while sharing. It updates while Hush
  is open.
- **Deleted things linger in backups.** Disappearing, deleted and unsent messages can remain in the server's
  encrypted backups for up to thirty days.
- **Being made to open it (Android).** A duress PIN opens a disguised Hush signed out, with the account, keys and
  everything decrypted already gone from the phone; a "Quick clear" tile in quick settings does the same in one tap.
  Chats can be hidden from the inbox on a phone and shown again by typing a code into its search box. The disguise's
  PINs are tied to a key in the phone's secure hardware (Knox Vault or another secure chip where there is one, else
  the TEE), so a copy of Hush's files can't be used to guess them anywhere else. None of this changes anything on the
  server: signing in again brings everything back, which also means someone who knows your password still can.
- **What a disguise can't hide.** Android shows an app's real name in Settings → Apps and at the top of every
  notification, and no app can change that, so a disguised Hush only notifies if you turn that on. The icons copy no
  particular app, so they won't fool someone who knows what the phone's own Calculator looks like. A forgotten PIN
  can't be recovered: clear Hush's storage in Android's settings, or reinstall it, and sign in again; everything is
  on the server. Before Android 13 the only way to keep a screen out of the recent apps list also blocks
  screenshots, so a disguised Hush can't be screenshotted there.
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
