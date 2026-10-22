# Salah

> This lives on its own branch of [Aezdek/Randomness](https://github.com/Aezdek/Randomness)
> ("every project that is too small to get a repo gets a branch").

An Android prayer tracker. Each day you tick the five prayers as **prayed** or **missed**, and the app keeps
count of the prayers you owe as **qada** (missed prayers to make up): both the ones you mark missed and the
older ones from before you started logging.

## What it does

- **Today**
  - Fajr, Dhuhr, Asr, Maghrib and Isha, each with a **Prayed** and a **Missed** button. Tap the chosen one again to clear it.
  - **Mark all prayed** fills in everything not logged yet. Prayers marked missed stay missed.
  - The Gregorian and Hijri date (Umm al-Qura), with Dhuhr shown as Jumu'ah on Fridays.
  - Your streak: days in a row with all five prayed.
  - Go back to any earlier day with the arrows or by tapping the date. Future days can't be logged.
- **History**
  - A month calendar with a dot per prayer: green prayed, red missed, grey not logged. Tap a day to open it.
  - The month's prayed percentage, missed count, and each prayer's record, so you can see which prayer you miss most.
- **Qada**
  - How many of each prayer you still owe, and a **Made up** button to tick one off (with undo for today's).
  - **Add old missed prayers**, either as a length of time ("2 years" adds 730 or 731 days of all five prayers) or as a count per prayer.
  - **Edit counts** to correct what you added.
  - Progress, how many you made up today, and an estimate of when you'll finish at your pace over the last 30 days.
- **Settings**
  - A daily reminder at a time you choose. It only notifies when something today isn't logged yet.
  - Shift the Hijri date by up to two days to match your local moon sighting.
  - Light, dark or system theme, and optional wallpaper colors (Android 12+).
  - **Export backup** and **Restore backup** as a JSON file.

### How qada is counted

For each prayer:

> **left to make up** = old missed prayers you added + days it's marked missed in your log − times you made it up

Changing a missed day to prayed takes it off the count again, so the log and the qada count never disagree.

## Install

Download **[salah.apk](https://github.com/Aezdek/Randomness/releases/download/salah-latest/salah.apk)** on your phone
(from the `salah-latest` release) and open it. Android asks once to allow installing apps from your browser
or file manager. Later builds install over it as updates and keep your data.

It needs Android 8.0 or newer.

## Signing

Every APK is signed with **one release key**, never a random debug key, so each new build installs as an update.
The key isn't in the repository; GitHub Actions reads it from two repository secrets:

| Secret | Value |
| --- | --- |
| `SALAH_KEYSTORE_BASE64` | the release keystore (`.jks`, PKCS12), base64-encoded on one line |
| `SALAH_KEYSTORE_PASSWORD` | its password (the key uses the same one) |

Add them under **Settings → Secrets and variables → Actions → New repository secret**, then re-run the workflow.
Until they exist the workflow still runs the tests and the release build, then stops with a "Signing key missing" error,
so an unsigned or wrongly signed APK is never published.

To make a new key yourself, run `scripts/create-signing-key.sh` (it needs a JDK). Do this only before anything
is installed: a build signed with a different key can't update the app, so you'd have to export a backup,
uninstall, reinstall and restore.

Keep the `.jks` and its password safe, for example in your password manager. GitHub won't show a secret again
after it's saved.

## Building

`.github/workflows/salah.yml` runs on every push to this branch:

1. The unit tests (`:core:test`) and the Robolectric UI tests (`:app:testDebugUnitTest`).
2. `:app:assembleRelease`, signed with the release key.
3. `apksigner verify`, which fails the run if the APK isn't signed or is signed with a debug key.
4. Uploads the APK as a workflow artifact and to the `salah-latest` release as `salah.apk`.

The version code is the workflow run number, so every build is newer than the last.

Locally, with JDK 17+ and the Android SDK (compileSdk 37):

```bash
./gradlew :core:test :app:testDebugUnitTest   # tests
./gradlew :app:assembleDebug                  # debug APK (xyz.aezdek.salah.debug, installs beside the release)
```

For a signed release build, put `salah.keystore=/path/to/salah-release.jks` and `salah.keystorePassword=…` in
`~/.gradle/gradle.properties`, or set the `SALAH_KEYSTORE_PATH` and `SALAH_KEYSTORE_PASSWORD` environment variables.

## Project layout

| Module | Contents |
| --- | --- |
| `core` | Pure Kotlin: the data model, edits, qada and pace, streaks and monthly totals, periods for old missed prayers, Hijri dates, reminder timing, and the backup file format. |
| `app` | Jetpack Compose UI (Today, History, Qada, Settings), the DataStore-backed store, and the reminder alarm and notification. |

## Your data

Everything is stored on the phone in one JSON file (`files/datastore/salah.json`), written atomically by DataStore.
Nothing is sent anywhere. The file is included in Android's device backup and phone-to-phone transfer, and you can
export it any time from Settings.
