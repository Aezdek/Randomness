# AezPrint

Print to the home Samsung ML-2165 from anywhere. AezPrint is an Android app that talks to the print API at
`https://print.aezdek.xyz` (behind Cloudflare Access). The server does all PDF processing and renders the
previews, so what you see in the app is what comes out of the printer.

> This lives on the `aezprint` branch of [Aezdek/Randomness](https://github.com/Aezdek/Randomness)
> ("every project that is too small to get a repo gets a branch").

## What it does

- **Three ways in**
  - **AezPrint in the system print dialog.** The Android `PrintService` adds the printer to every app's print dialog, and **More options** opens the full AezPrint option set.
  - **Share and "Open with"** for PDFs.
  - **The app itself**, with a file picker and a recent-documents list.
- **Every Acrobat print option**
  - Copies and collate.
  - Pages: all, current, a custom range (`1-3,7,10-12`), odd or even only, reverse order.
  - Sizing: fit, actual size, shrink oversized, custom scale, and paper chosen by PDF page size.
  - Multiple pages per sheet: 2, 4, 6, 9 or 16, or a custom grid, with page order and borders.
  - Booklet: both sides, front only or back only, with left or right binding.
  - Poster: tile scale, overlap, cut marks and labels.
  - Orientation, paper size, two-sided printing, and comments and forms.
  - Print as image with dpi, and toner save. Grayscale is always on, because the printer is mono.
  - Named presets, with a default.
- **Live preview**
  - Re-rendered by the server about 350 ms after you stop changing options.
  - Page outlines slide to their new places right away while the new render loads.
  - Pinch or double-tap to zoom, scrub through sheets, and see "Sheet X of Y" and the total paper used.
  - Tap a page to make it the current page.
- **Jobs**
  - WorkManager handles upload, submit and progress tracking. Jobs survive process death and retry on bad connections without printing twice.
  - A foreground notification shows progress. For manual duplex, a high-priority **flip the stack** notification has a *Continue printing* action.
  - The progress screen shows a paper-feed animation and an illustrated flip step, with cancel.
  - History offers one-tap reprint.
- **Printer status on the home screen:** ready, printing, offline, out of paper or jam.
- **Security**
  - The three secrets are stored only as AES-256-GCM ciphertext, under a non-exportable Android Keystore key.
  - They are never logged and are excluded from backup and device transfer.
  - Requests go over HTTPS only (no cleartext, and the auth interceptor refuses `http://`).
  - Optional biometric or screen-lock gate.

## Project layout

| Module | Contents |
| --- | --- |
| `core:domain` | Pure Kotlin: option models, page-range parser, option validator, job state machine, imposition (sheet layout), repository interfaces |
| `core:data` | Retrofit/OkHttp client for the v1 API, DTO mapping, Keystore cipher, JSON DataStores, document import, **fake server**, WorkManager job pipeline and notifications |
| `core:designsystem` | Material 3 theme (dynamic color, light/dark), visual option glyphs, paper-feed and flip illustrations, shared states |
| `feature:home` | Printer status, active jobs, recent documents, file picker |
| `feature:print` | Print screen: preview pane, options panel (also reused by the PrintService), view model |
| `feature:jobs` | Job progress (including the flip step) and history |
| `feature:settings` | Server address, credentials (paste or QR), connection test, biometric lock, appearance, fake-server debug controls |
| `app` | Activity, navigation with shared-element transitions and predictive back, deep links, biometric gate, `PrintService` |

## Building

Requirements: JDK 17 or newer and the Android SDK (compileSdk 37). The Gradle wrapper downloads Gradle 9.8.1.

```bash
./gradlew :core:domain:test testDebugUnitTest   # unit tests + Robolectric UI tests
./gradlew :app:assembleDebug                    # debug APK (package xyz.aezdek.aezprint.debug)
```

The tests cover:

- **Unit:** page-range parsing, option validation, the job state machine, imposition, API mapping, the auth headers and HTTPS enforcement, QR setup codes, and option side effects.
- **UI (Robolectric, against the real fake server):**
  - Every option group updates the preview.
  - Invalid ranges and 422 rejections show up inline.
  - Printing runs a job to completion.
  - A manual-duplex job pauses for the flip and resumes on continue.
  - Cancel works.
  - Reprint from history works.
  - PDFs opened from other apps, shares and notification deep links route to the right screen.

## Signed APKs (GitHub Actions)

`.github/workflows/aezprint.yml` runs the tests and builds a release APK on every push to `aezprint`. The APK is
always signed with **the same release key**, never a random debug key, so new builds install as updates.
Tagging `aezprint-vX.Y` also publishes a GitHub release with the APK attached.

One-time setup, on your own machine with a JDK and a logged-in `gh`:

```bash
scripts/create-signing-key.sh            # creates aezprint-release.jks and sets the four secrets
```

The script sets these repository secrets:

| Secret | Value |
| --- | --- |
| `AEZPRINT_KEYSTORE_BASE64` | `base64` of the `.jks` file |
| `AEZPRINT_KEYSTORE_PASSWORD` | keystore password |
| `AEZPRINT_KEY_ALIAS` | key alias (`aezprint`) |
| `AEZPRINT_KEY_PASSWORD` | key password |

Keep the `.jks` and its password safe. If you lose them, the next APK can't be installed over the old one.
Until the secrets exist, the workflow still runs the tests and the release build, then fails with a
"Signing key missing" error.

For a local signed build, put the same values in `~/.gradle/gradle.properties`
(`aezprint.keystore`, `aezprint.storePassword`, `aezprint.keyAlias`, `aezprint.keyPassword`), or export the
`AEZPRINT_*` environment variables (`AEZPRINT_KEYSTORE_PATH` instead of the base64 one).

## First run: the fake server

The app starts with **Settings → Developer → Use fake print server** switched on, so everything works before the
real server exists.

- **What it imitates.** The fake server implements the same `PrintServer` interface as the HTTP client. It behaves like the ML-2165: mono, 600/1200 dpi, manual duplex.
- **Previews.** It renders them from the real PDF using the same imposition rules as the app.
- **Jobs.** They run on a timer, and two-sided jobs pause in `awaiting_flip`.
- **Debug controls.**
  - Set the printer state: ready, offline, out of paper, jam (or error). A jam or empty tray pauses running jobs.
  - Set the network latency and the print speed.
  - Drop uploads halfway, to exercise the WorkManager retries.
  - Reject poster options with a 422, to show inline field errors.

## Pointing the app at the real server

1. Open **Settings → Print server** and check that the address is `https://print.aezdek.xyz`. The app accepts only `https://` addresses.
2. Add the credentials in one of two ways:
   - **Scan QR.** The payload is either JSON:
     ```json
     {"url":"https://print.aezdek.xyz","clientId":"<id>.access","clientSecret":"<secret>","apiKey":"<key>"}
     ```
     or a link:
     `aezprint://setup?url=https%3A%2F%2Fprint.aezdek.xyz&clientId=…&clientSecret=…&apiKey=…`
   - **Enter manually.** Paste the `CF-Access-Client-Id`, the `CF-Access-Client-Secret` and the API key.
3. Turn off **Developer → Use fake print server**.
4. Tap **Test connection**. It calls `GET /v1/printer` and shows the printer name.

To produce a setup QR on the server, for example: `qrencode -t ansiutf8 "$(cat setup.json)"`.

## API contract notes

The app follows the v1 contract in the brief. Where the brief leaves details open, it assumes the following, and
the server should match:

- **Headers.** Every request sends `CF-Access-Client-Id`, `CF-Access-Client-Secret` and `Authorization: Bearer <key>`. Redirects are not followed, so a 3xx (Cloudflare Access login), 401 or 403 is reported as a credentials problem.
- **Sheet numbering.** `GET /v1/renders/{renderId}/sheets/{n}.png?width=` is **1-based** and indexes *faces*: front, back, front, back… So it runs `1..sheetCount × sides`, with `sides` from the render response.
- **`media[]` in `GET /v1/printer`.** Either PWG self-describing names (`iso_a4_210x297mm`, `na_letter_8.5x11in`) or objects `{id, displayName|label, widthMm|widthPt, heightMm|heightPt}`. The selected media's `id` is sent back in `options.media`.
- **`capabilities.duplex`.** May be a boolean (`true` means manual duplex) or one of `"none"`, `"manual"` or `"automatic"`. `resolutions[]` may hold numbers or strings like `"600dpi"`.
- **`state`.** Plain IPP states (`idle`, `processing`, `stopped`) plus `stateReasons` (`media-empty-error`, `media-jam-error`, `offline-report`, …), or the friendly names `printing`, `offline`, `out_of_paper` and `jam`.
- **`expiresAt`.** Epoch seconds, epoch milliseconds, or ISO-8601. When a document has expired (404 or 410), the app uploads it again automatically.
- **`GET /v1/jobs?limit=`.** May return a bare array or `{ "jobs": [...] }`. Each item carries `jobId` or `id`.
- **Job progress.** For two-sided jobs, `sheetsCompleted` and `sheetsTotal` count sides printed. The fake server does this, and the app labels it "sides".
- **Enum wire values in the options object:**
  - `subset`: `all`, `odd`, `even`
  - `orientation`: `auto`, `portrait`, `landscape`
  - `sizing.mode`: `fit`, `actual`, `shrink`, `custom`, `multiple`, `booklet`, `poster`
  - `multiple.order`: `horizontal`, `horizontalReversed`, `vertical`, `verticalReversed`
  - `booklet.subset`: `both`, `frontOnly`, `backOnly`
  - `booklet.binding`: `left`, `right`
  - `duplex`: `off`, `longEdge`, `shortEdge`
  - `content`: `document`, `documentAndMarkups`, `documentAndStamps`, `formFieldsOnly`
- **Page ranges.** `pageRange` is omitted for all pages. For the current page it is that page number; for a custom range, the range with spaces removed.
- **Errors.** Failures return `{error, message, fields[]}`. On a 422, each entry in `fields[]` is an option path such as `poster.overlapMm` or `sizing.scalePercent`, and the app shows the message next to that control.
