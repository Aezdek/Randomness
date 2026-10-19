from __future__ import annotations

import shutil
import subprocess
from collections.abc import Sequence


class CommandError(RuntimeError):
    pass


def run(args: Sequence[str], timeout: float = 10, allow_failure: bool = False) -> str:
    try:
        result = subprocess.run(
            list(args), capture_output=True, text=True, timeout=timeout, check=False
        )
    except FileNotFoundError as e:
        raise CommandError(f"{args[0]} is not installed") from e
    except subprocess.TimeoutExpired as e:
        raise CommandError(f"{' '.join(args)} timed out after {timeout:g}s") from e
    if result.returncode != 0 and not (allow_failure and result.stdout.strip()):
        detail = result.stderr.strip() or result.stdout.strip()
        raise CommandError(detail or f"{' '.join(args)} exited with {result.returncode}")
    return result.stdout


def die_with_parent(args: Sequence[str]) -> list[str]:
    """Have the kernel SIGTERM the child if this process dies, however it dies."""
    setpriv = shutil.which("setpriv")
    return [setpriv, "--pdeathsig", "TERM", "--", *args] if setpriv else list(args)
