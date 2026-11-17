"""The sorted folder of papers that the iPad browses."""

from __future__ import annotations

import errno
import filecmp
import os
import shutil
import threading
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

from .names import Paper, parse, tidy_path

UNSORTED = "Unsorted"
INCOMING = ".incoming"  # hidden from the file share; holds downloads in progress


def looks_like_pdf(path: Path) -> bool:
    try:
        with path.open("rb") as fh:
            return b"%PDF-" in fh.read(1024)
    except OSError:
        return False


@dataclass(frozen=True)
class Filed:
    path: Path  # where the file is now (for a duplicate, the copy already there)
    status: str  # "added", "duplicate" or "unsorted"
    paper: Paper | None


class Library:
    def __init__(self, root: Path, subject_names: Mapping[str, str]):
        self.root = root
        self.subject_names = subject_names
        self._lock = threading.Lock()

    def prepare(self) -> None:
        """Create the folder and clear out downloads that were cut off last time."""
        incoming = self.root / INCOMING
        incoming.mkdir(parents=True, exist_ok=True)
        for leftover in incoming.iterdir():
            if leftover.is_file():
                leftover.unlink()

    def path_for(self, paper: Paper) -> Path:
        return self.root / tidy_path(paper, self.subject_names)

    def has(self, paper: Paper) -> bool:
        return self.path_for(paper).exists()

    def new_incoming_file(self) -> Path:
        incoming = self.root / INCOMING
        incoming.mkdir(parents=True, exist_ok=True)
        return incoming / f"{uuid.uuid4().hex}.part"

    def file(self, src: Path, name: str | None = None) -> Filed:
        """Move ``src`` into the library under its tidy name.

        ``name`` is the name to sort by (defaults to the file's own name). Files
        that aren't recognisable Cambridge papers go to ``Unsorted`` so nothing
        is ever lost.
        """
        name = Path(name or src.name).name
        paper = parse(name)
        if paper is not None and paper.ext == ".pdf" and not looks_like_pdf(src):
            paper = None  # e.g. an error page that was saved with a .pdf name
        if paper is None:
            dest = self.root / UNSORTED / name
        else:
            dest = self.path_for(paper)
        with self._lock:
            return self._place(src, dest, paper)

    def _place(self, src: Path, dest: Path, paper: Paper | None) -> Filed:
        candidate, number = dest, 1
        while candidate.exists():
            if filecmp.cmp(src, candidate, shallow=False):
                src.unlink()
                return Filed(candidate, "duplicate", paper)
            number += 1
            candidate = dest.with_name(f"{dest.stem} ({number}){dest.suffix}")
        candidate.parent.mkdir(parents=True, exist_ok=True)
        self._move(src, candidate)
        return Filed(candidate, "added" if paper else "unsorted", paper)

    def _move(self, src: Path, dest: Path) -> None:
        try:
            os.rename(src, dest)
            return
        except OSError as exc:
            if exc.errno != errno.EXDEV:
                raise
        # Different filesystems: copy next to the destination, then swap it in.
        tmp = self.new_incoming_file()
        try:
            shutil.copyfile(src, tmp)
            os.replace(tmp, dest)
        finally:
            tmp.unlink(missing_ok=True)
        src.unlink()
