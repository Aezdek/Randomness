"""Settings, read from config.toml."""

from __future__ import annotations

import re
import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from string import Formatter
from typing import Any
from urllib.parse import quote, urlsplit

from .names import Paper
from .subjects import DEFAULT_PAPERS, FALLBACK_PAPERS, SUBJECT_NAMES

DOWNLOAD_SESSIONS = ("m", "s", "w")
DOWNLOAD_TYPES = ("qp", "ms", "in", "er", "gt", "ci", "pm")
URL_FIELDS = ("filename", "code", "subject", "year", "yy", "session", "type", "component")
MY_SUBJECTS = ["0620", "0625", "0580", "0478", "0510", "0610"]


class ConfigError(Exception):
    pass


@dataclass
class DownloadSettings:
    url: str = ""
    subjects: list[str] = field(default_factory=lambda: list(MY_SUBJECTS))
    first_year: int = 2018
    last_year: int | None = None
    sessions: list[str] = field(default_factory=lambda: ["m", "s", "w"])
    types: list[str] = field(default_factory=lambda: ["qp", "ms", "in"])
    delay_seconds: float = 1.0
    every_hours: float = 24.0
    papers: dict[str, list[int]] = field(default_factory=lambda: {k: list(v) for k, v in DEFAULT_PAPERS.items()})

    @property
    def enabled(self) -> bool:
        return bool(self.url)

    def papers_for(self, code: str) -> list[int]:
        return self.papers.get(code) or FALLBACK_PAPERS

    def url_for(self, paper: Paper, subject: str) -> str:
        values = {
            "filename": paper.cambridge_name,
            "code": paper.code,
            "subject": subject,
            "year": str(paper.year),
            "yy": f"{paper.year % 100:02d}",
            "session": paper.session,
            "type": paper.kind,
            "component": paper.component or "",
        }
        return self.url.format(**{key: quote(value, safe="()") for key, value in values.items()})


@dataclass
class Config:
    scan_seconds: float = 15.0
    settle_seconds: float = 10.0
    subject_name_overrides: dict[str, str] = field(default_factory=dict)
    download: DownloadSettings = field(default_factory=DownloadSettings)

    @property
    def subject_names(self) -> dict[str, str]:
        return {**SUBJECT_NAMES, **self.subject_name_overrides}


def load(path: Path) -> Config:
    if path.is_dir():
        raise ConfigError(
            f"{path} is a folder, not a file. Docker makes that folder when config.toml is missing: "
            "delete it, then run ./setup.sh (or copy config.example.toml to config.toml)."
        )
    if not path.exists():
        raise ConfigError(f"{path} not found. Run ./setup.sh, or copy config.example.toml to config.toml.")
    try:
        with path.open("rb") as fh:
            raw = tomllib.load(fh)
    except tomllib.TOMLDecodeError as exc:
        raise ConfigError(f"config.toml isn't valid: {exc}") from None
    return parse(raw)


def parse(raw: dict[str, Any]) -> Config:
    root = _Table(raw, "")
    cfg = Config()

    inbox = root.table("inbox")
    cfg.scan_seconds = inbox.number("scan_seconds", cfg.scan_seconds, minimum=1)
    cfg.settle_seconds = inbox.number("settle_seconds", cfg.settle_seconds, minimum=0)
    inbox.finish()

    names = root.table("subject_names")
    for code in names.remaining():
        name = names.string(code, "")
        if not _is_code(code) or not name.strip() or "/" in name:
            raise ConfigError(f'subject_names: use lines like "0510" = "English" (problem with {code!r})')
        cfg.subject_name_overrides[code] = name.strip()
    names.finish()

    d = root.table("download")
    dl = cfg.download
    dl.url = d.string("url", "").strip()
    dl.subjects = d.strings("subjects", dl.subjects)
    dl.first_year = d.integer("first_year", dl.first_year)
    dl.last_year = d.integer("last_year", None)
    dl.sessions = d.strings("sessions", dl.sessions)
    dl.types = d.strings("types", dl.types)
    dl.delay_seconds = d.number("delay_seconds", dl.delay_seconds, minimum=0)
    dl.every_hours = d.number("every_hours", dl.every_hours, minimum=1)
    papers = d.table("papers")
    for code in papers.remaining():
        numbers = papers.integers(code)
        if not _is_code(code) or not numbers or any(not 1 <= n <= 9 for n in numbers):
            raise ConfigError(f'download.papers: use lines like "0620" = [1, 2, 3, 4, 5, 6] (problem with {code!r})')
        dl.papers[code] = numbers
    papers.finish()
    d.finish()
    root.finish()

    _check_download(dl)
    return cfg


