# AezGram

A private Instagram for a handful of people (built for 2, capped at 10), where everything is end-to-end encrypted
and lives on your own server. There's an Android app and a web app. Video calls are peer to peer.

- **Feed:** posts with up to 20 photos or videos, crop, filters, captions, @mentions, #hashtags, tags and
  location. Likes, threaded comments, saves and collections, archive, hidden like counts, comments off.
- **Stories:** photo, video or text stories, with stickers (poll, quiz, question, slider, countdown, mention,
  hashtag, location, link, emoji). Close Friends, viewers, replies into DMs, highlights and an archive.
- **Reels:** a full-screen vertical feed, with trim and cover.
- **Direct:** 1:1 chats and groups. Photos, videos, voice notes, view-once, vanish mode, reactions, replies,
  edit and unsend, typing and seen receipts, Notes, and pinned and muted chats.
- **Calls:** 1:1 and group video calls (mesh, up to 10 people). Live to followers (up to 9 viewers) with comments
  and hearts.
- **Everything else:** explore and search, activity, private accounts, follow requests, blocks, activity status,
  notification settings, app lock, safety numbers with QR verification, sessions, key reset, data export
  (web), and in-app updates.

The server only ever stores ciphertext. Media is transcoded on the phone or in the browser before it's encrypted
(H.264/AAC MP4 up to 1080p, JPEG with EXIF and GPS stripped), because the server can't transcode what it can't read.

## Setting it up

You do four things. Everything else is automatic.

1. **On Resolute, install it once:**

   ```bash
   curl -fsSLO https://github.com/Aezdek/Randomness/releases/download/aezgram-latest/install.sh
   ```

   ```bash
   sudo bash install.sh
   ```

   It does the following:
   - lays out `/opt/hl/aezgram`;
   - picks a port that `PORTS.md` doesn't use;
   - starts the newest image CI built;
   - adds `gram.aezdek.xyz` to the cloudflared config: backup first, insert above the catch-all, validate,
     check that lucifer and print still answer, and roll back if anything got worse;
   - installs the nightly backup.

   It prints the **first invite code**, and whoever uses that code becomes the admin. For calls that work on every
   mobile network, add Cloudflare TURN:

   ```bash
   sudo CLOUDFLARE_API_TOKEN=... CLOUDFLARE_ACCOUNT_ID=... bash install.sh
   ```

2. **Backups, once:** if the `gdrive-crypt:` rclone remote doesn't exist yet, run `sudo rclone config` (Google
   needs a browser to sign in). Then re-run `install.sh`. The steps are in [`deploy/RUNBOOK.md`](deploy/RUNBOOK.md).
3. **Phones:** open `https://gram.aezdek.xyz/download/aezgram.apk` on each phone and install it. Then open
   **Settings → Calls and ringing** and tick every item. On a Samsung that includes "Never sleeping apps". Finish
   with a test ring while the phone is locked.
4. **Updates:** when you want a new version, run `sudo /opt/hl/aezgram/update.sh`. It shows what changed, asks
   y/N, health-checks the new version, and rolls back if that fails. The apps then pick it up: the web app on its
   next load, and Android from **Settings → App updates**.

Invite the others from **Settings → Server and invites**.

## How it fits together

```
 Android app (Kotlin/Compose) ─┐ HTTPS + WSS                      ┌─ SQLite  /opt/hl/aezgram/data/aezgram.db
 Web app (React PWA) ──────────┼─► gram.aezdek.xyz ─► Ktor server ─┼─ blobs   /opt/hl/aezgram/data/blobs
                               │   (cloudflared on Resolute)       └─ Web Push, TURN credentials
 Peer ◄── WebRTC (P2P, or relayed by Cloudflare TURN: ciphertext only) ──► Peer
 Nightly: SQLite snapshot + blobs + signing key ── rclone ──► encrypted Google Drive remote
```

