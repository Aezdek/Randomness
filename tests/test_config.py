import tempfile
import tomllib
import unittest
from pathlib import Path

from pastpapers import config
from pastpapers.config import Config, ConfigError, parse

EXAMPLE = Path(__file__).resolve().parent.parent / "config.example.toml"


class ConfigTest(unittest.TestCase):
    def test_example_file_matches_the_defaults(self):
        self.assertEqual(config.load(EXAMPLE), Config())

    def test_overrides(self):
        cfg = parse(
            {
                "inbox": {"scan_seconds": 5},
                "download": {
                    "url": "https://papers.example/{code}/{filename}",
                    "subjects": ["0620"],
                    "first_year": 2020,
                    "types": ["qp", "ms", "er"],
                    "papers": {"0620": [2, 4]},
                },
                "subject_names": {"0510": "English"},
            }
        )
        self.assertEqual(cfg.scan_seconds, 5)
        self.assertTrue(cfg.download.enabled)
        self.assertEqual(cfg.download.papers_for("0620"), [2, 4])
        self.assertEqual(cfg.download.papers_for("0625"), [1, 2, 3, 4, 5, 6])
        self.assertEqual(cfg.subject_names["0510"], "English")
        self.assertEqual(cfg.subject_names["0620"], "Chemistry")

    def test_mistakes_are_explained(self):
        cases = [
            ({"downlaod": {}}, "Unknown setting: downlaod"),
            ({"download": {"subject": ["0620"]}}, "Unknown setting: download.subject"),
            ({"download": {"first_year": "2018"}}, "download.first_year should be a whole number"),
            ({"download": {"subjects": "0620"}}, "download.subjects should be a list"),
            ({"download": {"subjects": ["620"]}}, "isn't a 4-digit syllabus code"),
            ({"download": {"sessions": ["x"]}}, "should be one of m, s, w"),
            ({"download": {"types": ["exam"]}}, "download.types"),
            ({"download": {"url": "https://x/{file}"}}, "unknown placeholder {file}"),
            ({"download": {"url": "https://x/paper.pdf"}}, "needs placeholders"),
            ({"download": {"url": "ftp://x/{filename}"}}, "should start with https://"),
            ({"download": {"papers": {"0620": [0]}}}, "download.papers"),
            ({"inbox": {"scan_seconds": 0}}, "inbox.scan_seconds should be a number, at least 1"),
            ({"subject_names": {"0510": "A/B"}}, "subject_names"),
        ]
        for raw, message in cases:
            with self.subTest(raw=raw):
                with self.assertRaises(ConfigError) as caught:
                    parse(raw)
                self.assertIn(message, str(caught.exception))

    def test_folder_instead_of_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ConfigError, "is a folder"):
                config.load(Path(tmp))
            with self.assertRaisesRegex(ConfigError, "not found"):
                config.load(Path(tmp) / "config.toml")

    def test_example_file_is_valid_toml_with_every_section(self):
        with EXAMPLE.open("rb") as fh:
            raw = tomllib.load(fh)
        self.assertEqual(set(raw), {"inbox", "download", "subject_names"})


if __name__ == "__main__":
    unittest.main()
