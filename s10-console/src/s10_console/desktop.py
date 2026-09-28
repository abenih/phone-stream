from __future__ import annotations

import argparse
import os
import sys
from importlib.resources import files
from pathlib import Path

from .models import ConsoleError


def exec_quote(path: Path) -> str:
    value = str(path)
    if not path.is_absolute() or any(ord(char) < 32 for char in value):
        raise ConsoleError(
            "The launcher needs an absolute executable path without control characters."
        )
    for char in ("\\", '"', "$", "`"):
        value = value.replace(char, "\\\\" + char if char != "\\" else "\\\\\\\\")
    return '"' + value.replace("%", "%%") + '"'


def install(executable: Path, data_home: Path) -> Path:
    if not executable.is_file() or not os.access(executable, os.X_OK):
        raise ConsoleError("Install the s10-console entry point before creating its launcher.")
    if not data_home.is_absolute():
        raise ConsoleError("The desktop data directory must be absolute.")
    entry = (
        "[Desktop Entry]\nType=Application\nName=S10 Console\n"
        "Comment=USB-first, owner-authorized Galaxy S10 control\n"
        # GLib checks the first executable before expanding %% in desktop field codes.
        f"Exec=/usr/bin/env -- {exec_quote(executable)}\n"
        "Icon=s10-console\nTerminal=false\nCategories=Network;RemoteAccess;\n"
        "StartupNotify=true\n"
    )
    apps = data_home / "applications"
    icons = data_home / "icons/hicolor/scalable/apps"
    apps.mkdir(parents=True, exist_ok=True)
    icons.mkdir(parents=True, exist_ok=True)
    icon = files("s10_console").joinpath("resources/s10-console.svg").read_bytes()
    (icons / "s10-console.svg").write_bytes(icon)
    target = apps / "s10-console.desktop"
    target.write_text(entry, encoding="utf-8")
    return target


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Install the user-local S10 Console desktop launcher"
    )
    parser.add_argument(
        "--executable", type=Path, default=Path(sys.executable).parent / "s10-console"
    )
    parser.add_argument(
        "--data-home",
        type=Path,
        default=Path(os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local/share"))),
    )
    args = parser.parse_args()
    try:
        path = install(args.executable.absolute(), args.data_home)
    except (ConsoleError, OSError) as exc:
        print(f"Launcher installation failed: {exc}", file=sys.stderr)
        return 1
    print(f"Installed {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
