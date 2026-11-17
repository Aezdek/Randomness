import unittest
from pathlib import PurePosixPath

from pastpapers.names import Paper, parse, tidy_path
from pastpapers.subjects import SUBJECT_NAMES


class ParseTest(unittest.TestCase):
    def test_standard_cambridge_name(self):
        self.assertEqual(parse("0620_s24_qp_42.pdf"), Paper("0620", "s", 2024, "qp", "42", ".pdf"))

    def test_renamed_copies_still_parse(self):
        expected = Paper("0620", "s", 2024, "qp", "42", ".pdf")
        for name in [
            "0620_S24_QP_42.PDF",
            "0620-s24-qp-42.pdf",
            "0620 s24 qp 42.pdf",
            "0620_s24_qp42.pdf",
            "0620_s24_qp_42 (1).pdf",
            "Chemistry 0620_s24_qp_42.pdf",
            "0620.s24.qp.42.pdf",
        ]:
            with self.subTest(name=name):
                self.assertEqual(parse(name), expected)

    def test_session_documents_without_a_paper_number(self):
        self.assertEqual(parse("0620_w23_er.pdf"), Paper("0620", "w", 2023, "er", None, ".pdf"))
        self.assertEqual(parse("0580_m24_gt.pdf"), Paper("0580", "m", 2024, "gt", None, ".pdf"))

    def test_single_digit_paper_and_specimens(self):
        self.assertEqual(parse("0620_y23_sp_4.pdf"), Paper("0620", "y", 2023, "sp", "4", ".pdf"))
        self.assertEqual(parse("0478_s05_qp_1.pdf"), Paper("0478", "s", 2005, "qp", "1", ".pdf"))

    def test_keeps_other_extensions(self):
        self.assertEqual(parse("0417_s24_sf_21.zip").ext, ".zip")

    def test_rejects_things_that_are_not_papers(self):
        for name in [
            "notes.pdf",
            "Chemistry revision.pdf",
            "0620_s24_qp_423.pdf",  # three-digit component
            "20620_s24_qp_42.pdf",  # five-digit code
            "0620_s24_xx_42.pdf",  # unknown document type
            "0620_s24_qpx_42.pdf",
            "Homework 2024 w23 hw 1.pdf",
        ]:
            with self.subTest(name=name):
                self.assertIsNone(parse(name))

    def test_cambridge_name_round_trips(self):
        for name in ["0620_s24_qp_42.pdf", "0620_w23_er.pdf", "0510_m25_in_22.pdf"]:
            with self.subTest(name=name):
                self.assertEqual(parse(name).cambridge_name, name)


class TidyPathTest(unittest.TestCase):
    def test_question_paper(self):
        self.assertEqual(
            tidy_path(parse("0620_s24_qp_42.pdf"), SUBJECT_NAMES),
            PurePosixPath("Chemistry (0620)/2024/May-June/Chemistry 2024 May-June P42 QP.pdf"),
        )

    def test_mark_scheme_sits_next_to_its_paper(self):
        qp = tidy_path(parse("0625_w23_qp_41.pdf"), SUBJECT_NAMES)
        ms = tidy_path(parse("0625_w23_ms_41.pdf"), SUBJECT_NAMES)
        self.assertEqual(qp.parent, ms.parent)
        self.assertEqual(ms.name, "Physics 2023 Oct-Nov P41 MS.pdf")

    def test_session_document(self):
        self.assertEqual(
            tidy_path(parse("0478_m24_er.pdf"), SUBJECT_NAMES).name, "Computer Science 2024 Feb-March Examiner Report.pdf"
        )

    def test_unknown_subject_uses_the_code(self):
        self.assertEqual(tidy_path(parse("9999_s24_qp_12.pdf"), {}), PurePosixPath("9999/2024/May-June/9999 2024 May-June P12 QP.pdf"))

    def test_session_folders_sort_in_exam_order(self):
        folders = [tidy_path(parse(f"0620_{s}24_qp_22.pdf"), SUBJECT_NAMES).parent.name for s in "msw"]
        self.assertEqual(folders, sorted(folders))


if __name__ == "__main__":
    unittest.main()
