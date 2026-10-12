#!/usr/bin/env python3
"""Aria proxy: YouTube search and audio streaming for old iOS devices.

An iPod touch on iOS 9 can't talk to YouTube directly: it doesn't support the
TLS that YouTube now requires, and it can't run yt-dlp. This server runs on a
computer on your network. It does the YouTube work and gives the iPod plain
HTTP that it can play: AAC audio in an .m4a container, with byte ranges so
seeking works.

    pip install -r requirements.txt
    python3 aria_proxy.py                 # listens on 0.0.0.0:8765
    python3 aria_proxy.py --token secret  # require ?key=secret on every request

Endpoints:
    GET /api/ping                     server info
    GET /api/search?q=...&limit=25    search results as JSON
    GET /stream/<id>.m4a              audio, with support for Range requests
    GET /thumb/<id>.jpg?size=small    thumbnail (small or large)
"""

import argparse
import collections
import json
import re
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    import yt_dlp
except ImportError:  # pragma: no cover
    sys.exit("yt-dlp is missing. Install it with: pip install -r requirements.txt")

VERSION = "1.0.0"
VIDEO_ID = re.compile(r"^[A-Za-z0-9_-]{11}$")
# iOS 9 plays AAC (mp4a) in an mp4 container natively. Itag 140 is 128 kbps AAC.
AUDIO_FORMAT = "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/140"
CHUNK = 64 * 1024


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


class Resolver:
    """Turns a video id into a direct googlevideo URL. The URL is cached because extraction is slow."""

    TTL = 2 * 60 * 60  # signed URLs last about six hours, so refresh well before then

    def __init__(self):
        self._cache = {}
        self._lock = threading.Lock()
        self._inflight = collections.defaultdict(threading.Lock)

    def resolve(self, vid, fresh=False):
        with self._lock:
            hit = self._cache.get(vid)
            if hit and not fresh and hit["expires"] > time.time():
                return hit
            vid_lock = self._inflight[vid]
        # AVPlayer often opens several range requests at once. Extract only once.
        with vid_lock:
            with self._lock:
                hit = self._cache.get(vid)
                if hit and not fresh and hit["expires"] > time.time():
                    return hit
            started = time.time()
            opts = {"format": AUDIO_FORMAT, "quiet": True, "no_warnings": True, "noplaylist": True}
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(f"https://www.youtube.com/watch?v={vid}", download=False)
            if not info.get("url"):
                raise RuntimeError("no playable audio stream")
            entry = {
                "url": info["url"],
                "headers": dict(info.get("http_headers") or {}),
                "title": info.get("title"),
                "expires": time.time() + self.TTL,
            }
            log(f"resolved {vid} ({info.get('format_id')}, {info.get('ext')}) in {time.time() - started:.1f}s")
            with self._lock:
                self._cache[vid] = entry
            return entry


class ThumbCache:
    def __init__(self, limit=400):
        self._items = collections.OrderedDict()
        self._limit = limit
        self._lock = threading.Lock()

    def get(self, key):
        with self._lock:
            if key in self._items:
                self._items.move_to_end(key)
                return self._items[key]
        return None

    def put(self, key, data):
        with self._lock:
            self._items[key] = data
            while len(self._items) > self._limit:
                self._items.popitem(last=False)


RESOLVER = Resolver()
THUMBS = ThumbCache()


def search(query, limit):
    opts = {"quiet": True, "no_warnings": True, "extract_flat": "in_playlist", "skip_download": True}
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(f"ytsearch{limit}:{query}", download=False)
    results = []
    for e in info.get("entries") or []:
        vid = e.get("id")
        if not vid or not VIDEO_ID.match(vid):
            continue
        if e.get("live_status") in ("is_live", "is_upcoming") or e.get("duration") is None:
            continue  # live streams and premieres can't be played as a file
        results.append({
            "id": vid,
            "title": e.get("title") or "Untitled",
            "artist": e.get("channel") or e.get("uploader") or "",
            "duration": e.get("duration") or 0,
        })
    return results


