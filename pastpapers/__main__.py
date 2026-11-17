"""Command line: python -m pastpapers {run,sort,fetch,check-source}."""

from __future__ import annotations

import argparse
import logging
import os
import signal
import sys
import threading
from pathlib import Path

from . import config, service


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="pastpapers", description="Sorts and downloads Cambridge past papers.")
    parser.add_argument("--data", type=Path, default=Path(os.environ.get("PASTPAPERS_DATA", "/data")))
    parser.add_argument(
        "--config", type=Path, default=Path(os.environ.get("PASTPAPERS_CONFIG", "/config/config.toml"))
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("run", help="sort the Inbox continuously and download new papers (what the container runs)")
    commands.add_parser("sort", help="sort everything in the Inbox now, then exit")
    fetch = commands.add_parser("fetch", help="download missing papers now, then exit")
    fetch.add_argument("--subject", action="append", metavar="CODE", help="only this syllabus code (repeatable)")
    fetch.add_argument("--year", action="append", type=int, help="only this year (repeatable)")
    fetch.add_argument("--dry-run", action="store_true", help="list what would be downloaded, don't download")
    check = commands.add_parser("check-source", help="test download.url by asking the site for one paper")
    check.add_argument("filename", nargs="?", default="0620_s24_qp_42.pdf")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
        stream=sys.stdout,
        force=True,
    )
    try:
        cfg = config.load(args.config)
    except config.ConfigError as exc:
        print(f"Config problem: {exc}", file=sys.stderr)
        return 2

    if args.command == "run":
        stop = threading.Event()
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, lambda *_: stop.set())
        service.run(cfg, args.data, stop)
        return 0

    library, inbox = service.open_library(cfg, args.data)

    if args.command == "sort":
        results = inbox.scan(wait_for_settle=False)
        print(f"Sorted {len(results)} file(s).")
        return 0

    if not cfg.download.enabled:
        print("download.url isn't set in config.toml, so there's nowhere to download from.", file=sys.stderr)
        return 2
    fetcher = service.make_fetcher(cfg, args.data, library)

    if args.command == "check-source":
        try:
            probe = fetcher.probe(args.filename)
        except ValueError as exc:
            print(exc, file=sys.stderr)
            return 2
        print(f"URL:          {probe.url}")
        if probe.error:
            print(f"Result:       couldn't connect ({probe.error})")
            return 1
        print(f"HTTP status:  {probe.status}")
        print(f"Content type: {probe.content_type or '-'}")
        if probe.is_pdf:
            print("Result:       OK, that's a PDF. Downloads will work.")
            return 0
        print("Result:       not a PDF. Check the url pattern against a link that works in a browser.")
        return 1

    if args.dry_run:
        papers = fetcher.plan(args.subject, args.year)
        for paper in papers:
            print(fetcher.url_for(paper))
        print(f"{len(papers)} paper(s) would be requested.", file=sys.stderr)
        return 0
    counts = fetcher.run(codes=args.subject, years=args.year)
    return 1 if counts["errors"] and not counts["downloaded"] else 0


if __name__ == "__main__":
    sys.exit(main())
