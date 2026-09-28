from __future__ import annotations

import re
import shutil
import threading
from collections.abc import Callable
from dataclasses import dataclass

from .adb import Adb
from .models import ConsoleError, Device, Identity, Profile, text
from .processes import Result, Runner, diagnostic, environment

REQUIRED = (
    "--serial",
    "--no-clipboard-autosync",
    "--no-audio",
    "--video-codec",
    "--max-size",
    "--max-fps",
    "--video-bit-rate",
    "--keyboard",
    "--mouse",
    "--window-title",
    "--shortcut-mod",
    "--pause-on-exit",
    "--turn-screen-off",
    "--audio-source",
    "--audio-codec",
    "--require-audio",
)


@dataclass(frozen=True)
class SessionOptions:
    profile: Profile
    name: str
    audio: bool = False
    screen_off: bool = False
    aac: bool = False


def capabilities(version: str, help_text: str) -> str:
    match = re.search(r"\bscrcpy (\d+)\.(\d+)(?:\.(\d+))?", version)
    if match is None or int(match[1]) != 4 or int(match[2]) < 1:
        raise ConsoleError("scrcpy 4.1 or a capability-compatible 4.x release is required.")
    missing = [flag for flag in REQUIRED if not re.search(re.escape(flag) + r"\b", help_text)]
    if missing:
        raise ConsoleError("scrcpy lacks required controls/privacy options: " + ", ".join(missing))
    return match[0]


def viewer_message(line: str) -> str | None:
    if "Forced termination" in line:
        return (
            "Viewer forced to stop; upstream cleanup is unverified. "
            "No shared mappings were removed."
        )
    if re.search(r"\b(?:ERROR|CRITICAL):", line):
        return diagnostic(line, "Viewer")
    if re.search(r"\bWARN:", line):
        if "clipboard" in line.lower():
            return diagnostic(line, "Clipboard transfer")
        if "Could not set window icon" in line:
            return (
                "Viewer warning: the desktop cannot set its window icon. "
                "This does not establish a video or control failure."
            )
        return (
            "Viewer reported a warning. Check the live image and controls; "
            "process failure has not been established."
        )
    return None


def command(executable: str, device: Device, options: SessionOptions) -> list[str]:
    profile = options.profile
    name = text(options.name, 60)
    args = [
        executable,
        f"--serial={device.endpoint}",
        "--video-codec=h264",
        f"--max-size={profile.size}",
        f"--max-fps={profile.fps}",
        f"--video-bit-rate={profile.bitrate}M",
        f"--keyboard={profile.keyboard}",
        "--mouse=sdk",
        "--no-clipboard-autosync",
        "--shortcut-mod=lalt",
        "--pause-on-exit=false",
        f"--window-title=S10 Console - {name} - {profile.transport.upper()}",
    ]
    if options.audio:
        args.extend(
            [
                "--audio-source=output",
                "--require-audio",
                f"--audio-codec={'aac' if options.aac else 'opus'}",
            ]
        )
    else:
        args.append("--no-audio")
    if options.screen_off:
        args.append("--turn-screen-off")
    return args


class Scrcpy:
    def __init__(self, runner: Runner, adb: Adb, executable: str | None = None) -> None:
        self.runner = runner
        self.adb = adb
        self.executable = executable or shutil.which("scrcpy") or ""
        self.line_buffer = shutil.which("stdbuf") or ""

    def check(self, cancel: threading.Event) -> str:
        if not self.executable:
            raise ConsoleError("scrcpy is missing. Install the official Arch scrcpy package.")
        if not self.line_buffer:
            raise ConsoleError("coreutils stdbuf is required for timely native viewer diagnostics.")
        version = self.runner.run(
            [self.executable, "--version"], label="scrcpy version", cancel=cancel, env=environment()
        )
        help_result = self.runner.run(
            [self.executable, "--help"],
            label="scrcpy capabilities",
            cancel=cancel,
            env=environment(),
        )
        if version.code or help_result.code:
            raise ConsoleError("scrcpy dependency/capability check failed.")
        return capabilities(
            version.stdout + version.stderr, help_result.stdout + help_result.stderr
        )

    def view(
        self,
        identity: Identity,
        service: str,
        options: SessionOptions,
        cancel: threading.Event,
        started: Callable[[], None],
        output: Callable[[str], None],
        deadline: float | None = None,
        lease_fd: int | None = None,
    ) -> Result:
        import time

        self.check(cancel)
        device = self.adb.resolve(identity, options.profile, service, cancel, connect_missing=True)
        if deadline is not None and time.monotonic() >= deadline:
            raise ConsoleError("Recovery deadline expired. Click Start to retry.")
        env = environment()
        env["ADB"] = self.adb.executable
        return self.runner.run(
            [self.line_buffer, "-oL", "-eL", *command(self.executable, device, options)],
            label="Phone viewer",
            cancel=cancel,
            timeout=None,
            env=env,
            started=started,
            output=output,
            pass_fds=(lease_fd,) if lease_fd is not None else (),
        )
