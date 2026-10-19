from __future__ import annotations

from collections.abc import Callable
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, GLib, Gtk  # noqa: E402

from capview import APP_NAME, audio, bluetooth  # noqa: E402
from capview.audio import Sink  # noqa: E402
from capview.config import Config  # noqa: E402
from capview.device import CaptureDevice, Mode, find_capture_device, pick_mode  # noqa: E402
from capview.proc import CommandError  # noqa: E402
from capview.session import Route, Session, resolve_route  # noqa: E402
from capview.tasks import in_background, on_main  # noqa: E402
from capview.video import Backend, build_command  # noqa: E402

BACKENDS = list(Backend)


def _icon_button(icon: str, tooltip: str, handler: Callable[[], None]) -> Gtk.Button:
    button = Gtk.Button(icon_name=icon, tooltip_text=tooltip, valign=Gtk.Align.CENTER)
    button.add_css_class("flat")
    button.connect("clicked", lambda *_: handler())
    return button


def _text_button(label: str, handler: Callable[[], None]) -> Gtk.Button:
    button = Gtk.Button(label=label, valign=Gtk.Align.CENTER)
    button.connect("clicked", lambda *_: handler())
    return button


def _replace(row: Adw.ComboRow, labels: list[str], selected: int) -> None:
    model = row.get_model()
    model.splice(0, model.get_n_items(), labels)
    if labels:
        row.set_selected(selected)