| Path | What |
| --- | --- |
| `shared/protocol` | API types, envelopes and the media spec, shared by the server and Android |
| `shared/crypto` | libsodium: vault, object envelopes, chunked media encryption, safety numbers |
| `shared/client` | The Android app's non-UI layer: API, sync, composer, media and realtime, tested against the server |
| `server/` | Ktor + SQLite. It stores ciphertext, enforces access by key wraps, and relays call signals |
| `web/` | The web app (React, Vite, libsodium-wrappers, Mediabunny for transcoding) |
| `android/` | The Android app (Compose, Media3, WebRTC). A separate Gradle build that includes the root one |
| `deploy/` | The install, update, backup and restore scripts, the compose file, the container entrypoint, and their tests |
| `crypto-vectors/` | Test vectors that keep the Kotlin and TypeScript crypto byte-for-byte identical |

## Development

The JVM parts need JDK 21. The Android app needs the Android SDK, so CI builds it.

```bash
./gradlew :shared:crypto:test :server:test :shared:client:test
```

```bash
cd web && npm ci && npm run typecheck && npm test && npm run e2e
```

```bash
bash deploy/tests/run.sh
```

```bash
./gradlew -p android :app:testDebugUnitTest :app:assembleDebug
```

On every push to `aezgram`, CI runs all of the above, plus the photo and video pipeline on an Android emulator. It
renders the Android screens to screenshots for review, and builds the image (server, web app and unsigned APK) and
smoke-tests it, including signing the APK and checking it the way a phone would. Then it publishes everything to
the rolling `aezgram-latest` release that `install.sh` and `update.sh` read.

## Security and trade-offs

**What's encrypted:**

- Posts, comments, stories, reels, messages, reactions, profiles, settings and media are encrypted on the device.
  Each object gets its own key, wrapped with `crypto_box_seal` for exactly the people who may see it.
- Every object is signed by its author and carries a key commitment.
- Your private keys sit on the server only sealed under a key derived from your password with Argon2id. The server
  stores a hash of a separate derived login key, never the password.
- Call setup (SDP and ICE) is signed and sealed end to end, so TURN only ever relays SRTP ciphertext.

**What the server sees:** usernames, who follows and messages whom, when, and how big things are.

**Trade-offs, chosen on purpose:**

- **The web app trusts the server, Cloudflare and GitHub each time it loads.** Resolute serves the JavaScript and
  Cloudflare terminates TLS. Whoever controls either could serve code that captures your password.
  - Mitigations: a strict CSP, no third-party scripts, and the web bundle ships inside the pinned image.
  - **The Android app is the trusted client:** its code comes in a signed APK, and Android refuses updates signed
    with any other key. Do sensitive things, such as verifying contacts, on Android.
- **No Watchtower.** The image is pinned by digest and only changes when you approve `update.sh`. Phones only get
  APKs from that approved image.
- **The APK signing key lives only on Resolute.**
  - It is made on first start in `data/signing/` and kept in the encrypted backup. GitHub never has it, so a
    GitHub account takeover can't produce an APK your phones would accept as an update.
  - The cost: if both the server and its backup were lost, every phone would reinstall the app once. Your data is
    on the server either way.
- **No forward secrecy.** Keys are long-lived and history lives on the server, so that signing in on a new phone
  restores everything.
- **Removing a follower or member** stops further downloads, but their device keeps what it already cached.
- **Changing your password** re-seals the same keys. If an old copy of the server's data leaked, the old password
  still opens that copy. After a suspected leak, use **Settings → Security → Reset keys**. That gives you a new
  identity, re-seals everything you can read to it, and warns your contacts.
- **Weak passwords** can be guessed offline by anyone holding the server's data, so the app requires at least 10
  characters.
- **Late visibility:** new followers and new members see older posts once each author's app has been online to
  re-seal them.
- **Vanish mode, view-once and unsend** really delete from the server. They may survive in the encrypted backups
  for up to 30 days.
- **Keys are pinned on first use.** If someone's key changes, you get a loud warning. Compare safety numbers or
  scan QR codes under **Settings → Security** to be sure nobody is in the middle.
