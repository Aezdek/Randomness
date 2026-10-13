# Aria for Wear OS

Aria on the **Galaxy Watch** (built for the Watch8 Classic, and works on any Wear OS 3+ watch), with a
companion **Android phone app**. Part of [Aria](../README.md), next to the iPod app and the server.

<!-- screenshots -->

## What it does

**Watch**
- Plays downloaded songs offline, through Bluetooth earbuds or the watch speaker.
- Streams from your Aria server when the watch is online.
- Searches YouTube by voice or keyboard.
- **Saved** songs from the Aria website download to the watch by themselves.
- The bezel controls volume, a ring around the edge shows progress, and the standard Wear OS media controls work.
- Skips sponsor reads and non-music intros and outros (SponsorBlock).
- Updates itself.

**Phone**
- Searches and downloads with **yt-dlp running on the phone**, or through the Aria server, your choice.
- Sends songs to the watch over the Wear OS data link (Bluetooth). If the watch is away or asleep, songs wait and arrive when it's back in range.
- Sends the server address to the watch, so you never type a URL on it.
- Share a YouTube link to Aria from any app to send that song to the watch.
- Updates itself.

## Install

Download both from the [aria-wear-latest release](https://github.com/Aezdek/Randomness/releases/tag/aria-wear-latest):
`aria-watch.apk` and `aria-phone.apk`.

### Phone
Open `aria-phone.apk` on the phone and allow installing from your browser when Android asks.

### Watch
Wear OS apps that aren't from the Play Store go on with `adb`, once. After that the watch updates itself.

1. On the watch: **Settings → About watch → Software → tap Software version 5 times** (enables Developer options).
2. **Settings → Developer options**: turn on **ADB debugging** and **Wireless debugging**. The watch and computer must be on the same network.
3. In **Wireless debugging**, tap **Pair new device** and run, with the IP, port and code it shows:
   ```sh
   adb pair 192.168.x.x:<pairing port>
   ```
   If this fails with `protocol fault`, your Linux distribution's adb is the problem. Use Google's
   [platform-tools](https://developer.android.com/tools/releases/platform-tools) instead.
4. Close the popup and connect with the port shown under **IP address & port**, which is a different port:
   ```sh
   adb connect 192.168.x.x:<port>
   adb install aria-watch.apk
   ```
   With more than one device connected, pick the watch: `adb -s 192.168.x.x:<port> install aria-watch.apk`.
5. Turn off Wireless debugging afterwards, because it drains the battery.

Alternatively, **Wear Installer 2** from the Play Store installs APKs on the watch from the phone.

### Connect them
In the phone app's **Settings**: enter your Aria server (for example `192.168.1.20:8765`) and the key if you set one.
Tap **Save & test**, then **Send to watch**. Open Aria on the watch, and **Saved** fills with your songs.

## Updates

Both apps check the release above once a day (and from **Settings → Check for updates**), then download
and install the new version.

- **Phone:** Android asks you to confirm the first update. From then on, Android 12+ installs updates silently, because Aria installed itself.
- **Watch:** works the same way if the watch allows apps to install updates. If it refuses, Settings shows why. Install the new `aria-watch.apk` with adb as above.

Versions 1.0.0 had no updater, so install this version by hand once.

## Building

```sh
cd wear
./gradlew :core:testDebugUnitTest                      # title cleanup tests
./gradlew :watch:assembleRelease :phone:assembleRelease  # → */build/outputs/apk/release/
./gradlew :watch:recordRoborazziDebug :phone:recordRoborazziDebug  # screenshots → */build/screenshots/
```

Both apps use the application id `xyz.aezdek.aria` and are signed with `aria-sideload.keystore`.
The Wear OS data link only connects apps with the same id and key, and updates must be signed with the same key.
The keystore is committed on purpose: these apps are sideloaded, not published.

| Module | What it is |
| --- | --- |
| `core` | Song model, title cleanup, Aria server client (resumable downloads), updater, data-link paths |
| `watch` | The Wear OS app: Compose for Wear OS, Media3 playback, WorkManager downloads, data-link import |
| `phone` | The phone app: yt-dlp ([youtubedl-android](https://github.com/JunkFood02/youtubedl-android)), sending songs to the watch |
