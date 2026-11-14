#!/usr/bin/env python3
"""
The public record of a Hush build: what is inside its Android app and its web app, as fingerprints.

CI writes one for every release (hush-build-<number>.json, next to the release's other files). The Android app
fetches the record for the build its server offers and refuses an update whose contents differ, even one signed
with the server's own key; it also compares the web app its server sends with the record. So changing either takes
more than taking over the server. The app's side of this is BuildCheck.kt in shared/client.

  build-manifest.py digest APK                      the content fingerprint of an APK
  build-manifest.py write OUT --build N --version V --commit C [--apk APK] [--web DIR]
  build-manifest.py check MANIFEST --apk APK        does this (signed) APK have the contents on record?

The APK fingerprint covers every file inside it except the signature files, by name and contents, so it is the same
before and after a server signs the app with its own key.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import zipfile

FORMAT = 1
# What a signature adds to an APK (v1 signing); everything else is the app itself.
SIGNATURE_FILE = re.compile(r"^META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))$")


def apk_digest(path: str) -> tuple[str, int]:
    """The fingerprint and the number of files it covers. Refuses an archive with the same name in it twice."""
    with zipfile.ZipFile(path) as apk:
        names = [info.filename for info in apk.infolist()]
        if len(names) != len(set(names)):
            raise SystemExit(f"{path}: has the same file name more than once")
        covered = sorted((n for n in names if not SIGNATURE_FILE.match(n)), key=lambda n: n.encode("utf-8"))
        total = hashlib.sha256()
        for name in covered:
            content = hashlib.sha256()
            with apk.open(name) as stream:
                for block in iter(lambda: stream.read(1 << 20), b""):
                    content.update(block)
            total.update(name.encode("utf-8"))
            total.update(b"\0")
            total.update(content.digest())
        return total.hexdigest(), len(covered)


def web_files(root: str) -> dict[str, str]:
    files = {}
    for folder, _, names in os.walk(root):
        for name in names:
            full = os.path.join(folder, name)
            with open(full, "rb") as stream:
                files[os.path.relpath(full, root).replace(os.sep, "/")] = hashlib.sha256(stream.read()).hexdigest()
    return dict(sorted(files.items()))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    digest = commands.add_parser("digest")
    digest.add_argument("apk")
    write = commands.add_parser("write")
    write.add_argument("out")
    write.add_argument("--build", type=int, required=True)
    write.add_argument("--version", required=True)
    write.add_argument("--commit", default="")
    write.add_argument("--apk")
    write.add_argument("--web")
    check = commands.add_parser("check")
    check.add_argument("manifest")
    check.add_argument("--apk", required=True)
    args = parser.parse_args()

    if args.command == "digest":
        print(apk_digest(args.apk)[0])
    elif args.command == "write":
        record = {"format": FORMAT, "build": args.build, "version": args.version, "commit": args.commit}
        if args.apk:
            fingerprint, entries = apk_digest(args.apk)
            record["apk"] = {"digest": fingerprint, "entries": entries}
        if args.web:
            record["web"] = web_files(args.web)
        with open(args.out, "w", encoding="utf-8") as out:
            json.dump(record, out, indent=1)
            out.write("\n")
    else:
        with open(args.manifest, encoding="utf-8") as stream:
            record = json.load(stream)
        fingerprint, entries = apk_digest(args.apk)
        expected = record.get("apk") or {}
        if fingerprint != expected.get("digest") or entries != expected.get("entries"):
            print(f"{args.apk} does not match the record: {fingerprint} ({entries} files), expected "
                  f"{expected.get('digest')} ({expected.get('entries')} files)", file=sys.stderr)
            return 1
        print(f"{args.apk} matches build {record.get('build')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
