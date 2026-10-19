# Capview

A small GTK4/libadwaita control panel for USB HDMI capture cards on Fedora KDE (Wayland, PipeWire).
It's built around the MacroSilicon MS2109. It finds the card, starts a low-latency `mpv` (or `ffplay`)
preview and loops the card's audio to a sink you choose. That sink can be a Bluetooth headset that the
app connects and switches to A2DP.

Capview doesn't decode anything itself. Video goes through `mpv`/`ffplay`, audio routing through `pactl`,
and Bluetooth through `bluetoothctl`, each run as a subprocess.

## Install (Fedora)

```sh
sudo dnf install python3-gobject gtk4 libadwaita \
    mpv v4l-utils pulseaudio-utils bluez util-linux-core pipx
```

`ffplay` (the optional second player) comes from `ffmpeg`. If you already have RPM Fusion's `ffmpeg`,
you're set. Otherwise add `ffmpeg-free`, which conflicts with the RPM Fusion package, so install only one.

Optional, for VA-API MJPEG decode: `libva-utils` (`vainfo` should list `VAProfileJPEGBaseline`) and your
GPU's VA driver. Intel uses `intel-media-driver`. AMD uses `mesa-va-drivers-freeworld` from RPM Fusion.
If VA-API isn't available, mpv falls back to software decode, which still keeps up with 1080p60 MJPEG
on any recent CPU.

```sh
pipx install --system-site-packages ./capview
install -Dm644 capview/data/io.github.aezdek.Capview.desktop \
    ~/.local/share/applications/io.github.aezdek.Capview.desktop
```

`--system-site-packages` is needed because PyGObject comes from Fedora, not PyPI. If `~/.local/bin` isn't
on the PATH that Plasma uses for launchers, put an absolute path in the desktop file:

```sh
sed -i "s|^Exec=.*|Exec=$HOME/.local/bin/capview|" \
    ~/.local/share/applications/io.github.aezdek.Capview.desktop
```

Run from a checkout without installing: `cd capview && PYTHONPATH=src python3 -m capview`.

## Using it

- **Device** is found by matching `MACROSILICON` in `v4l2-ctl --list-devices` and in
  `/dev/v4l/by-id/`. The `-video-index0` link is resolved to whichever `/dev/videoN` it points to today.
  Hit rescan after replugging. Start also rescans, so a changed node number never matters.
- **Mode** lists the MJPEG modes from `v4l2-ctl --list-formats-ext`. The highest one (1080p60 on the
  MS2109) is the default. The last mode you used is remembered.
- **Player**: mpv (default, VA-API capable) or ffplay. The preview opens in its own window, because
  Wayland has no cross-process window embedding. Press `f` for fullscreen and `q` to close it. Closing
  the preview window stops the audio loopback too.
- **Output** lists every sink from `pactl list short sinks`, both Bluetooth and local. Changing it while
  the preview runs moves the loopback live.
- **Re-sync volume** sets the sink to 50% and then 100%. Use it when A2DP absolute volume and PipeWire
  disagree, so the headset sounds quiet while the slider reads full.
- **Bluetooth** runs `bluetoothctl connect` on the configured MAC, waits for `bluez_card.*` to appear and
  forces its profile to `a2dp-sink`. If any step fails, the preview starts on local speakers instead.

## Config

`~/.config/capview/config.toml` is written whenever a setting changes:

```toml
bluetooth_mac = "E8:EE:CC:65:9C:A0"
bluetooth_autoconnect = true
preferred_sink = "bluez_output.E8_EE_CC_65_9C_A0.1"
last_mode = "1920x1080@60"
backend = "mpv"
hwdec = true
loopback_latency_ms = 30
```

`loopback_latency_ms` can only be changed here. Raise it if Bluetooth audio crackles.

## Teardown

Capview cleans up on Stop, on window close, on SIGINT/SIGTERM/SIGHUP, and when it crashes or gets killed:

- The player runs under `setpriv --pdeathsig TERM`, so the kernel kills it if Capview dies.
- Each loopback has a tiny guard process holding a pipe from Capview. On a clean stop Capview unloads the
  module and tells the guard to exit. If Capview disappears, the pipe hits EOF and the guard runs
  `pactl unload-module` itself.
- At startup, any `module-loopback` tagged `media.name=capview-loopback` that is still loaded is removed.

## MS2109 quirks

- It's USB 2.0, so only MJPEG gets 1080p. YUYV is bandwidth-limited to tiny resolutions or about 5 fps.
  Capview only offers MJPEG modes and always requests `input_format=mjpeg`.
- Its audio descriptors are wrong (it reports odd rates and channel counts). Current Fedora kernels carry
  a USB-audio quirk for it. If audio sounds sped up or garbled, check `pactl list short sources` for the
  `alsa_input.*MACROSILICON*` source's sample spec.
- It runs hot. Stop the preview when you're not watching. Stop releases the device, so the card goes idle.

## Development

```sh
cd capview
pytest
ruff check . && ruff format --check . && mypy src
```

```
src/capview/
  app.py        Adw.Application, signal handling, stale-loopback sweep
  window.py     the UI
  device.py     v4l2 detection and MJPEG mode parsing
  audio.py      pactl: sinks, sources, loopback + guard, volume
  bluetooth.py  bluetoothctl connect, A2DP profile, sink wait
  video.py      mpv/ffplay command lines and process lifetime
  session.py    sink routing with Bluetooth fallback; owns the running player + loopback
  config.py     TOML config
  proc.py       subprocess helpers
  tasks.py      run blocking work off the GTK thread
```
