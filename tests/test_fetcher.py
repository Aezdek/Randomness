import json
import unittest
from datetime import date

from pastpapers.config import DownloadSettings
from pastpapers.fetcher import MAX_ERRORS_IN_A_ROW, Fetcher, FetchState, wanted
from pastpapers.names import Paper, parse

from fakesite import FakeSite, direct_opener
from helpers import TempDirTest, pdf

TODAY = date(2026, 11, 17)


def settings(url: str, **overrides) -> DownloadSettings:
    values = dict(
        url=url,
        subjects=["0620"],
        first_year=2024,
        last_year=2024,
        sessions=["s"],
        types=["qp", "ms"],
        delay_seconds=0,
        papers={"0620": [4]},
    )
    values.update(overrides)
    return DownloadSettings(**values)


class FetcherTest(TempDirTest):
    def setUp(self):
        super().setUp()
        self.site = FakeSite()
        self.addCleanup(self.site.close)
        self.state_path = self.tmp / "state/downloads.json"

    def fetcher(self, **overrides) -> Fetcher:
        return Fetcher(
            settings(self.site.url + "/papers/{filename}", **overrides),
            self.library,
            FetchState(self.state_path),
            opener=direct_opener(),
            today=lambda: TODAY,
            sleep=lambda _: None,
            error_pause=0,
        )

    def test_downloads_what_the_site_has_and_remembers_what_it_doesnt(self):
        self.site.add_pdf("/papers/0620_s24_qp_42.pdf", pdf("qp"))
        self.site.add_pdf("/papers/0620_s24_ms_42.pdf", pdf("ms"))
        # Some sites answer missing files with a normal page instead of a 404.
        self.site.files["/papers/0620_s24_qp_41.pdf"] = (200, "text/html", b"<html>Sorry</html>")

        counts = self.fetcher().run()

        self.assertEqual(counts["downloaded"], 2)
        self.assertEqual(counts["not on the site"], 4)
        qp = self.library.path_for(parse("0620_s24_qp_42.pdf"))
        self.assertEqual(qp.read_bytes(), pdf("qp"))
        self.assertEqual(qp.with_name("Chemistry 2024 May-June P42 MS.pdf").read_bytes(), pdf("ms"))
        missing = json.loads(self.state_path.read_text())["missing"]
        self.assertEqual(
            sorted(missing), ["0620_s24_ms_41.pdf", "0620_s24_ms_43.pdf", "0620_s24_qp_41.pdf", "0620_s24_qp_43.pdf"]
        )
        self.assertFalse(any((self.tmp / "library/Unsorted").glob("*")))

        # A second run the same day asks for nothing.
        self.site.hits.clear()
        counts = self.fetcher().run()
        self.assertEqual(self.site.hits, [])
        self.assertEqual(counts["already had"], 2)
        self.assertEqual(counts["known missing"], 4)

    def test_stops_after_repeated_errors_without_marking_papers_missing(self):
        self.site.default = (500, "text/plain", b"oops")
        counts = self.fetcher(papers={"0620": [1, 2, 3, 4, 5, 6]}).run()
        self.assertEqual(counts["errors"], MAX_ERRORS_IN_A_ROW)
        self.assertEqual(len(self.site.hits), MAX_ERRORS_IN_A_ROW)
        self.assertEqual(json.loads(self.state_path.read_text())["missing"], {})

    def test_probe(self):
        self.site.add_pdf("/papers/0620_s24_qp_42.pdf", pdf())
        found = self.fetcher().probe("0620_s24_qp_42.pdf")
        self.assertTrue(found.is_pdf)
        self.assertEqual(found.status, 200)
        missing = self.fetcher().probe("0620_s24_qp_43.pdf")
        self.assertFalse(missing.is_pdf)
        self.assertEqual(missing.status, 404)
        self.assertFalse(self.library.has(parse("0620_s24_qp_42.pdf")))

    def test_plan_lists_only_papers_still_needed(self):
        self.library.file(self.write("inbox/0620_s24_qp_42.pdf", pdf()))
        names = [p.cambridge_name for p in self.fetcher().plan()]
        self.assertNotIn("0620_s24_qp_42.pdf", names)
        self.assertEqual(len(names), 5)


class WantedTest(unittest.TestCase):
    def test_newest_first_with_pairs_together(self):
        dl = settings("https://x/{filename}", first_year=2023, last_year=2024, sessions=["m", "s", "w"])
        names = [p.cambridge_name for p in wanted(dl, TODAY)]
        self.assertEqual(names[:4], ["0620_w24_qp_41.pdf", "0620_w24_ms_41.pdf", "0620_w24_qp_42.pdf", "0620_w24_ms_42.pdf"])
        self.assertEqual(names[-2:], ["0620_m23_qp_42.pdf", "0620_m23_ms_42.pdf"])  # Feb/March has variant 2 only

    def test_skips_sessions_that_have_not_happened(self):
        dl = settings("https://x/{filename}", first_year=2026, last_year=None, sessions=["m", "s", "w"])
        sessions = {p.session for p in wanted(dl, date(2026, 7, 1))}
        self.assertEqual(sessions, {"m", "s"})

    def test_session_documents_are_asked_for_once(self):
        dl = settings("https://x/{filename}", types=["qp", "er"], papers={"0620": [1, 2]})
        names = [p.cambridge_name for p in wanted(dl, TODAY)]
        self.assertEqual(names.count("0620_s24_er.pdf"), 1)

    def test_url_placeholders(self):
        dl = DownloadSettings(url="https://x/{subject}/{code}/{year}/{session}{yy}/{type}/{component}/{filename}")
        self.assertEqual(
            dl.url_for(Paper("0478", "w", 2023, "qp", "12"), "Computer Science"),
            "https://x/Computer%20Science/0478/2023/w23/qp/12/0478_w23_qp_12.pdf",
        )


class FetchStateTest(TempDirTest):
    def test_recent_misses_are_retried_weekly(self):
        state = FetchState(self.tmp / "s.json")
        paper = Paper("0620", "w", 2026, "qp", "42")
        state.mark_missing(paper, date(2026, 12, 1))
        self.assertFalse(state.should_try(paper, date(2026, 12, 5)))
        self.assertTrue(state.should_try(paper, date(2026, 12, 8)))

    def test_old_misses_are_never_retried(self):
        state = FetchState(self.tmp / "s.json")
        paper = Paper("0620", "s", 2019, "qp", "43")
        state.mark_missing(paper, date(2026, 1, 1))
        self.assertFalse(state.should_try(paper, date(2030, 1, 1)))

    def test_survives_save_and_a_corrupt_file(self):
        path = self.tmp / "state/s.json"
        state = FetchState(path)
        state.mark_missing(Paper("0620", "s", 2019, "qp", "43"), TODAY)
        state.save()
        self.assertEqual(FetchState(path).missing, {"0620_s19_qp_43.pdf": "2026-11-17"})
        path.write_text("{not json")
        with self.assertLogs("pastpapers.fetcher", "WARNING"):
            self.assertEqual(FetchState(path).missing, {})


if __name__ == "__main__":
    unittest.main()
