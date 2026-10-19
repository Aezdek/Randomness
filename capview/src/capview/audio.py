from __future__ import annotations

import json
import subprocess
import time
from collections.abc import Callable
from dataclasses import dataclass

from capview.proc import CommandError, run

LOOPBACK_TAG = "media.name=capview-loopback"
CAPTURE_MARKER = "MACROSILICON"


@dataclass(frozen=True)
class Sink:
    name: str
    description: str

    @property
    def is_bluetooth(self) -> bool:
        return self.name.startswith(("bluez_output.", "bluez_sink."))

    @property
    def label(self) -> str:
        kind = "Bluetooth" if self.is_bluetooth else "Local"
        return f"{self.description} ({kind})"


def _short_list(kind: str) -> list[list[str]]:
    return [line.split("\t") for line in run(["pactl", "list", "short", kind]).splitlines() if line]


def parse_short_names(text: str) -> list[str]:
    return [fields[1] for line in text.splitlines() if len(fields := line.split("\t")) > 1]


def _sink_descriptions() -> dict[str, str]:
    try:
        data = json.loads(run(["pactl", "--format=json", "list", "sinks"]))
    except (CommandError, json.JSONDecodeError):
        return {}
    return {s["name"]: s.get("description") or s["name"] for s in data if "name" in s}


def list_sinks() -> list[Sink]:
    names = parse_short_names(run(["pactl", "list", "short", "sinks"]))
    descriptions = _sink_descriptions() if names else {}
    return [Sink(name, descriptions.get(name, name)) for name in names]


def list_cards() -> list[str]:
    return [fields[1] for fields in _short_list("cards") if len(fields) > 1]


def find_capture_source(names: list[str] | None = None) -> str | None:
    if names is None:
        names = parse_short_names(run(["pactl", "list", "short", "sources"]))
    for name in names:
        if name.startswith("alsa_input.") and CAPTURE_MARKER in name.upper():
            return name
    return None


def wait_for_sink(match: Callable[[Sink], bool], timeout: float) -> Sink | None:
    deadline = time.monotonic() + timeout
    while True:
        for sink in list_sinks():
            if match(sink):
                return sink
        if time.monotonic() >= deadline:
            return None
        time.sleep(0.5)


def set_card_profile(card: str, profiles: tuple[str, ...]) -> str:
    errors = []
    for profile in profiles:
        try:
            run(["pactl", "set-card-profile", card, profile])
            return profile
        except CommandError as e:
            errors.append(f"{profile}: {e}")
    raise CommandError("; ".join(errors))


def set_sink_volume(sink: str, percent: int) -> None:
    run(["pactl", "set-sink-volume", sink, f"{percent}%"])


def resync_volume(sink: str) -> None:
    """Nudge A2DP absolute volume so the headset and PipeWire agree again."""
    set_sink_volume(sink, 50)
    time.sleep(0.4)
    set_sink_volume(sink, 100)


def unload_stale_loopbacks() -> int:
    count = 0
    for fields in _short_list("modules"):
        if len(fields) > 2 and fields[1] == "module-loopback" and LOOPBACK_TAG in fields[2]:
            try:
                run(["pactl", "unload-module", fields[0]])
                count += 1
            except CommandError:
                pass
    return count


class Loopback:
    """A module-loopback that is unloaded on stop, and by a guard process if we die first."""

    _GUARD = 'read -r _ || exec pactl unload-module "$0"'

    def __init__(self, source: str, sink: str, latency_ms: int) -> None:
        self.source = source
        self.sink = sink
        self.module = int(
            run(
                [
                    "pactl",
                    "load-module",
                    "module-loopback",
                    f"source={source}",
                    f"sink={sink}",
                    f"latency_msec={latency_ms}",
                    "source_dont_move=true",
                    "sink_dont_move=true",
                    f"sink_input_properties={LOOPBACK_TAG}",
                ]
            ).strip()
        )
        self._guard: subprocess.Popen[bytes] | None = subprocess.Popen(
            ["sh", "-c", self._GUARD, str(self.module)],
            stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )

    def unload(self) -> None:
        guard, self._guard = self._guard, None
        if guard is None:
            return
        try:
            run(["pactl", "unload-module", str(self.module)])
            assert guard.stdin is not None
            guard.stdin.write(b"done\n")
            guard.stdin.close()
        except (CommandError, OSError):
            if guard.stdin:
                guard.stdin.close()
        guard.wait(timeout=5)
