import contextlib
import io
import os
import unittest
from unittest import mock

from pastpapers.__main__ import main

from fakesite import FakeSite
from helpers import TempDirTest, pdf


class CliTest(TempDirTest):
    def setUp(self):
        super().setUp()
        self.site = FakeSite()
        self.addCleanup(self.site.close)
        self.config = self.tmp / "config.toml"
        self.config.write_text(
            "[download]\n"
            f'url = "{self.site.url}/p/{{filename}}"\n'
            'subjects = ["0620"]\nfirst_year = 2024\nlast_year = 2024\nsessions = ["s"]\n'
            'types = ["qp"]\ndelay_seconds = 0\n'
            '[download.papers]\n"0620" = [4]\n'
        )
        # The fake site is local, so keep any proxy settings out of the way.
        patcher = mock.patch.dict(os.environ, {"no_proxy": "*", "NO_PROXY": "*"})
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_cli(self, *args: str) -> tuple[int, str]:
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = main(["--data", str(self.tmp), "--config", str(self.config), *args])
        return code, out.getvalue()

    def test_sort(self):
        self.write("inbox/0620_s24_qp_42.pdf", pdf())
        code, out = self.run_cli("sort")
        self.assertEqual(code, 0)
        self.assertIn("Sorted 1 file(s).", out)
        self.assertTrue((self.tmp / "library/Chemistry (0620)/2024/May-June/Chemistry 2024 May-June P42 QP.pdf").exists())

    def test_check_source(self):
        self.site.add_pdf("/p/0620_s24_qp_42.pdf", pdf())
        code, out = self.run_cli("check-source")
        self.assertEqual(code, 0, out)
        self.assertIn("OK, that's a PDF", out)
        code, out = self.run_cli("check-source", "0620_s24_qp_43.pdf")
        self.assertEqual(code, 1)
        self.assertIn("HTTP status:  404", out)

    def test_fetch_dry_run_then_fetch(self):
        self.site.add_pdf("/p/0620_s24_qp_41.pdf", pdf())
        code, out = self.run_cli("fetch", "--dry-run")
        self.assertEqual(code, 0)
        self.assertIn("3 paper(s) would be requested.", out)
        self.assertEqual(self.site.hits, [])
        code, out = self.run_cli("fetch")
        self.assertEqual(code, 0, out)
        self.assertIn("1 downloaded", out)

    def test_bad_config(self):
        self.config.write_text("[download]\nurl = 5\n")
        code, out = self.run_cli("sort")
        self.assertEqual(code, 2)
        self.assertIn("Config problem: download.url should be text", out)


if __name__ == "__main__":
    unittest.main()
