"""Download papers that are missing from the library."""

from __future__ import annotations

import json
import logging
import os
import shutil
import threading
import time
import urllib.error
import urllib.request
from collections import Counter
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path
from typing import Callable, Collection, Iterator

from .config import DownloadSettings
from .library import Library, looks_like_pdf
from .names import Paper, parse

log = logging.getLogger(__name__)

USER_AGENT = "pastpapers/1.0"
SESSION_END = {"m": (3, 31), "s": (6, 30), "w": (11, 30)}
VARIANTS = {"m": ["2"], "s": ["1", "2", "3"], "w": ["1", "2", "3"]}
PER_SESSION_TYPES = {"er", "gt"}  # one per session, not one per paper
RETRY_AFTER_DAYS = 7
GIVE_UP_AFTER_DAYS = 548  # still missing ~18 months after the exam: it isn't coming
MAX_ERRORS_IN_A_ROW = 10
ERROR_PAUSE_SECONDS = 30.0


class FetchError(Exception):
    pass


def session_end(session: str, year: int) -> date:
    month, day = SESSION_END[session]
    return date(year, month, day)


def wanted(
    dl: DownloadSettings,
    today: date,
    codes: Collection[str] | None = None,
    years: Collection[int] | None = None,
) -> Iterator[Paper]:
    """Every paper the settings ask for, newest first, question paper and mark
    scheme next to each other so pairs arrive together."""
    for year in range(dl.last_year or today.year, dl.first_year - 1, -1):
        if years and year not in years:
            continue
        for session in sorted(dl.sessions, key="msw".index, reverse=True):
            if today < session_end(session, year):
                continue
            for code in dl.subjects:
                if codes and code not in codes:
                    continue
                for number in dl.papers_for(code):
                    for variant in VARIANTS[session]:
                        for kind in dl.types:
                            if kind not in PER_SESSION_TYPES:
                                yield Paper(code, session, year, kind, f"{number}{variant}")
                for kind in dl.types:
                    if kind in PER_SESSION_TYPES:
                        yield Paper(code, session, year, kind)


class FetchState:
    """Remembers which papers the site didn't have, so they aren't asked for daily."""

    def __init__(self, path: Path):
        self.path = path
        self.missing: dict[str, str] = {}
        if path.exists():
            try:
                data = json.loads(path.read_text())
                self.missing = {str(k): str(v) for k, v in data["missing"].items()}
            except (OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
                log.warning("Ignoring unreadable %s (%s), starting fresh", path.name, exc)

    def should_try(self, paper: Paper, today: date) -> bool:
        checked = self.missing.get(paper.cambridge_name)
        if checked is None:
            return True
        try:
            checked_on = date.fromisoformat(checked)
        except ValueError:
            return True
        if checked_on >= session_end(paper.session, paper.year) + timedelta(days=GIVE_UP_AFTER_DAYS):
            return False
        return (today - checked_on).days >= RETRY_AFTER_DAYS

    def mark_missing(self, paper: Paper, today: date) -> None:
        self.missing[paper.cambridge_name] = today.isoformat()

    def forget(self, paper: Paper) -> None:
        self.missing.pop(paper.cambridge_name, None)

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps({"missing": self.missing}, indent=1, sort_keys=True))
        os.replace(tmp, self.path)


@dataclass(frozen=True)
class Probe:
    url: str
    status: int | None
    content_type: str
    is_pdf: bool
    error: str | None = None


