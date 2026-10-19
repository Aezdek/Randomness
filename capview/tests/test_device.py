from pathlib import Path

from capview import device
from capview.device import Mode, by_id_links, parse_device_list, parse_mjpeg_modes, pick_mode

LIST_DEVICES = """\
Integrated Camera: Integrated C (usb-0000:00:14.0-5):
\t/dev/video0
\t/dev/video1
\t/dev/media0

USB Video: USB Video (usb-0000:00:14.0-2):
\t/dev/video2
\t/dev/video3
\t/dev/media1
"""

FORMATS = """\
ioctl: VIDIOC_ENUM_FMT
\tType: Video Capture

\t[0]: 'MJPG' (Motion-JPEG, compressed)
\t\tSize: Discrete 1280x720
\t\t\tInterval: Discrete 0.017s (60.000 fps)
\t\t\tInterval: Discrete 0.033s (30.000 fps)
\t\tSize: Discrete 1920x1080
\t\t\tInterval: Discrete 0.033s (30.000 fps)
\t\t\tInterval: Discrete 0.017s (60.000 fps)
\t[1]: 'YUYV' (YUYV 4:2:2)
\t\tSize: Discrete 1920x1080
\t\t\tInterval: Discrete 0.200s (5.000 fps)
\t\tSize: Discrete 640x480
\t\t\tInterval: Discrete 0.017s (60.000 fps)
"""


def test_parse_device_list() -> None:
    devices = parse_device_list(LIST_DEVICES)
    assert devices["USB Video: USB Video (usb-0000:00:14.0-2)"] == [
        "/dev/video2",
        "/dev/video3",
        "/dev/media1",
    ]


def test_only_mjpeg_modes_highest_first() -> None:
    modes = parse_mjpeg_modes(FORMATS)
    assert modes[0] == Mode(1920, 1080, 60.0)
    assert Mode(640, 480, 60.0) not in modes
    assert len(modes) == 4


def test_mode_key_roundtrip() -> None:
    mode = Mode(1920, 1080, 59.94)
    assert Mode.parse(mode.key) == mode
    assert Mode.parse("garbage") is None


def test_pick_mode_prefers_saved_then_highest() -> None:
    modes = parse_mjpeg_modes(FORMATS)
    assert pick_mode(modes, "1280x720@30") == Mode(1280, 720, 30.0)
    assert pick_mode(modes, "3840x2160@60") == Mode(1920, 1080, 60.0)
    assert pick_mode([], "") is None


def test_by_id_links_index0_first(tmp_path: Path) -> None:
    (tmp_path / "video3").touch()
    (tmp_path / "video2").touch()
    links = tmp_path / "by-id"
    links.mkdir()
    (links / "usb-MACROSILICON_USB_Video-video-index1").symlink_to(tmp_path / "video3")
    (links / "usb-MACROSILICON_USB_Video-video-index0").symlink_to(tmp_path / "video2")
    (links / "usb-Chicony_Camera-video-index0").symlink_to(tmp_path / "video3")
    resolved = by_id_links(links)
    assert list(resolved) == [str(tmp_path / "video2"), str(tmp_path / "video3")]


def test_find_capture_device_resolves_via_by_id(monkeypatch) -> None:  # type: ignore[no-untyped-def]
    monkeypatch.setattr(
        device,
        "by_id_links",
        lambda: {
            "/dev/video3": "/dev/v4l/by-id/usb-MACROSILICON_USB_Video-video-index1",
            "/dev/video2": "/dev/v4l/by-id/usb-MACROSILICON_USB_Video-video-index0",
        },
    )

    def fake_run(args: list[str], **_: object) -> str:
        if args[1] == "--list-devices":
            return LIST_DEVICES
        return FORMATS if args[2] == "/dev/video2" else ""

    monkeypatch.setattr(device, "run", fake_run)
    found = device.find_capture_device()
    assert found is not None
    dev, modes = found
    assert dev.node == "/dev/video2"
    assert modes[0] == Mode(1920, 1080, 60.0)
