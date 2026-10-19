from __future__ import annotations

import threading
from collections.abc import Callable

from gi.repository import GLib


def on_main(fn: Callable[[], object]) -> None:
    def once() -> bool:
        fn()
        return GLib.SOURCE_REMOVE

    GLib.idle_add(once)


def in_background[T](
    work: Callable[[], T],
    done: Callable[[T], None],
    failed: Callable[[Exception], None],
) -> None:
    """Run blocking work off the GTK thread and deliver the outcome back on it."""

    def worker() -> None:
        try:
            result = work()
        except Exception as e:  # noqa: BLE001
            error = e
            on_main(lambda: failed(error))
        else:
            on_main(lambda: done(result))

    threading.Thread(target=worker, daemon=True).start()