class Handler(BaseHTTPRequestHandler):
    server_version = "AriaProxy/" + VERSION
    protocol_version = "HTTP/1.1"
    token = None

    def log_message(self, fmt, *args):  # quieter than the default access log
        pass

    # Helpers

    def send_json(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def error_json(self, status, message):
        self.send_json(status, {"error": message})

    # Routing

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        parsed = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        if self.token and query.get("key", [""])[0] != self.token:
            return self.error_json(401, "Wrong or missing access key")
        path = parsed.path
        try:
            if path == "/api/ping":
                return self.send_json(200, {"ok": True, "name": "Aria proxy", "version": VERSION,
                                            "ytdlp": yt_dlp.version.__version__})
            if path == "/api/search":
                return self.handle_search(query)
            m = re.match(r"^/stream/([A-Za-z0-9_-]{11})(?:\.m4a)?$", path)
            if m:
                return self.handle_stream(m.group(1))
            m = re.match(r"^/thumb/([A-Za-z0-9_-]{11})(?:\.jpg)?$", path)
            if m:
                return self.handle_thumb(m.group(1), query.get("size", ["small"])[0])
            return self.error_json(404, "Not found")
        except (BrokenPipeError, ConnectionResetError):
            pass  # the iPod cancelled the request, which AVPlayer does all the time
        except Exception as exc:  # noqa: BLE001
            message = re.sub(r"^ERROR:\s*", "", str(exc).splitlines()[0]).split("; please report")[0]
            log(f"error on {path}: {message}")
            try:
                self.error_json(502, message[:300])
            except OSError:
                pass

    def handle_search(self, query):
        q = (query.get("q", [""])[0]).strip()
        if not q:
            return self.error_json(400, "Missing q")
        try:
            limit = max(1, min(50, int(query.get("limit", ["25"])[0])))
        except ValueError:
            limit = 25
        started = time.time()
        results = search(q, limit)
        log(f"search {q!r}: {len(results)} results in {time.time() - started:.1f}s")
        self.send_json(200, {"results": results})

    def handle_stream(self, vid):
        client_range = self.headers.get("Range")
        for attempt in range(2):
            entry = RESOLVER.resolve(vid, fresh=attempt > 0)
            headers = dict(entry["headers"])
            # Always ask for a range. Requests for the whole file are throttled.
            headers["Range"] = client_range or "bytes=0-"
            req = urllib.request.Request(entry["url"], headers=headers, method=self.command)
            try:
                upstream = urllib.request.urlopen(req, timeout=20)
                break
            except urllib.error.HTTPError as exc:
                if exc.code == 416:
                    self.send_response(416)
                    self.send_header("Content-Range", exc.headers.get("Content-Range", "bytes */0"))
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                if attempt == 0 and exc.code in (403, 404, 410):
                    log(f"stream URL for {vid} rejected ({exc.code}), extracting again")
                    continue
                raise
        with upstream:
            status = upstream.status
            length = upstream.headers.get("Content-Length")
            content_range = upstream.headers.get("Content-Range")
            if status == 206 and not client_range and content_range:
                # The client asked for the whole file. Report a 200 with the full length.
                status = 200
                content_range = None
            self.send_response(status)
            self.send_header("Content-Type", "audio/mp4")
            self.send_header("Accept-Ranges", "bytes")
            if length:
                self.send_header("Content-Length", length)
            if content_range:
                self.send_header("Content-Range", content_range)
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            if self.command == "HEAD":
                return
            while True:
                chunk = upstream.read(CHUNK)
                if not chunk:
                    break
                self.wfile.write(chunk)

    def handle_thumb(self, vid, size):
        names = ["maxresdefault", "sddefault", "hqdefault"] if size == "large" else ["mqdefault"]
        key = f"{vid}:{size}"
        data = THUMBS.get(key)
        if data is None:
            for name in names:
                try:
                    with urllib.request.urlopen(f"https://i.ytimg.com/vi/{vid}/{name}.jpg", timeout=10) as resp:
                        data = resp.read()
                    break
                except urllib.error.HTTPError:
                    continue
            if data is None:
                return self.error_json(404, "No thumbnail")
            THUMBS.put(key, data)
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "public, max-age=604800")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)


def lan_address():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def main():
    parser = argparse.ArgumentParser(description="YouTube audio proxy for the Aria iPod app")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--token", help="require this access key (the app's Access Key setting)")
    args = parser.parse_args()

    Handler.token = args.token
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    log(f"Aria proxy {VERSION} (yt-dlp {yt_dlp.version.__version__})")
    log(f"In the app, set Server to: http://{lan_address()}:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
