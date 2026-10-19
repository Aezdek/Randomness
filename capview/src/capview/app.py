from __future__ import annotations

import contextlib
import signal
import sys

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gio, GLib  # noqa: E402

from capview import APP_ID, audio  # noqa: E402
from capview.config import Config  # noqa: E402
from capview.proc import CommandError  # noqa: E402
from capview.window import MainWindow  # noqa: E402


class CapviewApp(Adw.Application):
    def __init__(self) -> None:
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.DEFAULT_FLAGS)
        self.config = Config.load()
        self.window: MainWindow | None = None
        self.connect("activate", self._on_activate)
        self.connect("shutdown", self._on_shutdown)

    def _on_activate(self, _app: Adw.Application) -> None:
        if self.window is None:
            self.window = MainWindow(self, self.config)
        self.window.present()

    def _on_shutdown(self, _app: Adw.Application) -> None:
        if self.window:
            self.window.teardown()


def _quit(app: CapviewApp) -> bool:
    app.quit()
    return GLib.SOURCE_REMOVE


def main() -> int:
    with contextlib.suppress(CommandError):
        audio.unload_stale_loopbacks()
    app = CapviewApp()
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, sig, _quit, app)
    return app.run(sys.argv)