class MainWindow(Adw.ApplicationWindow):
    def __init__(self, app: Adw.Application, config: Config) -> None:
        super().__init__(application=app, title=APP_NAME, default_width=480, default_height=680)
        self.config = config
        self.session = Session(lambda code: on_main(lambda: self._on_video_exit(code)))
        self._modes: list[Mode] = []
        self._sinks: list[Sink] = []
        self._busy = 0
        self._starting = False
        self._populating = False

        self.start_button = Gtk.Button(label="Start")
        self.start_button.add_css_class("suggested-action")
        self.start_button.connect("clicked", lambda *_: self._toggle())
        self.spinner = Gtk.Spinner()

        header = Adw.HeaderBar()
        header.pack_start(self.start_button)
        header.pack_end(self.spinner)

        page = Adw.PreferencesPage()
        page.add(self._build_capture_group())
        page.add(self._build_audio_group())
        page.add(self._build_bluetooth_group())

        view = Adw.ToolbarView()
        view.add_top_bar(header)
        view.set_content(page)
        self.toasts = Adw.ToastOverlay(child=view)
        self.set_content(self.toasts)

        self.rescan_device()
        if config.bluetooth_autoconnect:
            self.connect_bluetooth()
        else:
            self.refresh_sinks()

    def _build_capture_group(self) -> Adw.PreferencesGroup:
        group = Adw.PreferencesGroup(title="Capture")
        self.device_row = Adw.ActionRow(title="Device", subtitle="Searching…")
        self.device_row.add_suffix(
            _icon_button("view-refresh-symbolic", "Rescan", self.rescan_device)
        )
        self.mode_row = Adw.ComboRow(title="Mode", model=Gtk.StringList.new([]))
        self.mode_row.connect("notify::selected", self._on_mode_changed)

        self.backend_row = Adw.ComboRow(
            title="Player", model=Gtk.StringList.new([b.value for b in BACKENDS])
        )
        backend = Backend(self.config.backend) if self.config.backend in BACKENDS else Backend.MPV
        self.backend_row.set_selected(BACKENDS.index(backend))
        self.backend_row.connect("notify::selected", self._on_player_settings_changed)

        self.hwdec_row = Adw.SwitchRow(
            title="Hardware decoding", subtitle="VA-API, mpv only", active=self.config.hwdec
        )
        self.hwdec_row.connect("notify::active", self._on_player_settings_changed)

        for row in (self.device_row, self.mode_row, self.backend_row, self.hwdec_row):
            group.add(row)
        return group

    def _build_audio_group(self) -> Adw.PreferencesGroup:
        group = Adw.PreferencesGroup(title="Audio")
        self.sink_row = Adw.ComboRow(title="Output", model=Gtk.StringList.new([]))
        self.sink_row.add_suffix(
            _icon_button("view-refresh-symbolic", "Refresh outputs", self.refresh_sinks)
        )
        self.sink_row.connect("notify::selected", self._on_sink_changed)

        resync_row = Adw.ActionRow(
            title="Re-sync volume", subtitle="Fixes Bluetooth absolute-volume drift"
        )
        resync_row.add_suffix(_text_button("Re-sync", self._resync_volume))

        group.add(self.sink_row)
        group.add(resync_row)
        return group

    def _build_bluetooth_group(self) -> Adw.PreferencesGroup:
        group = Adw.PreferencesGroup(title="Bluetooth")
        self.mac_row = Adw.EntryRow(
            title="Device MAC", text=self.config.bluetooth_mac, show_apply_button=True
        )
        self.mac_row.connect("apply", self._on_mac_applied)

        self.autoconnect_row = Adw.SwitchRow(
            title="Connect automatically",
            subtitle="On launch and when starting the preview",
            active=self.config.bluetooth_autoconnect,
        )
        self.autoconnect_row.connect("notify::active", self._on_autoconnect_changed)

        self.bt_row = Adw.ActionRow(title="Status", subtitle="Not connected")
        self.bt_row.add_suffix(_text_button("Connect", self.connect_bluetooth))

        for row in (self.mac_row, self.autoconnect_row, self.bt_row):
            group.add(row)
        return group

    def toast(self, message: str) -> None:
        self.toasts.add_toast(Adw.Toast(title=GLib.markup_escape_text(message), timeout=4))

    def _run[T](
        self,
        work: Callable[[], T],
        done: Callable[[T], None],
        failed: Callable[[Exception], None] | None = None,
    ) -> None:
        self._set_busy(+1)

        def finish(result: T) -> None:
            self._set_busy(-1)
            done(result)

        def fail(error: Exception) -> None:
            self._set_busy(-1)
            (failed or (lambda e: self.toast(str(e))))(error)

        in_background(work, finish, fail)

    def _set_busy(self, delta: int) -> None:
        self._busy += delta
        self.spinner.set_spinning(self._busy > 0)

    def rescan_device(self) -> None:
        self.device_row.set_subtitle("Searching…")
        self._run(find_capture_device, self._show_device, self._device_failed)

    def _device_failed(self, error: Exception) -> None:
        self._show_device(None)
        self.toast(str(error))

    def _show_device(self, found: tuple[CaptureDevice, list[Mode]] | None) -> None:
        if found is None:
            self.device_row.set_subtitle("No MACROSILICON capture card found")
            self._set_modes([], None)
            return
        device, modes = found
        label = Path(device.by_id).name if device.by_id else device.name
        self.device_row.set_subtitle(f"{device.node} · {label}")
        self._set_modes(modes, pick_mode(modes, self.config.last_mode))

    def _set_modes(self, modes: list[Mode], selected: Mode | None) -> None:
        current = self._selected_mode()
        self._modes = modes
        self._populating = True
        _replace(
            self.mode_row,
            [m.label for m in modes],
            modes.index(selected) if selected in modes else 0,
        )
        self._populating = False
        if self._selected_mode() != current and self.session.running:
            self._restart_video()

    def _selected_mode(self) -> Mode | None:
        index = self.mode_row.get_selected()
        return self._modes[index] if 0 <= index < len(self._modes) else None

    def refresh_sinks(self, select: str | None = None) -> None:
        self._run(audio.list_sinks, lambda sinks: self._set_sinks(sinks, select))

    def _set_sinks(self, sinks: list[Sink], select: str | None) -> None:
        self._sinks = sinks
        names = [s.name for s in sinks]
        wanted = select or self.session.sink or self.config.preferred_sink
        index = names.index(wanted) if wanted in names else 0
        self._populating = True
        _replace(self.sink_row, [s.label for s in sinks], index)
        self._populating = False

    def _selected_sink(self) -> Sink | None:
        index = self.sink_row.get_selected()
        return self._sinks[index] if 0 <= index < len(self._sinks) else None

    def _select_sink(self, name: str) -> None:
        names = [s.name for s in self._sinks]
        if name in names:
            self._populating = True
            self.sink_row.set_selected(names.index(name))
            self._populating = False
        else:
            self.refresh_sinks(select=name)

    def connect_bluetooth(self) -> None:
        mac = self.config.bluetooth_mac
        self.bt_row.set_subtitle(f"Connecting to {mac}…")

        def connected(sink: Sink) -> None:
            self.bt_row.set_subtitle(f"Connected · {sink.description}")
            prefer_bt = not self.config.preferred_sink or self.config.preferred_sink == sink.name
            self.refresh_sinks(select=sink.name if prefer_bt else None)

        def failed(error: Exception) -> None:
            self.bt_row.set_subtitle(str(error))
            self.refresh_sinks()

        self._run(lambda: bluetooth.bring_up_sink(mac), connected, failed)

    def _toggle(self) -> None:
        if self._starting:
            return
        if self.session.running:
            self.session.stop()
            self._show_running(False)
        else:
            self._start()

    def _start(self) -> None:
        wanted = self._selected_mode()
        sink = self._selected_sink()
        preferred = sink.name if sink else self.config.preferred_sink
        config = self.config
        self._starting = True
        self.start_button.set_sensitive(False)

        def work() -> tuple[CaptureDevice, list[Mode], Mode, Route]:
            found = find_capture_device()
            if found is None:
                raise RuntimeError("Capture card not found. Is it plugged in?")
            device, modes = found
            mode = pick_mode(modes, wanted.key if wanted else config.last_mode)
            assert mode is not None
            route = resolve_route(preferred, config.bluetooth_mac, config.bluetooth_autoconnect)
            if route.source and route.sink:
                try:
                    self.session.start_audio(
                        route.source, route.sink.name, config.loopback_latency_ms
                    )
                except (CommandError, OSError) as e:
                    route.notes.append(f"Audio loopback failed: {e}")
            return device, modes, mode, route

        def started(result: tuple[CaptureDevice, list[Mode], Mode, Route]) -> None:
            device, modes, mode, route = result
            self._starting = False
            self.start_button.set_sensitive(True)
            self._show_device((device, modes))
            self._populating = True
            self.mode_row.set_selected(modes.index(mode))
            self._populating = False
            self.config.last_mode = mode.key
            self.config.save()
            if route.sink:
                self._select_sink(route.sink.name)
            for note in route.notes:
                self.toast(note)
            try:
                self.session.start_video(device, mode, self._command(device, mode))
            except OSError as e:
                self.session.stop()
                self.toast(f"Could not launch {self.config.backend}: {e}")
                return
            self._show_running(True)

        def failed(error: Exception) -> None:
            self._starting = False
            self.start_button.set_sensitive(True)
            self.session.stop_audio()
            self.toast(str(error))

        self._run(work, started, failed)

    def _command(self, device: CaptureDevice, mode: Mode) -> list[str]:
        backend = BACKENDS[self.backend_row.get_selected()]
        return build_command(backend, device.node, mode, self.hwdec_row.get_active())

    def _restart_video(self) -> None:
        device, mode = self.session.device, self._selected_mode()
        if device and mode:
            try:
                self.session.start_video(device, mode, self._command(device, mode))
            except OSError as e:
                self.session.stop()
                self._show_running(False)
                self.toast(str(e))

    def _show_running(self, running: bool) -> None:
        self.start_button.set_label("Stop" if running else "Start")
        if running:
            self.start_button.remove_css_class("suggested-action")
            self.start_button.add_css_class("destructive-action")
        else:
            self.start_button.remove_css_class("destructive-action")
            self.start_button.add_css_class("suggested-action")

    def _on_video_exit(self, code: int) -> None:
        self.session.stop_audio()
        self._show_running(False)
        if code not in (0, -15):
            self.toast(f"Player exited with code {code}")

    def _on_mode_changed(self, *_: object) -> None:
        mode = self._selected_mode()
        if self._populating or mode is None:
            return
        self.config.last_mode = mode.key
        self.config.save()
        if self.session.running:
            self._restart_video()

    def _on_player_settings_changed(self, *_: object) -> None:
        self.config.backend = BACKENDS[self.backend_row.get_selected()].value
        self.config.hwdec = self.hwdec_row.get_active()
        self.config.save()
        if self.session.running:
            self._restart_video()

    def _on_sink_changed(self, *_: object) -> None:
        sink = self._selected_sink()
        if self._populating or sink is None:
            return
        self.config.preferred_sink = sink.name
        self.config.save()
        if self.session.sink:
            latency = self.config.loopback_latency_ms
            self._run(lambda: self.session.switch_sink(sink.name, latency), lambda _: None)

    def _resync_volume(self) -> None:
        sink = self.session.sink or (s.name if (s := self._selected_sink()) else None)
        if sink is None:
            self.toast("No output selected")
            return
        self._run(lambda: audio.resync_volume(sink), lambda _: self.toast("Volume re-synced"))

    def _on_mac_applied(self, row: Adw.EntryRow) -> None:
        try:
            self.config.bluetooth_mac = bluetooth.normalize_mac(row.get_text())
        except bluetooth.BluetoothError as e:
            self.toast(str(e))
            row.set_text(self.config.bluetooth_mac)
            return
        row.set_text(self.config.bluetooth_mac)
        self.config.save()
        self.connect_bluetooth()

    def _on_autoconnect_changed(self, *_: object) -> None:
        self.config.bluetooth_autoconnect = self.autoconnect_row.get_active()
        self.config.save()

    def teardown(self) -> None:
        self.session.stop()