class Fetcher:
    def __init__(
        self,
        settings: DownloadSettings,
        library: Library,
        state: FetchState,
        *,
        opener: urllib.request.OpenerDirector | None = None,
        today: Callable[[], date] = date.today,
        sleep: Callable[[float], None] = time.sleep,
        error_pause: float = ERROR_PAUSE_SECONDS,
    ):
        self.settings = settings
        self.library = library
        self.state = state
        self._opener = opener or urllib.request.build_opener()
        self._today = today
        self._sleep = sleep
        self._error_pause = error_pause

    def url_for(self, paper: Paper) -> str:
        return self.settings.url_for(paper, self.library.subject_names.get(paper.code, paper.code))

    def plan(self, codes: Collection[str] | None = None, years: Collection[int] | None = None) -> list[Paper]:
        """The papers a run would ask the site for right now."""
        today = self._today()
        return [
            p
            for p in wanted(self.settings, today, codes, years)
            if not self.library.has(p) and self.state.should_try(p, today)
        ]

    def run(
        self,
        stop: threading.Event | None = None,
        codes: Collection[str] | None = None,
        years: Collection[int] | None = None,
    ) -> Counter:
        today = self._today()
        counts: Counter = Counter()
        errors_in_a_row = 0
        asked_before = False
        try:
            for paper in wanted(self.settings, today, codes, years):
                if stop is not None and stop.is_set():
                    break
                if self.library.has(paper):
                    counts["already had"] += 1
                    continue
                if not self.state.should_try(paper, today):
                    counts["known missing"] += 1
                    continue
                if asked_before:
                    self._wait(self.settings.delay_seconds, stop)
                asked_before = True
                try:
                    found = self._download(paper)
                except FetchError as exc:
                    counts["errors"] += 1
                    errors_in_a_row += 1
                    log.warning("Couldn't download %s: %s", paper.cambridge_name, exc)
                    if errors_in_a_row >= MAX_ERRORS_IN_A_ROW:
                        log.error(
                            "Stopped after %d errors in a row. Is the site down, or is download.url wrong? "
                            "Test it with: docker compose run --rm papers check-source",
                            errors_in_a_row,
                        )
                        break
                    self._wait(self._error_pause, stop)
                    continue
                errors_in_a_row = 0
                if found:
                    counts["downloaded"] += 1
                    self.state.forget(paper)
                else:
                    counts["not on the site"] += 1
                    self.state.mark_missing(paper, today)
                if (counts["downloaded"] + counts["not on the site"]) % 50 == 0:
                    self.state.save()
        finally:
            self.state.save()
        self._summarise(counts)
        return counts

    def probe(self, filename: str) -> Probe:
        """Ask the site for one paper without saving it, to test download.url."""
        paper = parse(filename)
        if paper is None:
            raise ValueError(f"{filename!r} isn't a Cambridge file name like 0620_s24_qp_42.pdf")
        url = self.url_for(paper)
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with self._opener.open(request, timeout=60) as response:
                head = response.read(1024)
                return Probe(url, response.status, response.headers.get("Content-Type", ""), b"%PDF-" in head)
        except urllib.error.HTTPError as exc:
            exc.close()
            return Probe(url, exc.code, exc.headers.get("Content-Type", ""), False)
        except OSError as exc:
            return Probe(url, None, "", False, str(getattr(exc, "reason", exc)))

    def _download(self, paper: Paper) -> bool:
        """True if the paper was downloaded, False if the site doesn't have it."""
        request = urllib.request.Request(self.url_for(paper), headers={"User-Agent": USER_AGENT})
        tmp = self.library.new_incoming_file()
        try:
            try:
                with self._opener.open(request, timeout=60) as response, tmp.open("wb") as out:
                    shutil.copyfileobj(response, out)
            except urllib.error.HTTPError as exc:
                exc.close()
                if exc.code in (404, 410):
                    return False
                raise FetchError(f"the site answered HTTP {exc.code}") from None
            except OSError as exc:  # includes URLError and timeouts
                raise FetchError(str(getattr(exc, "reason", exc))) from None
            if not looks_like_pdf(tmp):
                return False  # some sites answer a missing file with an ordinary web page
            filed = self.library.file(tmp, paper.cambridge_name)
            log.info("Downloaded %s", filed.path.relative_to(self.library.root))
            return True
        finally:
            tmp.unlink(missing_ok=True)

    def _wait(self, seconds: float, stop: threading.Event | None) -> None:
        if seconds <= 0:
            return
        if stop is not None:
            stop.wait(seconds)
        else:
            self._sleep(seconds)

    def _summarise(self, counts: Counter) -> None:
        if not counts:
            return
        log.info("Downloads done: %s", ", ".join(f"{n} {label}" for label, n in counts.items()))
        if counts["not on the site"] >= 20 and not counts["downloaded"] and not counts["already had"]:
            log.warning(
                "The site didn't have any of the papers asked for. Check download.url with: "
                "docker compose run --rm papers check-source"
            )
