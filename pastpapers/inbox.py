"""The Inbox: drop files here (from the iPad or the server) and they get sorted."""

from __future__ import annotations

import logging
import os
import time
from pathlib import Path

from .library import Filed, Library

log = logging.getLogger(__name__)

# Clutter that Macs, iPads and Windows leave behind. Deleted when it's all that's
# left in a folder.
JUNK_NAMES = {".DS_Store", "Thumbs.db", "desktop.ini"}
# Files that are still being written by something.
PARTIAL_SUFFIXES = (".part", ".partial", ".crdownload", ".download", ".tmp")


def _is_junk(name: str) -> bool:
    return name in JUNK_NAMES or name.startswith("._")


def _skip(name: str) -> bool:
    return name.startswith((".", "~$")) or _is_junk(name) or name.lower().endswith(PARTIAL_SUFFIXES)


class Inbox:
    def __init__(self, root: Path, library: Library, settle_seconds: float = 10.0):
        self.root = root
        self.library = library
        self.settle_seconds = settle_seconds
        self._last_seen: dict[Path, tuple[int, int]] = {}

    def scan(self, *, wait_for_settle: bool = True, now: float | None = None) -> list[Filed]:
        """Sort every finished file in the Inbox.

        With ``wait_for_settle`` a file is only taken once it has looked the same
        on two scans in a row and hasn't changed for ``settle_seconds``, so a copy
        that is still coming over the network is left alone.
        """
        now = time.time() if now is None else now
        results: list[Filed] = []
        still_settling: dict[Path, tuple[int, int]] = {}
        for path in self._files():
            try:
                st = path.stat()
            except FileNotFoundError:
                continue
            signature = (st.st_size, st.st_mtime_ns)
            if wait_for_settle:
                last_change = max(st.st_mtime, st.st_ctime)
                if self._last_seen.get(path) != signature or now - last_change < self.settle_seconds:
                    still_settling[path] = signature
                    continue
            try:
                filed = self.library.file(path)
            except OSError as exc:
                log.warning("Couldn't sort %s: %s", path.relative_to(self.root), exc)
                continue
            self._report(path, filed)
            results.append(filed)
        self._last_seen = still_settling
        self._remove_empty_folders(now, 0 if not wait_for_settle else self.settle_seconds)
        return results

    def _files(self) -> list[Path]:
        found = []
        for folder, dirs, files in os.walk(self.root):
            dirs[:] = sorted(d for d in dirs if not d.startswith("."))
            found.extend(Path(folder, name) for name in sorted(files) if not _skip(name))
        return found

    def _report(self, original: Path, filed: Filed) -> None:
        source = original.relative_to(self.root)
        dest = filed.path.relative_to(self.library.root)
        if filed.status == "added":
            log.info("Sorted %s -> %s", source, dest)
        elif filed.status == "duplicate":
            log.info("Already had %s (%s), removed it from the Inbox", source, dest)
        else:
            log.info("Couldn't tell what %s is, moved it to %s", source, dest)

    def _remove_empty_folders(self, now: float, min_age: float) -> None:
        for folder, _, _ in os.walk(self.root, topdown=False):
            path = Path(folder)
            if path == self.root or any(part.startswith(".") for part in path.relative_to(self.root).parts):
                continue
            try:
                # Listed now, not by os.walk, so subfolders removed a moment ago count as gone.
                entries = os.listdir(path)
                if not all(_is_junk(name) for name in entries):
                    continue
                if min_age and now - path.stat().st_mtime < min_age:
                    continue
                for name in entries:
                    (path / name).unlink()
                path.rmdir()
            except OSError:
                pass  # something new arrived, or it's in use; try again next scan
