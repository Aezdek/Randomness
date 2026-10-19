from pathlib import Path

import pytest

from capview.bluetooth import BluetoothError, card_name, normalize_mac
from capview.config import Config
from capview.device import Mode
from capview.session import resolve_route
from capview.video import Backend, build_command


def test_config_roundtrip(tmp_path: Path) -> None:
    path = tmp_path / "capview" / "config.toml"
    config = Config(preferred_sink='weird "sink"', last_mode="1920x1080@60", hwdec=False)
    config.save(path)
    assert Config.load(path) == config


def test_config_ignores_bad_values(tmp_path: Path) -> None:
    path = tmp_path / "config.toml"
    path.write_text('hwdec = "yes"\nloopback_latency_ms = 50\nunknown = 1\n')
    config = Config.load(path)
    assert config.hwdec is True
    assert config.loopback_latency_ms == 50


def test_mpv_command_is_low_latency_mjpeg() -> None:
    cmd = build_command(Backend.MPV, "/dev/video2", Mode(1920, 1080, 60), hwdec=True)
    assert cmd[-1] == "av://v4l2:/dev/video2"
    for flag in (
        "--no-cache",
        "--hwdec=vaapi",
        "--hwdec-codecs=mjpeg",
        "--demuxer-lavf-o-append=input_format=mjpeg",
        "--demuxer-lavf-o-append=video_size=1920x1080",
        "--demuxer-lavf-o-append=framerate=60",
        "--demuxer-lavf-o-append=fflags=+nobuffer",
        "--demuxer-lavf-o-append=analyzeduration=0",
    ):
        assert flag in cmd


def test_ffplay_command() -> None:
    cmd = build_command(Backend.FFPLAY, "/dev/video2", Mode(1280, 720, 30), hwdec=True)
    assert cmd[cmd.index("-input_format") + 1] == "mjpeg"
    assert cmd[cmd.index("-video_size") + 1] == "1280x720"
    assert cmd[-1] == "/dev/video2"


def test_mac_handling() -> None:
    assert normalize_mac("e8-ee-cc-65-9c-a0") == "E8:EE:CC:65:9C:A0"
    assert card_name("E8:EE:CC:65:9C:A0") == "bluez_card.E8_EE_CC_65_9C_A0"
    with pytest.raises(BluetoothError):
        normalize_mac("nope")


def test_route_falls_back_to_local_when_bluetooth_fails(monkeypatch) -> None:  # type: ignore[no-untyped-def]
    from capview import audio, bluetooth, session
    from capview.audio import Sink

    local = Sink("alsa_output.pci.analog-stereo", "Speakers")
    monkeypatch.setattr(audio, "list_sinks", lambda: [local])
    monkeypatch.setattr(audio, "find_capture_source", lambda: "alsa_input.MACROSILICON")

    def fail(_mac: str) -> Sink:
        raise bluetooth.BluetoothError("Could not connect")

    monkeypatch.setattr(session.bluetooth, "bring_up_sink", fail)
    route = resolve_route("bluez_output.E8_EE_CC_65_9C_A0.1", "E8:EE:CC:65:9C:A0", True)
    assert route.sink == local
    assert route.source == "alsa_input.MACROSILICON"
    assert any("Falling back" in n for n in route.notes)
