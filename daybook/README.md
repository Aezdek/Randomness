# Daybook

A daily study planner for A Level **Computer Science (9618)**, **Mathematics (9709)** and **Physics (9702)**.
Set yourself tasks for each subject, tick them off, and see the same list on your Android phone and your Linux laptop.

- **Android app** and **Linux desktop app** from one Kotlin Multiplatform codebase, with Compose for the UI.
- **Offline first.** Each device keeps its own SQLite copy and works with no connection; edits sync when it's back.
- **Live sync** through a small self-hosted server: a tick on the phone shows on the laptop within a second or two.
- **Designed to Apple's Human Interface Guidelines** (see [Design](#design)).

| | |
|---|---|
| **Today** | A week strip with a progress ring per day, tasks grouped by subject, unfinished tasks carried over to today, swipe for Done / Tomorrow / Delete, long-press or right-click for the same in a menu. |
| **Progress** | This week's totals per subject, your streak (days in a row with every task ticked), a 12-week activity grid, and a countdown to your first paper. |
| **Settings** | Server address and token, optional Cloudflare Access service token, sync status, appearance. |

> This lives in `daybook/` on a branch of [Aezdek/Randomness](https://github.com/Aezdek/Randomness). It is a separate Gradle
> build from AezPrint and shares nothing with it.

## How sync works

```
 Phone (SQLite) ──┐                       ┌── Laptop (SQLite)
                  │  POST /api/sync       │
                  ├──────────►  Server  ◄─┤   GET /api/wait  (long poll)
                  │           (SQLite)    │
```

- Every edit is written locally first and marked as unsent. Within about half a second the app pushes unsent edits
  and pulls everything changed since it last synced, in one request.
- While the app is open it holds a long poll (`/api/wait`) that answers the moment another device changes something.
- Conflicts resolve **last writer wins, per task**. An edit's timestamp is always later than the version it replaced,
  so an edit made on top of the latest version wins even if that device's clock is slow. The server caps timestamps at
  five minutes ahead of its own clock, so a device with a wildly wrong clock can't win forever.
- Deletes are kept as tombstones so they reach every device; **Undo** in the app restores them.
- On Android, unsent edits are pushed by WorkManager when the app goes to the background, even if it is then closed.

## 1. Run the server

You need somewhere always on, for example the machine that already runs `print.aezdek.xyz`.

```bash
cd daybook/server
```

```bash
echo "DAYBOOK_TOKEN=$(openssl rand -hex 32)" > .env
```

```bash
docker compose up -d
```

The image is `ghcr.io/aezdek/daybook-server:latest`. CI publishes it when you push a `daybook-v*` tag or run the
workflow by hand. To build it yourself instead, run `./gradlew :server:installDist` in `daybook/` and switch
`docker-compose.yml` to `build: .`.

The server listens on `127.0.0.1:8080`. Put HTTPS in front of it before using it from your phone, for example a
Cloudflare Tunnel that points a hostname such as `daybook.aezdek.xyz` at `http://localhost:8080`.

If the hostname sits behind **Cloudflare Access**, create a service token, add it to the application's policy
(action: *Service Auth*), and enter its client ID and secret in the app's settings. `/health` needs no token, for
uptime checks.

| Variable | Default | |
|---|---|---|
| `DAYBOOK_TOKEN` | (required) | Shared secret, at least 24 characters. |
| `DAYBOOK_DB` | `/data/daybook.db` in Docker | The SQLite file. Back this up. |
| `DAYBOOK_PORT` / `DAYBOOK_HOST` | `8080` / `0.0.0.0` | |

## 2. Install the apps

Every push that touches `daybook/` runs the **Daybook** workflow. Download from the run's artifacts, or from the
release when you push a `daybook-v*` tag:

- **Android:** `Daybook-android` → `Daybook-1.0.N.apk`. CI signs it with the `DAYBOOK_*` signing secrets, or the existing
  `AEZPRINT_*` ones if those aren't set, so each new APK installs as an update.
- **Linux:** `Daybook-linux` → `daybook_1.0.N-1_amd64.deb` (bundles its own Java runtime), installed with
  `sudo apt install ./daybook_*.deb`. It shows up as **Daybook** in your app menu. A runnable JAR is there too, for
  other distributions: `java -jar Daybook-linux-x64-*.jar` (needs Java 21).

Then on each device open **Settings**, enter the server address and token, and tap **Connect**. **Test Connection**
checks both without saving.

The desktop app keeps its data in `~/.local/share/daybook/` and its settings in `~/.config/daybook/config.json`. Both
are readable only by you. On Android the token is stored encrypted with a key held in the Android Keystore.

### Keyboard shortcuts (Linux)

| | |
|---|---|
| `Ctrl+N` | New task |
| `Ctrl+T` | Go to today |
| `Alt+←` / `Alt+→` | Previous / next day |
| `Ctrl+1` `2` `3` | Today, Progress, Settings |
| `Ctrl+R` | Sync now |
| `Esc` | Close a sheet or menu |

## Design

The UI follows the Apple UI design cheatsheet (13 Nov 2026), built in Compose so it runs the same on both platforms:

- **Typography:** the iOS text styles at their default sizes, leading and weights (Large Title 34/41 down to
  Caption 2 11/13), in sp so they scale with the system font size. SF Pro may not be shipped outside Apple platforms, so
  **Inter** stands in, with **Inter Display** from 20 sp up, the same split as SF Pro Text and SF Pro Display.
- **Colour:** Apple's semantic system colours. Secondary text, the accent and the subject colours use the
  Increase Contrast variants, or are darkened, so text meets 4.5:1 in light and dark. One accent (blue) means "you can
  tap this". State is never shown by colour alone: the check circle fills and shows a checkmark, and sync state is an
  icon plus a word.
- **Glass is for controls only:** the tab bar, toolbar buttons, sidebar and menus blur what's behind them (with Haze).
  Content never sits on glass, and there are no opaque bars. Content runs full bleed underneath, with a scroll-edge blur.
- **Shapes:** continuous-curvature ("squircle") corners via a custom shape, concentric radii for nested shapes, and
  capsules for controls.
- **Layout:** controls are at least 44 × 44. Compact width gets a floating tab bar and one prominent **+** button;
  regular width (wide windows, the desktop) gets a sidebar and a **New Task** button in the toolbar.
- **Motion:** springs throughout, interruptible, with the finger tracked 1:1 on swipes and sheet drags. Under Reduce
  Motion (Android "Remove animations", GNOME "Animations" off) springs lose their bounce and slides become fades.
- **Every gesture has a button:** swipe actions are also in the context menu and the edit sheet, the week swipe has
  arrows, and sheets have Cancel and Done. Haptics confirm ticks, selections and the full-swipe point.

## Development

```bash
cd daybook
```

```bash
./gradlew :core:jvmTest :server:test :shared:desktopTest
```

```bash
./gradlew :desktopApp:run
```

```bash
./gradlew :androidApp:assembleDebug
```

```bash
./gradlew :desktopApp:screenshots
```

The last one renders the real screens offscreen with sample tasks, to `desktopApp/build/screenshots/`.

| Module | |
|---|---|
| `core` | Sync protocol, record validation and the last-writer-wins rule. Shared by the apps and the server. |
| `server` | Ktor + SQLite sync server. |
| `shared` | Local store, sync engine, and the whole Compose UI, for Android and desktop. |
| `androidApp` | Android entry point, encrypted settings, background sync. |
| `desktopApp` | Linux entry point, packaging, keyboard shortcuts. |

The tests cover the protocol, the server (auth, conflicts, paging, long poll) and two devices syncing through a real
in-process server, including offline conflicts, a device with a slow clock, deletes with undo, and live updates.
