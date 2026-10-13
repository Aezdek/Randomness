<img src="docs/icon.png" width="96" align="right" alt="Aria icon">

# Aria

> This lives on the `aria` branch of [Aezdek/Randomness](https://github.com/Aezdek/Randomness)
> ("every project that is too small to get a repo gets a branch").

A modern music player for a **jailbroken iPod touch 5th gen** (iOS 9.x). It also runs on other jailbroken
iOS 9+ devices. It plays the music synced to the iPod, and it streams anything from YouTube, either
straight from the iPod or through a small server on your computer or homelab. You change its settings
and manage your Saved songs from a website, not the iPod's keyboard.

## Features

- **Library**: every song and album synced with iTunes, with search, Shuffle All, and album pages that have Play and Shuffle buttons.
- **Stream**: search YouTube, play results right away, and keep tracks with the heart button. Saved and Recently Played appear on the Stream tab.
- **Three streaming modes** (Settings on the iPod, or the website):
  - **Auto** (the default): the iPod talks to YouTube itself, and uses the server if that fails.
  - **Direct**: the iPod only. Needs no server, but can break when YouTube changes something.
  - **Proxy**: everything through the server. The most reliable.
- **A website to set it up** (`http://<server>:8765/admin`), from a real keyboard:
  - Customize the accent color, theme (dark, true black, midnight), Now Playing background, artwork corners, starting tab, library sort and search size.
  - Search YouTube and manage **Saved** songs.
  - The iPod picks up changes when Aria opens and every 30 seconds while it's running.
- **No ads, and clean song info for streams**:
  - Audio comes straight from YouTube's stream, so there are no YouTube ads.
  - Sponsor reads, "subscribe" reminders and non-music intros and outros are skipped automatically using [SponsorBlock](https://sponsor.ajay.app). You can turn this off in Settings.
  - Titles show as *Song* by *Artist* instead of "Artist - Song (Official Video) [4K]".
  - The album and real square album art come from YouTube's music data and the iTunes catalog, and they show on the lock screen too.
- **Now Playing**:
  - The background is a blur of the artwork, and the artwork shrinks when paused.
  - Big transport controls, a scrubber, shuffle and repeat (all or one), a volume slider and an Up Next queue.
  - Swipe down to close it.
- **Mini player** above the tab bar, showing progress, play/pause and next. Tap it or swipe up to open Now Playing.
- **Queue**: long-press any song (or tap **⋯** on a stream result) to Play Next or Add to Queue. Swipe to remove from Up Next.
- **Lock screen and Control Center**: shows the artwork, title and progress, and the controls and headphone buttons work.
- Keeps playing in the background, pauses when headphones are unplugged, and resumes after an interruption.

---

<p align="center"><img src="docs/website.png" width="720" alt="The settings website"></p>

## Install

You need:

- An iPod touch 5th gen on iOS 9 that is **jailbroken** (Phœnix or similar) and has **Filza File Manager** from Cydia.
- A computer (Windows, Mac or Linux) on the **same Wi-Fi**, with **Python 3.9 or newer**.
  - On Windows, get it from [python.org](https://www.python.org/downloads/) and tick **"Add python.exe to PATH"** in the installer.
  - On a Mac, `python3` is there after you run `xcode-select --install`, or install it from python.org.

The computer runs the Aria server. The server streams YouTube to the iPod, and it also hands the iPod the
app to install.

### Step 1: Start the server on your computer

Download this branch: on GitHub, switch to the `aria` branch, then **Code → Download ZIP**, and unzip it.
Or run `git clone -b aria https://github.com/Aezdek/Randomness.git aria`.

Then start the server:

| Windows | Mac / Linux |
| --- | --- |
| Open the `server` folder and double-click **`run.bat`** | In a terminal: `./server/run.sh` |

The first start takes a minute, because it installs yt-dlp and Deno into `server/.venv`. When it's ready, it prints:

```
12:54:23 Aria proxy 1.0.0 (yt-dlp 2026.08.19)
12:54:23 On the iPod, open http://192.168.1.20:8765 in Safari to install Aria
12:54:23 In the app, set Server to: http://192.168.1.20:8765
12:54:24 App package: serving xyz.aezdek.aria_1.0.0_iphoneos-arm.deb (release aria-v1.0.0)
```

Note **your** address (the `http://192.168.…:8765` part). Leave this window open while you listen.

> **Windows firewall:** the first time, Windows asks whether Python may use the network. Allow it on **Private networks**, or the iPod can't connect.

### Step 2: Install the app on the iPod

<img src="docs/install-page.png" width="240" align="right" alt="The install page in Safari on the iPod">

1. On the iPod, open **Safari** and go to the address from Step 1, for example `http://192.168.1.20:8765`.
2. Tap **Install Aria**.
3. Safari shows the download. Tap **Open in "Filza"**.
4. Filza shows the package. Tap **Install** in the top right corner.
5. When it finishes, tap **Done**, then press the Home button. **Aria** is on the home screen.
   If it isn't, respring, or run `uicache` in a terminal app.

<br clear="right">

**Other ways to install** (any one works):

- **SSH** (needs OpenSSH from Cydia, default password `alpine`, so change it):
  ```sh
  scp xyz.aezdek.aria_1.0.0_iphoneos-arm.deb root@<ipod-ip>:/tmp/
  ssh root@<ipod-ip> "dpkg -i /tmp/xyz.aezdek.aria_*.deb && uicache"
  ```
- **Straight from GitHub in the iPod's Safari** (iOS 9 can open it). This link always gives the newest version:
  `https://github.com/Aezdek/Randomness/releases/download/aria-v1.0.0/xyz.aezdek.aria_1.0.0_iphoneos-arm.deb`
  Tap **Open in "Filza"**, then **Install**.
- **The `.deb` file directly**: from [Releases](https://github.com/Aezdek/Randomness/releases), or from GitHub → Actions → **Aria** → the latest run → the `aria-deb` artifact.

### Step 3: Connect the app to the server

Direct mode doesn't need the server at all. Connect it anyway for the website, the fallback, and the best song info.


1. Open **Aria**, then go to the **Settings** tab.
2. Under **YouTube Proxy**, set **Server** to the same address, for example `http://192.168.1.20:8765`.
3. Tap **Test Connection**. It should say **Connected · yt-dlp …**.
4. Go to **Stream** and search for something.

Your synced music is already in the **Library** tab and works without the server.

### Changing settings

Open `http://<server>:8765/admin` on your computer or phone. If the server runs with `--token`, it asks for
the key once. Changes save as you click, and the iPod applies them within 30 seconds. On the iPod, Settings
shows only the server, the streaming mode and the skip switch.

Settings and Saved songs are kept in `server/aria-data.json`. Back that file up if you care about them.

**If direct mode stops working**, YouTube has probably retired the app versions Aria pretends to be. Copy the
newer `ANDROID_VR` or `IOS` client versions from yt-dlp's
[`_base.py`](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py) into
**Advanced** on the website. Until then, Auto mode keeps working through the server.

### Updating and uninstalling

- **Update the app:** open the server page in Safari again and install the newer version over the top.
- **Update the server:** run `run.bat` or `run.sh` again. It updates yt-dlp every time it starts.
- **Uninstall:** in Cydia, go to Installed → Aria → Modify → Remove. Or run `dpkg -r xyz.aezdek.aria` over SSH.

### Troubleshooting

| Problem | Fix |
| --- | --- |
| The page stays blank on the computer too | Read the server window. The self-check line says whether the server answers, and every visit appears as a line such as `GET / -> 200`. If visits don't show up, another program has the port: close every Aria window, run it once, or use `--port 8766`. Type the address with `http://` and the port. |
| The page opens on the computer but not on the iPod | Something between them blocks it. **1. Firewall:** on Linux, `sudo ufw allow 8765/tcp` (Ubuntu/Mint) or `sudo firewall-cmd --add-port=8765/tcp --permanent && sudo firewall-cmd --reload` (Fedora). On Windows, allow Python on Private networks. On a Mac, allow incoming connections for Python. **2. Address:** the server window lists every address the computer has. Try the one on your Wi-Fi adapter (`wlan…`/`wlp…`), not `docker0` or a VPN (`tun0`, `wg0`). It should start like the iPod's own IP (Settings → Wi-Fi → ⓘ). **3. VPN:** a VPN on the computer (Mullvad, Proton, NordVPN…) usually blocks local devices. Turn on its **Local network sharing** / **LAN access** setting (Mullvad: `mullvad lan set allow`). **4. Network:** school, work, hotel and guest Wi-Fi often block devices from reaching each other. Use your home Wi-Fi or a phone hotspot that both devices join. |
| The page says the app package isn't available | The server couldn't download it from GitHub. Download the `.deb` from Releases, put it in the `server` folder, and restart the server. |
| Test Connection says "Wrong or missing access key" | You started the server with `--token`. Enter the same value in Settings → **Key**. |
| Search or playback fails with a YouTube error | Restart the server, which updates yt-dlp. YouTube changes often and old yt-dlp versions stop working. |
| A library song shows dimmed and won't play | It's DRM-protected (an old iTunes Store purchase) or only in iCloud. Third-party apps can't play those. |
| Music stops when the computer sleeps | YouTube streams through the computer. Stop it from sleeping, or run the server on something always on, such as a Raspberry Pi. |

### Server options

```sh
./server/run.sh --port 9000          # use another port
./server/run.sh --token mysecret     # require a key (enter it in the app under Settings → Key)
./server/run.sh --deb path/to.deb    # offer a specific build for install
```

To use it away from home, run the server on a machine you can reach from outside, always with `--token`.

---

## How it works

```
iPod (Aria app)  ──plain HTTP on your Wi-Fi──▶  aria_proxy.py on your computer  ──HTTPS──▶  YouTube
```

iOS 9 can't do the TLS YouTube needs and can't run yt-dlp. The proxy does that work. It sends the iPod
128 kbps AAC (`.m4a`), which iOS 9 plays natively, and supports byte ranges so seeking works.
Thumbnails go through the proxy too. Local songs play straight from the iPod.

| Endpoint | What it does |
| --- | --- |
| `GET /` | Install page for the iPod's Safari |
| `GET /aria.deb` | The app package |
| `GET /api/ping` | Server and yt-dlp version |
| `GET /api/search?q=…` | YouTube search results |
| `GET /admin` | The settings and Saved songs website |
| `GET /api/config`, `POST /api/settings`, `PUT /api/saved` | What the website and the app sync |
| `GET /api/track/<id>` | Clean title, artist and album, whether album art exists, and the segments to skip |
| `GET /stream/<id>.m4a` | Audio, with Range support |
| `GET /thumb/<id>.jpg?size=small\|large` | Thumbnails |

## Building the app

The app is Objective-C for iOS 9 and is built with [Theos](https://theos.dev) on Linux, macOS or WSL. CI
builds it on every push to `aria` and publishes it as the release `aria-v<version>`. To make a new
release, bump `Version:` in `app/control`. The server downloads the newest release automatically.

```sh
# One time: Theos, an iOS toolchain, and the iOS 12.4 SDK (the newest one that still has armv7)
export THEOS=~/theos
git clone --recursive https://github.com/theos/theos.git $THEOS
mkdir -p $THEOS/toolchain && curl -sSL https://github.com/L1ghtmann/llvm-project/releases/latest/download/iOSToolchain-x86_64.tar.xz | tar -xJ -C $THEOS/toolchain
git clone --depth 1 --filter=blob:none --sparse https://github.com/theos/sdks.git /tmp/sdks
git -C /tmp/sdks sparse-checkout set iPhoneOS12.4.sdk && mv /tmp/sdks/iPhoneOS12.4.sdk $THEOS/sdks/

cd app
make package FINALPACKAGE=1                   # → packages/xyz.aezdek.aria_<version>_iphoneos-arm.deb
make package install THEOS_DEVICE_IP=<ipod>   # build and install over SSH
```

The build targets iOS 9.0 (armv7 + arm64) with `-Wunguarded-availability`, so the compiler rejects any
API newer than iOS 9. To change the icon or launch images, edit `app/tools/make_images.py` and run it (it needs Pillow).

| Path | What it is |
| --- | --- |
| `server/aria_proxy.py` | The server: YouTube search, audio relay, thumbnails, install page. |
| `server/run.sh`, `server/run.bat` | One-command setup and start. |
| `app/Sources/ARPlayer.*` | Playback engine: one `AVPlayer` for local and stream, queue, shuffle, repeat, lock screen, remote commands, interruptions. |
| `app/Sources/ARProxyClient.*` | Talks to the server. |
| `app/Sources/ARLibraryViewController.*`, `ARAlbumViewController.*` | Local music. |
| `app/Sources/ARStreamViewController.*` | YouTube search, Saved, Recently Played. |
| `app/Sources/ARNowPlayingViewController.*`, `ARMiniPlayerView.*`, `ARQueueViewController.*` | Player UI. |
| `app/Sources/ARIcons.*`, `ARTheme.*` | Icons drawn in code, plus colors and fonts. |