def _check_download(dl: DownloadSettings) -> None:
    for code in dl.subjects:
        if not _is_code(code):
            raise ConfigError(f"download.subjects: {code!r} isn't a 4-digit syllabus code like \"0620\"")
    if not 2000 <= dl.first_year <= 2099:
        raise ConfigError("download.first_year should be a year like 2018")
    if dl.last_year is not None and not dl.first_year <= dl.last_year <= 2099:
        raise ConfigError("download.last_year should be a year no earlier than first_year")
    for session in dl.sessions:
        if session not in DOWNLOAD_SESSIONS:
            raise ConfigError(f"download.sessions: {session!r} should be one of m, s, w")
    for kind in dl.types:
        if kind not in DOWNLOAD_TYPES:
            raise ConfigError(f"download.types: {kind!r} should be one of {', '.join(DOWNLOAD_TYPES)}")
    if not dl.url:
        return
    if urlsplit(dl.url).scheme not in ("http", "https"):
        raise ConfigError("download.url should start with https://")
    try:
        fields = {name for _, name, _, _ in Formatter().parse(dl.url) if name is not None}
    except ValueError as exc:
        raise ConfigError(f"download.url: {exc}") from None
    unknown = fields - set(URL_FIELDS)
    if unknown:
        raise ConfigError(
            f"download.url: unknown placeholder {{{sorted(unknown)[0]}}}. "
            f"You can use: {' '.join('{' + f + '}' for f in URL_FIELDS)}"
        )
    if not fields:
        raise ConfigError("download.url needs placeholders, e.g. https://example.com/papers/{filename}")


def _is_code(value: str) -> bool:
    return bool(re.fullmatch(r"\d{4}", value))


class _Table:
    """A TOML table that reports wrong types and unknown keys in plain words."""

    def __init__(self, data: Any, where: str):
        if not isinstance(data, dict):
            raise ConfigError(f"[{where}] should be a section")
        self._data = dict(data)
        self._where = where

    def _name(self, key: str) -> str:
        return f"{self._where}.{key}" if self._where else key

    def remaining(self) -> list[str]:
        return list(self._data)

    def table(self, key: str) -> _Table:
        return _Table(self._data.pop(key, {}), self._name(key))

    def string(self, key: str, default: str) -> str:
        value = self._data.pop(key, default)
        if not isinstance(value, str):
            raise ConfigError(f'{self._name(key)} should be text in quotes, like "..."')
        return value

    def strings(self, key: str, default: list[str]) -> list[str]:
        value = self._data.pop(key, default)
        if not isinstance(value, list) or not all(isinstance(v, str) for v in value):
            raise ConfigError(f'{self._name(key)} should be a list of text, like ["a", "b"]')
        return list(value)

    def integer(self, key: str, default: int | None) -> int | None:
        value = self._data.pop(key, default)
        if value is not None and (isinstance(value, bool) or not isinstance(value, int)):
            raise ConfigError(f"{self._name(key)} should be a whole number")
        return value

    def integers(self, key: str) -> list[int]:
        value = self._data.pop(key)
        if not isinstance(value, list) or any(isinstance(v, bool) or not isinstance(v, int) for v in value):
            raise ConfigError(f"{self._name(key)} should be a list of whole numbers, like [1, 2, 3]")
        return list(value)

    def number(self, key: str, default: float, minimum: float) -> float:
        value = self._data.pop(key, default)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or value < minimum:
            raise ConfigError(f"{self._name(key)} should be a number, at least {minimum:g}")
        return float(value)

    def finish(self) -> None:
        if self._data:
            raise ConfigError(f"Unknown setting: {self._name(next(iter(self._data)))}")
