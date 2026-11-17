"""A tiny local web server standing in for a past papers website."""

import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class FakeSite:
    def __init__(self):
        self.files: dict[str, tuple[int, str, bytes]] = {}
        self.hits: list[str] = []
        self.default = (404, "text/html", b"<html>Not found</html>")
        site = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                site.hits.append(self.path)
                status, content_type, body = site.files.get(self.path, site.default)
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def add_pdf(self, path: str, body: bytes) -> None:
        self.files[path] = (200, "application/pdf", body)

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


def direct_opener() -> urllib.request.OpenerDirector:
    """An opener that ignores any proxy settings in the environment."""
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))
