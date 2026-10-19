from capview import audio
from capview.audio import Sink, find_capture_source, parse_short_names

SOURCES = """\
55\talsa_output.pci-0000_00_1f.3.analog-stereo.monitor\tPipeWire\ts32le 2ch 48000Hz\tSUSPENDED
71\talsa_input.usb-MACROSILICON_USB_Video-02.analog-stereo\tPipeWire\ts16le 2ch 48000Hz\tSUSPENDED
"""


def test_capture_source_is_found() -> None:
    names = parse_short_names(SOURCES)
    assert find_capture_source(names) == "alsa_input.usb-MACROSILICON_USB_Video-02.analog-stereo"
    assert find_capture_source(names[:1]) is None


def test_sink_kind() -> None:
    assert Sink("bluez_output.E8_EE_CC_65_9C_A0.1", "Headphones").is_bluetooth
    assert not Sink("alsa_output.pci-0000_00_1f.3.analog-stereo", "Speakers").is_bluetooth


def test_unload_stale_loopbacks_only_ours(monkeypatch) -> None:  # type: ignore[no-untyped-def]
    modules = (
        "536870913\tmodule-loopback\tsource=x sink=y sink_input_properties=media.name=other\n"
        f"536870914\tmodule-loopback\tsource=a sink=b sink_input_properties={audio.LOOPBACK_TAG}\n"
    )
    calls: list[list[str]] = []

    def fake_run(args: list[str], **_: object) -> str:
        calls.append(args)
        return modules if args[1] == "list" else ""

    monkeypatch.setattr(audio, "run", fake_run)
    assert audio.unload_stale_loopbacks() == 1
    assert ["pactl", "unload-module", "536870914"] in calls
