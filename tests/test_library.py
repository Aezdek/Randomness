import errno
import unittest
from unittest import mock

from pastpapers.names import parse

from helpers import TempDirTest, pdf


class LibraryTest(TempDirTest):
    def test_files_a_paper_under_its_tidy_name(self):
        src = self.write("inbox/0620_s24_qp_42.pdf", pdf())
        filed = self.library.file(src)
        self.assertEqual(filed.status, "added")
        self.assertEqual(
            filed.path, self.tmp / "library/Chemistry (0620)/2024/May-June/Chemistry 2024 May-June P42 QP.pdf"
        )
        self.assertEqual(filed.path.read_bytes(), pdf())
        self.assertFalse(src.exists())
        self.assertTrue(self.library.has(parse("0620_s24_qp_42.pdf")))

    def test_identical_copy_is_dropped(self):
        first = self.library.file(self.write("inbox/0620_s24_qp_42.pdf", pdf()))
        src = self.write("inbox/0620_s24_qp_42 (1).pdf", pdf())
        filed = self.library.file(src)
        self.assertEqual(filed.status, "duplicate")
        self.assertEqual(filed.path, first.path)
        self.assertFalse(src.exists())

    def test_different_file_with_the_same_name_is_kept_too(self):
        first = self.library.file(self.write("inbox/0620_s24_qp_42.pdf", pdf("one")))
        second = self.library.file(self.write("inbox/0620_s24_qp_42.pdf", pdf("two")))
        third = self.library.file(self.write("inbox/0620_s24_qp_42.pdf", pdf("two")))
        self.assertEqual(second.path.name, "Chemistry 2024 May-June P42 QP (2).pdf")
        self.assertEqual(first.path.read_bytes(), pdf("one"))
        self.assertEqual(third.status, "duplicate")
        self.assertEqual(third.path, second.path)

    def test_unrecognised_files_go_to_unsorted(self):
        filed = self.library.file(self.write("inbox/My notes.pdf", pdf()))
        self.assertEqual(filed.status, "unsorted")
        self.assertEqual(filed.path, self.tmp / "library/Unsorted/My notes.pdf")

    def test_web_page_saved_as_pdf_goes_to_unsorted(self):
        filed = self.library.file(self.write("inbox/0620_s24_qp_42.pdf", b"<html>Not found</html>"))
        self.assertEqual(filed.status, "unsorted")
        self.assertFalse(self.library.has(parse("0620_s24_qp_42.pdf")))

    def test_sort_name_can_differ_from_file_name(self):
        src = self.write("library/.incoming/abc.part", pdf())
        filed = self.library.file(src, "0610_w22_ms_31.pdf")
        self.assertEqual(filed.path.name, "Biology 2022 Oct-Nov P31 MS.pdf")

    def test_moves_across_filesystems(self):
        src = self.write("inbox/0620_s24_qp_42.pdf", pdf())
        with mock.patch("pastpapers.library.os.rename", side_effect=OSError(errno.EXDEV, "cross-device")):
            filed = self.library.file(src)
        self.assertEqual(filed.path.read_bytes(), pdf())
        self.assertFalse(src.exists())
        self.assertEqual(list((self.tmp / "library/.incoming").iterdir()), [])

    def test_prepare_clears_interrupted_downloads(self):
        self.write("library/.incoming/old.part", b"half a file")
        self.library.prepare()
        self.assertEqual(list((self.tmp / "library/.incoming").iterdir()), [])


if __name__ == "__main__":
    unittest.main()
