import tempfile
import unittest
from pathlib import Path

from pastpapers.library import Library
from pastpapers.subjects import SUBJECT_NAMES

PDF = b"%PDF-1.7\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n"


def pdf(text: str = "") -> bytes:
    return PDF + text.encode()


class TempDirTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)
        self.library = Library(self.tmp / "library", SUBJECT_NAMES)
        self.library.prepare()

    def write(self, relative: str, data: bytes) -> Path:
        path = self.tmp / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path
