from __future__ import annotations

import atexit
import contextlib
import threading
from collections.abc import Callable
from dataclasses import dataclass, field

from capview import audio, bluetooth
from capview.audio import Loopback, Sink
from capview.bluetooth import BluetoothError
from capview.device import CaptureDevice, Mode
from capview.proc import CommandError
from capview.video import VideoPlayer


@dataclass
class Route:
    sink: Sink | None
    source: str | None
    notes: list[str] = field(default_factory=list)


def resolve_route(preferred: str, bt_mac: str, use_bluetooth: bool) -> Route:
    """Pick the output sink, bringing up Bluetooth if it is wanted, else falling back locally."""
    notes: list[str] = []
    sinks = audio.list_sinks()
    by_name = {s.name: s for s in sinks}
    chosen = by_name.get(preferred)

    wants_bt = use_bluetooth and (not preferred or preferred.startswith("bluez_"))
    if chosen is None and wants_bt:
        try:
            chosen = bluetooth.bring_up_sink(bt_mac)
        except BluetoothError as e:
            notes.append(f"{e}. Falling back to local speakers.")

    if chosen is None:
        local = [s for s in sinks if not s.is_bluetooth]
        chosen = local[0] if local else (sinks[0] if sinks else None)
        if chosen and preferred and not wants_bt:
            notes.append(f"{preferred} is gone; using {chosen.description}.")

    source = audio.find_capture_source()
    if source is None:
        notes.append("Capture card audio source not found; video only.")
    return Route(chosen, source, notes)


class Session:
    """Owns the running video process and loopback, and tears both down no matter how we exit."""

    def __init__(self, on_video_exit: Callable[[int], None]) -> None:
        self._lock = threading.Lock()
        self._loopback: Loopback | None = None
        self.player = VideoPlayer(on_video_exit)
        self.device: CaptureDevice | None = None
        self.mode: Mode | None = None
        atexit.register(self.stop)

    @property
    def running(self) -> bool:
        return self.player.running

    @property
    def sink(self) -> str | None:
        return self._loopback.sink if self._loopback else None

    def start_audio(self, source: str, sink: str, latency_ms: int) -> None:
        with self._lock:
            if self._loopback:
                self._loopback.unload()
                self._loopback = None
            self._loopback = Loopback(source, sink, latency_ms)

    def switch_sink(self, sink: str, latency_ms: int) -> None:
        current = self._loopback
        if current and current.sink != sink:
            self.start_audio(current.source, sink, latency_ms)

    def start_video(self, device: CaptureDevice, mode: Mode, command: list[str]) -> None:
        self.device, self.mode = device, mode
        self.player.start(command)

    def stop_audio(self) -> None:
        with self._lock:
            loopback, self._loopback = self._loopback, None
        if loopback:
            with contextlib.suppress(CommandError, OSError):
                loopback.unload()

    def stop(self) -> None:
        self.player.stop()
        self.stop_audio()
