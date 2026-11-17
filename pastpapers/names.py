"""Read Cambridge past paper file names and build tidy ones.

Cambridge names files like ``0620_s24_qp_42.pdf``: syllabus code, session letter
and year, document type, then paper number and variant. Students' copies are often
renamed a little ("0620-S24-QP-42 (1).PDF"), so the parser is lenient about
separators, case and extra text around the code.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Mapping

SESSIONS = {"m": "Feb-March", "s": "May-June", "w": "Oct-Nov", "y": "Specimen"}

DOC_TYPES = {
    "qp": "QP",
    "ms": "MS",
    "in": "Insert",
    "er": "Examiner Report",
    "gt": "Grade Thresholds",
    "ci": "Confidential Instructions",
    "pm": "Pre-release",
    "sf": "Source Files",
    "sp": "QP",  # specimen paper
    "sm": "MS",  # specimen mark scheme
}

_SEP = r"[ _.\-]*"
_PATTERN = re.compile(
    rf"(?<!\d)(?P<code>\d{{4}}){_SEP}(?P<session>[mswy])(?P<yy>\d{{2}}){_SEP}"
    rf"(?P<kind>{'|'.join(DOC_TYPES)})(?![a-z])"
    # Either a one or two digit component, or no digits at all after the type.
    rf"(?:{_SEP}(?P<component>\d{{1,2}})(?!\d)|(?!{_SEP}\d))",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class Paper:
    code: str  # syllabus code, e.g. "0620"
    session: str  # m, s, w or y (specimen)
    year: int
    kind: str  # qp, ms, in, er, ...
    component: str | None = None  # "42" = paper 4, variant 2
    ext: str = ".pdf"

    @property
    def cambridge_name(self) -> str:
        component = f"_{self.component}" if self.component else ""
        return f"{self.code}_{self.session}{self.year % 100:02d}_{self.kind}{component}{self.ext}"


def parse(filename: str) -> Paper | None:
    """Return the paper a file name refers to, or None if it isn't a Cambridge name."""
    match = _PATTERN.search(filename)
    if not match:
        return None
    ext = PurePosixPath(filename).suffix.lower()
    if not re.fullmatch(r"\.[a-z0-9]{1,5}", ext):
        ext = ".pdf"
    return Paper(
        code=match["code"],
        session=match["session"].lower(),
        year=2000 + int(match["yy"]),
        kind=match["kind"].lower(),
        component=match["component"],
        ext=ext,
    )


def tidy_path(paper: Paper, subject_names: Mapping[str, str]) -> PurePosixPath:
    """Where a paper lives in the library, e.g.

    ``Chemistry (0620)/2024/May-June/Chemistry 2024 May-June P42 QP.pdf``

    The file name repeats the subject, year and session because it becomes the
    note's title when imported into Notability.
    """
    name = subject_names.get(paper.code)
    folder = f"{name} ({paper.code})" if name else paper.code
    session = SESSIONS[paper.session]
    words = [name or paper.code, str(paper.year), session]
    if paper.component:
        words.append(f"P{paper.component}")
    words.append(DOC_TYPES[paper.kind])
    filename = " ".join(words).replace("/", "-") + paper.ext
    return PurePosixPath(folder.replace("/", "-"), str(paper.year), session, filename)
