from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path

from capview.proc import run

MARKER = "MACROSILICON"
BY_ID = Path("/dev/v4l/by-id")

_FORMAT = re.compile(r"\[\d+\]:\s*'(\w+)'")
_SIZE = re.compile(r"Size:\s*Discrete\s+(\d+)x(\d+)")
_FPS = re.compile(r"\((\d+(?:\.\d+)?)\s*fps\)")
_MODE_KEY = re.compile(r"^(\d+)x(\d+)@(\d+(?:\.\d+)?)$")


@dataclass(frozen=True)
class Mode:
    width: int
    height: int
    fps: float

    @property
    def size(self) -> str:
        return f"{self.width}x{self.height}"

    @property
    def key(self) -> str:
        return f"{self.size}@{self.fps:g}"

    @property
    def label(self) -> str:
        return f"{self.width}×{self.height} @ {self.fps:g} fps"

    @classmethod
    def parse(cls, key: str) -> Mode | None:
        match = _MODE_KEY.match(key.strip())
        if not match:
            return None
        return cls(int(match[1]), int(match[2]), float(match[3]))


@dataclass(frozen=True)
class CaptureDevice:
    name: str
    node: str
    by_id: str | None


def parse_device_list(text: str) -> dict[str, list[str]]:
    devices: dict[str, list[str]] = {}
    current: list[str] | None = None
    for line in text.splitlines():
        if not line.strip():
            continue
        if line[0].isspace():
            if current is not None:
                current.append(line.strip())
        else:
            current = devices.setdefault(line.strip().rstrip(":"), [])
    return devices


def parse_mjpeg_modes(text: str) -> list[Mode]:
    modes: set[Mode] = set()
    in_mjpeg = False
    size: tuple[int, int] | None = None
    for line in text.splitlines():
        if fmt := _FORMAT.search(line):
            in_mjpeg = fmt[1] == "MJPG"
            size = None
        elif not in_mjpeg:
            continue
        elif dims := _SIZE.search(line):
            size = (int(dims[1]), int(dims[2]))
        elif size and (fps := _FPS.search(line)):
            modes.add(Mode(*size, round(float(fps[1]), 3)))
    return sorted(modes, key=lambda m: (m.width * m.height, m.fps), reverse=True)


def by_id_links(directory: Path = BY_ID) -> dict[str, str]:
    """Map /dev/videoN -> its MACROSILICON by-id symlink, capture interface (index0) first."""
    if not directory.is_dir():
        return {}
    links = sorted(
        (p for p in directory.iterdir() if MARKER in p.name.upper()),
        key=lambda p: ("index0" not in p.name, p.name),
    )
    resolved: dict[str, str] = {}
    for link in links:
        resolved.setdefault(os.path.realpath(link), str(link))
    return resolved


def list_modes(node: str) -> list[Mode]:
    return parse_mjpeg_modes(run(["v4l2-ctl", "-d", node, "--list-formats-ext"]))


def find_capture_device() -> tuple[CaptureDevice, list[Mode]] | None:
    links = by_id_links()
    listing = parse_device_list(run(["v4l2-ctl", "--list-devices"], allow_failure=True))
    for name, nodes in listing.items():
        videos = [n for n in nodes if n.startswith("/dev/video")]
        if MARKER not in name.upper() and not any(n in links for n in videos):
            continue
        videos.sort(key=lambda n: n not in links or "index0" not in links[n])
        for node in videos:
            if modes := list_modes(node):
                return CaptureDevice(name, node, links.get(node)), modes
    for node, link in links.items():
        if modes := list_modes(node):
            return CaptureDevice(Path(link).name, node, link), modes
    return None


def pick_mode(modes: list[Mode], preferred: str) -> Mode | None:
    wanted = Mode.parse(preferred)
    if wanted in modes:
        return wanted
    return modes[0] if modes else None
