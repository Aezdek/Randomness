import os
import time
import unittest

from pastpapers.inbox import Inbox

from helpers import TempDirTest, pdf

QP = "library/Chemistry (0620)/2024/May-June/Chemistry 2024 May-June P42 QP.pdf"


class InboxTest(TempDirTest):
    def setUp(self):
        super().setUp()
        (self.tmp / "inbox").mkdir()
        self.inbox = Inbox(self.tmp / "inbox", self.library, settle_seconds=10)

    def test_waits_until_a_file_has_settled(self):
        self.write("inbox/0620_s24_qp_42.pdf", pdf())
        now = time.time()
        self.assertEqual(self.inbox.scan(now=now), [])  # first sighting
        self.assertEqual(self.inbox.scan(now=now + 1), [])  # unchanged, but too recent
        self.assertEqual(len(self.inbox.scan(now=now + 60)), 1)
        self.assertTrue((self.tmp / QP).exists())

    def test_leaves_a_file_alone_while_it_is_still_growing(self):
        path = self.write("inbox/0620_s24_qp_42.pdf", pdf())
        now = time.time()
        self.inbox.scan(now=now)
        with path.open("ab") as fh:
            fh.write(b"more")
        self.assertEqual(self.inbox.scan(now=now + 60), [])
        self.assertTrue(path.exists())
        self.assertEqual(len(self.inbox.scan(now=now + 120)), 1)

    def test_sorts_nested_folders_and_tidies_up(self):
        self.write("inbox/Chemistry/2024/0620_s24_qp_42.pdf", pdf())
        self.write("inbox/Chemistry/.DS_Store", b"junk")
        self.write("inbox/notes.pdf", pdf("notes"))
        results = self.inbox.scan(wait_for_settle=False)
        self.assertEqual(sorted(r.status for r in results), ["added", "unsorted"])
        self.assertTrue((self.tmp / QP).exists())
        self.assertTrue((self.tmp / "library/Unsorted/notes.pdf").exists())
        self.assertEqual(os.listdir(self.tmp / "inbox"), [])

    def test_ignores_partial_and_hidden_files(self):
        self.write("inbox/0620_s24_qp_42.pdf.part", pdf())
        self.write("inbox/._0620_s24_qp_42.pdf", b"apple double")
        self.write("inbox/.hidden/0620_s24_qp_42.pdf", pdf())
        self.assertEqual(self.inbox.scan(wait_for_settle=False), [])
        self.assertTrue((self.tmp / "inbox/0620_s24_qp_42.pdf.part").exists())
        self.assertTrue((self.tmp / "inbox/.hidden/0620_s24_qp_42.pdf").exists())

    def test_keeps_a_new_empty_folder_for_a_while(self):
        (self.tmp / "inbox/New folder").mkdir()
        self.inbox.scan(now=time.time())
        self.assertTrue((self.tmp / "inbox/New folder").exists())
        self.inbox.scan(now=time.time() + 60)
        self.assertFalse((self.tmp / "inbox/New folder").exists())


if __name__ == "__main__":
    unittest.main()
