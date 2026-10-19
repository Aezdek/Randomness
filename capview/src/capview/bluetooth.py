from __future__ import annotations

import re
import time

from capview import audio
from capview.audio import Sink
from capview.proc import CommandError, run

DEFAULT_MAC = "E8:EE:CC:65:9C:A0"
A2DP_PROFILES = ("a2dp-sink", "a2dp_sink")

_MAC = re.compile(r"^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")


class BluetoothError(RuntimeError):
    pass


def normalize_mac(mac: str) -> str:
    mac = mac.strip().upper().replace("-", ":")
    if not _MAC.match(mac):
        raise BluetoothError(f"{mac or 'empty'} is not a Bluetooth MAC address")
    return mac


def _token(mac: str) -> str:
    return mac.replace(":", "_")


def card_name(mac: str) -> str:
    return f"bluez_card.{_token(mac)}"


def is_device_sink(sink: Sink, mac: str) -> bool:
    return sink.is_bluetooth and _token(mac) in sink.name


def is_connected(mac: str) -> bool:
    try:
        return "Connected: yes" in run(["bluetoothctl", "info", mac])
    except CommandError:
        return False


def connect(mac: str, timeout: float = 20) -> None:
    try:
        run(["bluetoothctl", "power", "on"])
    except CommandError as e:
        raise BluetoothError(f"Bluetooth adapter unavailable: {e}") from e
    if is_connected(mac):
        return
    try:
        run(["bluetoothctl", "connect", mac], timeout=timeout)
    except CommandError as e:
        if not is_connected(mac):
            raise BluetoothError(f"Could not connect {mac}: {e}") from e


def _wait_for_card(card: str, timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    while card not in audio.list_cards():
        if time.monotonic() >= deadline:
            return False
        time.sleep(0.5)
    return True


def bring_up_sink(mac: str, timeout: float = 15) -> Sink:
    mac = normalize_mac(mac)
    connect(mac)
    card = card_name(mac)
    if not _wait_for_card(card, timeout):
        raise BluetoothError(f"{card} never appeared in PipeWire")
    try:
        audio.set_card_profile(card, A2DP_PROFILES)
    except CommandError as e:
        raise BluetoothError(f"Could not switch {card} to A2DP: {e}") from e
    sink = audio.wait_for_sink(lambda s: is_device_sink(s, mac), timeout)
    if sink is None:
        raise BluetoothError(f"No A2DP sink appeared for {mac}")
    return sink
