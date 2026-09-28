import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

import pytest

from s10_console.desktop import exec_quote, install
from s10_console.models import ConsoleError


def test_launcher_native_entry_and_icon(tmp_path):
    path = tmp_path / "space directory" / "console"
    path.parent.mkdir()
    path.write_text("#!/bin/sh\nexit 0\n")
    path.chmod(0o755)
    entry = install(path, tmp_path / "data")
    validator = shutil.which("desktop-file-validate")
    if validator:
        result = subprocess.run([validator, str(entry)], capture_output=True, text=True)
        assert result.returncode == 0, result.stderr
    value = entry.read_text()
    assert f'Exec=/usr/bin/env -- "{path}"' in value
    assert "Terminal=false" in value
    assert (tmp_path / "data/icons/hicolor/scalable/apps/s10-console.svg").is_file()


def test_launcher_escapes_percent_quotes_and_shell_chars(tmp_path):
    path = tmp_path / 'space 100% "quote" $x `cmd` \\ file'
    quoted = exec_quote(path)
    assert "100%%" in quoted
    assert '\\\\"' in quoted and "\\\\$" in quoted
    with pytest.raises(ConsoleError):
        exec_quote(Path("relative"))


def test_packaged_idle_launch_from_other_cwd(tmp_path):
    env = dict(os.environ, QT_QPA_PLATFORM="offscreen")
    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "s10_console",
            "--smoke-test",
            "--config-dir",
            str(tmp_path / "config"),
        ],
        cwd=tmp_path,
        env=env,
        capture_output=True,
        text=True,
        timeout=10,
    )
    assert result.returncode == 0, result.stderr
    assert not (tmp_path / "config/settings.json").exists()


def test_entrypoint_version_no_gui():
    result = subprocess.run(
        [sys.executable, "-m", "s10_console", "--version"],
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert result.returncode == 0
    assert result.stdout.strip() == "S10 Console 0.1.0"


@pytest.mark.skipif(shutil.which("gio") is None, reason="GNOME launcher unavailable")
def test_actual_desktop_execution_with_special_path_characters(tmp_path):
    path = tmp_path / '100% "quoted" $literal `literal` \\ program'
    marker = tmp_path / "launched"
    path.write_text(
        f"#!{sys.executable}\nfrom pathlib import Path\n"
        f"Path({str(marker)!r}).write_text('launched')\n"
    )
    path.chmod(0o755)
    entry = install(path, tmp_path / "desktop")
    result = subprocess.run(
        ["gio", "launch", str(entry)], cwd=tmp_path, capture_output=True, text=True, timeout=5
    )
    assert result.returncode == 0, result.stderr
    deadline = time.monotonic() + 3
    while not marker.exists() and time.monotonic() < deadline:
        time.sleep(0.02)
    assert marker.read_text() == "launched"
