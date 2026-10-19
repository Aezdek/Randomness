from __future__ import annotations

import json
import os
import tomllib
from dataclasses import asdict, dataclass, fields
from pathlib import Path

from capview.bluetooth import DEFAULT_MAC
from capview.video import Backend


def config_path() -> Path:
    base = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")
    return base / "capview" / "config.toml"


@dataclass
class Config:
    bluetooth_mac: str = DEFAULT_MAC
    bluetooth_autoconnect: bool = True
    preferred_sink: str = ""
    last_mode: str = ""
    backend: str = Backend.MPV.value
    hwdec: bool = True
    loopback_latency_ms: int = 30

    @classmethod
    def load(cls, path: Path | None = None) -> Config:
        path = path or config_path()
        try:
            data = tomllib.loads(path.read_text())
        except (OSError, tomllib.TOMLDecodeError):
            return cls()
        defaults = cls()
        values = {
            f.name: data[f.name]
            for f in fields(cls)
            if f.name in data and type(data[f.name]) is type(getattr(defaults, f.name))
        }
        return cls(**values)

    def save(self, path: Path | None = None) -> None:
        path = path or config_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text("".join(f"{k} = {_toml(v)}\n" for k, v in asdict(self).items()))
        tmp.replace(path)


def _toml(value: object) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int | float):
        return repr(value)
    return json.dumps(str(value))
