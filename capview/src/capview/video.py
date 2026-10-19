from __future__ import annotations

import signal
import subprocess
import threading
from collections.abc import Callable
from enum import StrEnum

from capview import APP_NAME
from capview.device import Mode
from capview.proc import die_with_parent

LAVF_LOW_LATENCY = {"fflags": "+nobuffer", "analyzeduration": "0", "probesize": "32"}


class Backend(StrEnum):
    MPV = "mpv"
    FFPLAY = "ffplay"


def mpv_command(node: str, mode: Mode, hwdec: bool) -> list[str]:
    lavf = {
        "input_format": "mjpeg",
        "video_size": mode.size,
        "framerate": f"{mode.fps:g}",
        **LAVF_LOW_LATENCY,
    }
    return [
        "mpv",
        f"--title={APP_NAME} — {mode.label}",
        "--force-window=immediate",
        "--profile=low-latency",
        "--untimed",
        "--no-cache",
        "--demuxer-readahead-secs=0",
        "--demuxer-lavf-analyzeduration=0",
        "--demuxer-lavf-probesize=32",
        "--video-latency-hacks=yes",
        "--interpolation=no",
        "--no-audio",
        "--osc=no",
        *(f"--demuxer-lavf-o-append={k}={v}" for k, v in lavf.items()),
        *(["--hwdec=vaapi", "--hwdec-codecs=mjpeg"] if hwdec else ["--hwdec=no"]),
        f"av://v4l2:{node}",
    ]


def ffplay_command(node: str, mode: Mode) -> list[str]:
    return [
        "ffplay",
        "-hide_banner",
        "-loglevel", "warning",
        "-window_title", f"{APP_NAME} — {mode.label}",
        "-fflags", LAVF_LOW_LATENCY["fflags"],
        "-flags", "low_delay",
        "-analyzeduration", LAVF_LOW_LATENCY["analyzeduration"],
        "-probesize", LAVF_LOW_LATENCY["probesize"],
        "-framedrop",
        "-sync", "ext",
        "-an",
        "-f", "v4l2",
        "-input_format", "mjpeg",
        "-video_size", mode.size,
        "-framerate", f"{mode.fps:g}",
        "-i", node,
    ]  # fmt: skip


def build_command(backend: Backend, node: str, mode: Mode, hwdec: bool) -> list[str]:
    if backend is Backend.FFPLAY:
        return ffplay_command(node, mode)
    return mpv_command(node, mode, hwdec)


class VideoPlayer:
    def __init__(self, on_exit: Callable[[int], None]) -> None:
        self._on_exit = on_exit
        self._proc: subprocess.Popen[bytes] | None = None

    @property
    def running(self) -> bool:
        return self._proc is not None and self._proc.poll() is None

    def start(self, command: list[str]) -> None:
        self.stop()
        proc = subprocess.Popen(die_with_parent(command), stdin=subprocess.DEVNULL)
        self._proc = proc
        threading.Thread(target=self._watch, args=(proc,), daemon=True).start()

    def _watch(self, proc: subprocess.Popen[bytes]) -> None:
        code = proc.wait()
        if self._proc is proc:
            self._proc = None
            self._on_exit(code)

    def stop(self) -> None:
        proc, self._proc = self._proc, None
        if proc is None or proc.poll() is not None:
            return
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
