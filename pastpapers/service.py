"""The long-running service: sort the Inbox, and download new papers once a day."""

from __future__ import annotations

import logging
import threading
from pathlib import Path

from .config import Config
from .fetcher import Fetcher, FetchState
from .inbox import Inbox
from .library import Library

log = logging.getLogger(__name__)


def open_library(cfg: Config, data: Path) -> tuple[Library, Inbox]:
    library = Library(data / "library", cfg.subject_names)
    library.prepare()
    (data / "inbox").mkdir(parents=True, exist_ok=True)
    return library, Inbox(data / "inbox", library, cfg.settle_seconds)


def make_fetcher(cfg: Config, data: Path, library: Library) -> Fetcher:
    return Fetcher(cfg.download, library, FetchState(data / "state" / "downloads.json"))


def run(cfg: Config, data: Path, stop: threading.Event) -> None:
    library, inbox = open_library(cfg, data)

    if cfg.download.enabled:
        fetcher = make_fetcher(cfg, data, library)
        thread = threading.Thread(
            target=_download_loop, args=(fetcher, cfg.download.every_hours, stop), name="downloads", daemon=True
        )
        thread.start()
    else:
        log.info("Downloading is off (download.url isn't set in config.toml)")

    log.info("Watching the Inbox, checking every %gs", cfg.scan_seconds)
    while not stop.is_set():
        try:
            inbox.scan()
        except Exception:
            log.exception("Inbox scan failed, will try again")
        stop.wait(cfg.scan_seconds)
    log.info("Stopped")


def _download_loop(fetcher: Fetcher, every_hours: float, stop: threading.Event) -> None:
    while not stop.is_set():
        log.info("Checking for papers to download")
        try:
            fetcher.run(stop)
        except Exception:
            log.exception("Downloading failed, will try again later")
        stop.wait(every_hours * 3600)
