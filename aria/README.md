# Aria

A modern music player for a **jailbroken iPod touch 5th gen** (iOS 9.x, also iOS 9+ iPhones and iPads).
It plays the music synced to the iPod, and it streams anything from YouTube through a small proxy that runs
on your computer.

## Features

- **Library**: every song and album synced with iTunes, with search, Shuffle All, and album pages that have Play and Shuffle buttons.
- **Stream**: search YouTube, play results right away, and keep tracks with the heart button. Saved and Recently Played appear on the Stream tab.
- **Now Playing**:
  - The background is a blur of the artwork, and the artwork shrinks when paused.
  - Big transport controls, a scrubber, shuffle and repeat (all or one), a volume slider and an Up Next queue.
  - Swipe down to close it.
- **Mini player** above the tab bar, showing progress, play/pause and next. Tap it or swipe up to open Now Playing.
- **Queue**: long-press any song (or tap **⋯** on a stream result) to Play Next or Add to Queue. Swipe to remove from Up Next.
- **Lock screen and Control Center**: shows the artwork, title and progress, and the controls and headphone buttons work.
- Keeps playing in the background, pauses when headphones are unplugged, and resumes after a call.
- Dark UI, drawn in code, sized for the 4-inch screen.

## How it fits together

```
iPod (Aria app)  ──plain HTTP on your Wi-Fi──▶  aria_proxy.py on your computer  ──HTTPS──▶  YouTube
```

iOS 9 can't do the TLS YouTube needs and can't run yt-dlp. The proxy does that work. It sends the iPod
128 kbps AAC (`.m4a`), which iOS 9 plays natively, and supports byte ranges so seeking works. Local songs
play straight from the iPod and don't need the proxy.

## 1. Run the proxy

You need Python 3.9+ on any computer on the same Wi-Fi (Mac, Windows, Linux or a Raspberry Pi):

```sh
cd aria/server
pip install -r requirements.txt
python3 aria_proxy.py
```

It prints the address to enter in the app, for example `http://192.168.1.20:8765`.

- `--token SECRET`: requires that key. Enter it in the app under Settings → Key.
- `--port 9000`: uses a different port.
- YouTube changes often. If search or playback stops working, update yt-dlp first with `pip install -U yt-dlp`.
  Recent yt-dlp versions also want a JavaScript runtime such as [Deno](https://deno.com) installed for YouTube.
- If the iPod can't connect, allow port 8765 through your computer's firewall.

## 2. Install the app on the iPod

Get the `.deb`:

- **From CI**: GitHub → Actions → **Aria** → the latest run → the `aria-deb` artifact. Pushing a tag such as `aria-v1.0.0` also attaches it to a release.
- **Or build it yourself** (see below).

Then install it on the jailbroken iPod. Use whichever works for you:

- **Filza**: copy the `.deb` over (AirDrop isn't available on iOS 9, so use Safari, iFile or a USB file manager), tap it, then tap Install.
- **SSH**: `scp xyz.aezdek.aria_*.deb root@<ipod-ip>:/tmp/`, then on the iPod run `dpkg -i /tmp/xyz.aezdek.aria_*.deb && uicache`.

The app is fake-signed with ldid, which is normal for jailbreak `.deb`s. If the icon doesn't appear, run `uicache` or respring.

Open Aria → **Settings**, enter the proxy address, then tap **Test Connection**.

## Building

The app is Objective-C for iOS 9 and is built with [Theos](https://theos.dev). It builds on Linux, macOS or WSL.

```sh
# One time: Theos, an iOS toolchain, and the iOS 12.4 SDK (the newest one that still has armv7)
export THEOS=~/theos
git clone --recursive https://github.com/theos/theos.git $THEOS
# Toolchain on Linux/WSL (on macOS Theos uses Xcode's, but recent Xcode can't build armv7):
mkdir -p $THEOS/toolchain && curl -sSL https://github.com/L1ghtmann/llvm-project/releases/latest/download/iOSToolchain-x86_64.tar.xz | tar -xJ -C $THEOS/toolchain
git clone --depth 1 --filter=blob:none --sparse https://github.com/theos/sdks.git /tmp/sdks
git -C /tmp/sdks sparse-checkout set iPhoneOS12.4.sdk && mv /tmp/sdks/iPhoneOS12.4.sdk $THEOS/sdks/

cd aria/app
make package FINALPACKAGE=1                   # → packages/xyz.aezdek.aria_1.0.0_iphoneos-arm.deb
make package install THEOS_DEVICE_IP=<ipod>   # build and install over SSH
```

The build targets iOS 9.0 (armv7 + arm64) with `-Wunguarded-availability`, so the compiler rejects any
API newer than iOS 9. To change the icon or launch images, edit `tools/make_images.py` and run it (it needs Pillow).

### Layout

| Path | What it is |
| --- | --- |
| `server/aria_proxy.py` | YouTube search, audio relay with byte ranges, thumbnails. Its only dependency is yt-dlp. |
| `app/Sources/ARPlayer.*` | Playback engine: one `AVPlayer` for local and stream, queue, shuffle, repeat, lock screen, remote commands, interruptions. |
| `app/Sources/ARProxyClient.*` | Talks to the proxy. |
| `app/Sources/ARLibraryViewController.*`, `ARAlbumViewController.*` | Local music. |
| `app/Sources/ARStreamViewController.*` | YouTube search, Saved, Recently Played. |
| `app/Sources/ARNowPlayingViewController.*`, `ARMiniPlayerView.*`, `ARQueueViewController.*` | Player UI. |
| `app/Sources/ARIcons.*`, `ARTheme.*` | Icons drawn in code, plus colors and fonts. |

## Limits

- Songs that are DRM-protected (old iTunes Store purchases) or only in iCloud can't be played by third-party apps. They show dimmed.
- YouTube streaming needs the proxy to be running and reachable. Away from home, run the proxy on a server and use its address, ideally with `--token`.
