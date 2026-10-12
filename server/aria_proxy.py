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
    GET /                             a page for the iPod's Safari with an install link
    GET /aria.deb                     the Aria app package, to install with Filza
    GET /api/ping                     server info
    GET /api/search?q=...&limit=25    search results as JSON
    GET /api/track/<id>               song, artist, album, album art and segments to skip
    GET /stream/<id>.m4a              audio, with support for Range requests
    GET /thumb/<id>.jpg?size=small    thumbnail (small or large)
"""

import argparse
import collections
import glob
import html
import json
import os
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

VERSION = "1.1.0"
REPO = "Aezdek/Randomness"
HERE = os.path.dirname(os.path.abspath(__file__))
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
                "channel": info.get("channel") or info.get("uploader") or "",
                "duration": info.get("duration") or 0,
                # YouTube's "Music in this video" data, when it has it
                "track": info.get("track"),
                "artists": info.get("artists") or ([info["artist"]] if info.get("artist") else []),
                "album": info.get("album"),
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


class Package:
    """Finds the Aria .deb so the iPod can install it from this server.

    Uses --deb if given, then a build in ../app/packages, then the newest aria-v*
    release from GitHub (downloaded into this folder, and reused when offline).
    """

    def __init__(self):
        self.path = None
        self.status = "looking for the app package"
        self.explicit = None
        self._lock = threading.Lock()

    @staticmethod
    def newest(pattern):
        found = glob.glob(pattern)
        return max(found, key=os.path.getmtime) if found else None

    def prepare(self, explicit=None):
        with self._lock:
            if explicit:
                self.explicit = explicit
            if self.path:
                return
            for attempt in range(3):
                if self._prepare() or self.explicit:
                    return
                time.sleep(2 * (attempt + 1))
            cached = self.newest(os.path.join(HERE, "*.deb"))
            if cached:  # offline: fall back to the last release downloaded
                self.path = cached
                self.status = f"serving {os.path.basename(cached)} (GitHub unreachable, using the copy downloaded earlier)"

    def _prepare(self):
        """Returns True when it's done looking: found a package or there is none to find."""
        if self.explicit:
            ok = os.path.isfile(self.explicit)
            self.path = self.explicit if ok else None
            self.status = f"serving {os.path.basename(self.explicit)}" if ok else f"{self.explicit} not found"
            return True
        built = self.newest(os.path.join(HERE, "..", "app", "packages", "*.deb"))
        if built:  # a build made on this computer wins
            self.path = built
            self.status = f"serving {os.path.basename(built)} (local build)"
            return True
        try:
            req = urllib.request.Request(f"https://api.github.com/repos/{REPO}/releases?per_page=30",
                                         headers={"Accept": "application/vnd.github+json", "User-Agent": "aria-proxy"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                releases = json.load(resp)
            for rel in releases:
                if not rel.get("tag_name", "").startswith("aria-v") or rel.get("draft"):
                    continue
                asset = next((a for a in rel.get("assets", []) if a["name"].endswith(".deb")), None)
                if not asset:
                    continue
                target = os.path.join(HERE, asset["name"])
                if not os.path.isfile(target):
                    asset_req = urllib.request.Request(asset["browser_download_url"], headers={"User-Agent": "aria-proxy"})
                    with urllib.request.urlopen(asset_req, timeout=60) as resp, open(target + ".part", "wb") as out:
                        out.write(resp.read())
                    os.replace(target + ".part", target)
                self.path = target
                self.status = f"serving {asset['name']} (release {rel['tag_name']})"
                return True
            self.status = "no Aria release found on GitHub"
            return True
        except Exception as exc:  # noqa: BLE001
            self.status = f"couldn't download the app package: {exc}"
            return False


PACKAGE = Package()


# Metadata: YouTube titles are video titles ("Artist - Song (Official Video) [4K]") and the
# channel is often "ArtistVEVO" or "Artist - Topic". Clean those up, prefer YouTube's own music
# data, and match against the iTunes catalog for the album and square album art.

NOISE_WORDS = (r"official|lyric|lyrics|audio|video|visuali[sz]er|music video|hd|hq|4k|8k|1080p|"
               r"720p|mv|m/v|clip officiel|videoclip|video oficial|letra|explicit|clean|full song")
NOISE = re.compile(r"\s*[\(\[\{][^\)\]\}]*\b(?:" + NOISE_WORDS + r")\b[^\)\]\}]*[\)\]\}]", re.I)
TRAILING_NOISE = re.compile(r"\s*[|｜/]\s*.*$")
DASHES = re.compile(r"\s+[-–—~]\s+")


def clean_channel(channel):
    channel = re.sub(r"\s*-\s*Topic$", "", channel or "").strip()
    channel = re.sub(r"\s*(?:VEVO|Vevo|vevo)$", "", channel).strip()
    channel = re.sub(r"\s*(?:Official|official|OFFICIAL)(?:\s*(?:Channel|YouTube Channel|Music))?$", "", channel).strip()
    return channel


def clean_song(text):
    text = NOISE.sub("", text or "")
    text = TRAILING_NOISE.sub("", text)
    text = text.strip().strip('"“”\'').strip()
    return re.sub(r"\s{2,}", " ", text)


def guess_metadata(title, channel):
    """(song, artist) from a video title and channel name."""
    title = title or ""
    if (channel or "").endswith(" - Topic"):
        # Auto-generated "Topic" uploads are official audio: the title is just the song.
        return clean_song(title) or title, clean_channel(channel)
    stripped = NOISE.sub("", title).strip()
    quoted = re.match(r'^(.+?)\s+["“](.+?)["”]$', stripped)
    if quoted:  # Artist "Song"
        return clean_song(quoted.group(2)), quoted.group(1).strip()
    parts = DASHES.split(stripped, maxsplit=1)
    if len(parts) == 2 and parts[0].strip() and parts[1].strip():
        return clean_song(parts[1]) or title, parts[0].strip().strip('"')
    return clean_song(title) or title, clean_channel(channel)


def _norm(text):
    text = re.sub(r"\s*[\(\[].*?[\)\]]", "", (text or "").lower())
    text = re.split(r"\s+(?:feat\.?|ft\.?|featuring|with|x|&|,)\s+", text)[0]
    return re.sub(r"[^0-9a-zÀ-￿]+", "", text)


def _similar(a, b):
    a, b = _norm(a), _norm(b)
    return bool(a and b) and (a == b or (min(len(a), len(b)) >= 4 and (a in b or b in a)))


def itunes_lookup(song, artist, duration):
    """The matching iTunes catalog entry, or None. Only accepted when title, artist and length agree."""
    term = urllib.parse.quote(f"{artist} {song}".strip())
    req = urllib.request.Request(f"https://itunes.apple.com/search?term={term}&entity=song&limit=10",
                                 headers={"User-Agent": "aria-proxy"})
    with urllib.request.urlopen(req, timeout=8) as resp:
        results = json.load(resp).get("results") or []
    for r in results:
        if not (_similar(r.get("trackName"), song) and _similar(r.get("artistName"), artist)):
            continue
        length = (r.get("trackTimeMillis") or 0) / 1000
        if duration and length and abs(length - duration) > 15:
            continue  # same name, different version (live, remix, extended...)
        art = r.get("artworkUrl100") or ""
        return {
            "title": r.get("trackName"),
            "artist": r.get("artistName"),
            "album": r.get("collectionName") or "",
            "artwork": art.replace("100x100bb", "600x600bb") if art else None,
        }
    return None


SKIP_CATEGORIES = ["sponsor", "selfpromo", "interaction", "music_offtopic"]


def sponsorblock_segments(vid):
    """[[start, end], ...] to skip: sponsor reads, self-promotion, "subscribe" bits, non-music parts."""
    cats = urllib.parse.quote(json.dumps(SKIP_CATEGORIES))
    req = urllib.request.Request(f"https://sponsor.ajay.app/api/skipSegments?videoID={vid}&categories={cats}",
                                 headers={"User-Agent": "aria-proxy"})
    try:
        with urllib.request.urlopen(req, timeout=6) as resp:
            data = json.load(resp)
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return []  # no segments submitted for this video
        raise
    segments = [s["segment"] for s in data if s.get("actionType", "skip") == "skip" and len(s.get("segment") or []) == 2]
    return sorted([round(a, 2), round(b, 2)] for a, b in segments if b - a >= 1)


class TrackInfo:
    """Cached /api/track answers. Each part is optional: a failed lookup just leaves it out."""

    def __init__(self):
        self._cache = {}
        self._lock = threading.Lock()

    def artwork_url(self, vid):
        with self._lock:
            hit = self._cache.get(vid)
        return hit and hit.get("_artwork")

    def get(self, vid):
        with self._lock:
            if vid in self._cache:
                return self._cache[vid]
        entry = RESOLVER.resolve(vid)
        duration = entry.get("duration") or 0
        song, artist = guess_metadata(entry.get("title"), entry.get("channel"))
        album = ""
        if entry.get("track") and entry.get("artists"):
            song, artist, album = entry["track"], ", ".join(entry["artists"]), entry.get("album") or ""
        artwork = None
        try:
            match = itunes_lookup(song, artist.split(",")[0], duration)
            if match:
                if not entry.get("track"):
                    song, artist = match["title"], match["artist"]
                album = album or match["album"]
                artwork = match["artwork"]
        except Exception as exc:  # noqa: BLE001
            log(f"iTunes lookup for {vid} failed: {exc}")
        try:
            skip = sponsorblock_segments(vid)
        except Exception as exc:  # noqa: BLE001
            log(f"SponsorBlock lookup for {vid} failed: {exc}")
            skip = []
        info = {"id": vid, "title": song, "artist": artist, "album": album, "duration": duration,
                "artwork": bool(artwork), "skip": skip, "_artwork": artwork}
        log(f"track {vid}: {artist} - {song}" + (f" [{album}]" if album else "") + (f", {len(skip)} skip" if skip else ""))
        with self._lock:
            self._cache[vid] = info
        return info


TRACKS = TrackInfo()


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
        song, artist = guess_metadata(e.get("title") or "Untitled", e.get("channel") or e.get("uploader") or "")
        results.append({
            "id": vid,
            "title": song,
            "artist": artist,
            "duration": e.get("duration") or 0,
        })
    return results


class Server(ThreadingHTTPServer):
    daemon_threads = True
    if os.name == "nt":
        # On Windows SO_REUSEADDR lets a second program share the port without an error,
        # and then requests go to whichever one Windows picks. Claim the port exclusively.
        allow_reuse_address = False

        def server_bind(self):
            if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            super().server_bind()


class Handler(BaseHTTPRequestHandler):
    server_version = "AriaProxy/" + VERSION
    protocol_version = "HTTP/1.1"
    token = None

    def log_message(self, fmt, *args):  # replaced by log_request below
        pass

    def log_request(self, code="-", size="-"):
        path = urllib.parse.urlsplit(self.path).path
        if path.startswith("/thumb/") or (path.startswith("/stream/") and self.headers.get("Range", "bytes=0-")[:8] != "bytes=0-"):
            return  # artwork and seeking are too chatty to log
        log(f"{self.client_address[0]} {self.command} {path} -> {code}")

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
        public = parsed.path in ("/", "/index.html", "/aria.deb")
        if self.token and not public and query.get("key", [""])[0] != self.token:
            return self.error_json(401, "Wrong or missing access key")
        path = parsed.path
        try:
            if path in ("/", "/index.html"):
                return self.handle_home()
            if path == "/aria.deb":
                return self.handle_deb()
            if path == "/api/ping":
                return self.send_json(200, {"ok": True, "name": "Aria proxy", "version": VERSION,
                                            "ytdlp": yt_dlp.version.__version__})
            if path == "/api/search":
                return self.handle_search(query)
            m = re.match(r"^/stream/([A-Za-z0-9_-]{11})(?:\.m4a)?$", path)
            if m:
                return self.handle_stream(m.group(1))
            m = re.match(r"^/api/track/([A-Za-z0-9_-]{11})$", path)
            if m:
                info = TRACKS.get(m.group(1))
                return self.send_json(200, {k: v for k, v in info.items() if not k.startswith("_")})
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

    def handle_home(self):
        host = self.headers.get("Host") or ""
        if not host or host.startswith(("127.", "localhost", "[::1]")):
            host = f"{best_address()}:{self.server.server_port}"  # opened on the computer itself
        install = ('<a class="button" href="/aria.deb">Install Aria</a>'
                   '<p>Safari downloads it, then tap <b>Open in Filza</b> and <b>Install</b>.</p>'
                   if PACKAGE.path else f"<p>The app package isn't available: {html.escape(PACKAGE.status)}.</p>")
        body = f"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Aria</title>
<style>body{{font-family:-apple-system,Helvetica,sans-serif;background:#0b0b10;color:#f5f5f7;margin:0;padding:32px 20px;text-align:center}}
h1{{font-size:34px;margin:0 0 6px}}p{{color:#8e8e9a;line-height:1.4}}code{{color:#ff3b6b;font-size:18px}}
.button{{display:block;background:#ff3b6b;color:#fff;text-decoration:none;font-weight:600;font-size:18px;padding:14px;border-radius:12px;margin:28px 0 8px}}</style>
</head><body><h1>Aria</h1><p>The proxy is running.</p>{install}
<p>Then open Aria &rarr; Settings and set Server to</p><code>http://{html.escape(host)}</code></body></html>""".encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def handle_deb(self):
        if not PACKAGE.path:
            PACKAGE.prepare()  # the download at startup may have failed, for example while offline
        if not PACKAGE.path:
            return self.error_json(404, PACKAGE.status)
        with open(PACKAGE.path, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", "application/x-debian-package")
        self.send_header("Content-Disposition", f'attachment; filename="{os.path.basename(PACKAGE.path)}"')
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

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
        album_art = TRACKS.artwork_url(vid)
        key = f"{vid}:{size}:{'album' if album_art else 'video'}"
        data = THUMBS.get(key)
        if data is None and album_art:
            try:
                url = album_art if size == "large" else album_art.replace("600x600bb", "200x200bb")
                with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "aria-proxy"}), timeout=10) as resp:
                    data = resp.read()
            except Exception:  # noqa: BLE001
                data = None  # fall back to the video thumbnail
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


def self_check(port):
    time.sleep(1)
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(f"http://127.0.0.1:{port}/", timeout=10) as resp:
            ok = resp.status == 200 and b"Aria" in resp.read()
        log("Self-check passed: the server answers on this computer. Every visit is logged below."
            if ok else "Self-check failed: something else answered on this port.")
    except Exception as exc:  # noqa: BLE001
        log(f"Self-check failed: {exc}")


VIRTUAL = ("wg", "tun", "tap", "docker", "br-", "veth", "virbr", "tailscale", "zt", "utun", "vmnet", "vboxnet", "proton", "mullvad", "nordlynx")


def interface_addresses():
    """[(address, interface)] for this computer's IPv4 addresses. Interface names are known on Linux only."""
    found = []
    try:
        import subprocess
        if sys.platform.startswith("linux"):
            out = subprocess.run(["ip", "-4", "-o", "addr", "show", "scope", "global"], capture_output=True, text=True, timeout=3).stdout
            for line in out.splitlines():
                parts = line.split()
                found.append((parts[3].split("/")[0], parts[1]))
        else:
            for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
                found.append((info[4][0], ""))
    except Exception:  # noqa: BLE001
        pass
    return [(a, i) for a, i in dict.fromkeys(found) if not a.startswith("127.")]


def is_virtual(interface):
    name = interface.lower()
    return name == "lo" or any(v in name for v in VIRTUAL)


def best_address():
    """The address other devices on the Wi-Fi can reach. VPNs (Mullvad, WireGuard, Tailscale) and Docker
    often own the default route, so prefer a real adapter with a private address."""
    for address, interface in interface_addresses():
        if interface and not is_virtual(interface) and address.startswith(("192.168.", "10.", "172.")):
            return address
    return lan_address()


def main():
    parser = argparse.ArgumentParser(description="YouTube audio proxy for the Aria iPod app")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--token", help="require this access key (the app's Key setting)")
    parser.add_argument("--deb", help="the Aria .deb to offer for install (found or downloaded automatically)")
    args = parser.parse_args()

    # yt-dlp needs a JavaScript runtime (deno) for YouTube. pip installs it next to Python.
    os.environ["PATH"] = os.path.dirname(sys.executable) + os.pathsep + os.environ.get("PATH", "")

    Handler.token = args.token
    try:
        server = Server((args.host, args.port), Handler)
    except OSError as exc:
        log(f"Can't listen on port {args.port}: {exc}")
        log("Another program is using it, or Aria is already running in another window.")
        log(f"Close the other window, or pick another port, for example: --port {args.port + 1}")
        sys.exit(1)
    log(f"Aria proxy {VERSION} (yt-dlp {yt_dlp.version.__version__})")
    primary = best_address()
    address = f"http://{primary}:{args.port}"
    log(f"On the iPod, open {address} in Safari to install Aria")
    log(f"In the app, set Server to: {address}")
    others = [f"{a} ({i})" if i else a for a, i in interface_addresses() if a != primary]
    if others:
        log(f"If the iPod can't open that, try this computer's other addresses: {', '.join(others)} (same port)")
    threading.Thread(target=lambda: (PACKAGE.prepare(args.deb), log(f"App package: {PACKAGE.status}")), daemon=True).start()
    threading.Thread(target=self_check, args=(args.port,), daemon=True).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
