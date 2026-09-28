from __future__ import annotations

import os
import selectors
import signal
import subprocess
import threading
import time
from collections.abc import Callable, Mapping, Sequence
from contextlib import suppress
from dataclasses import dataclass

from .models import Cancelled, ConsoleError


@dataclass(frozen=True)
class Result:
    code: int
    stdout: str
    stderr: str


def environment(source: Mapping[str, str] | None = None) -> dict[str, str]:
    env = dict(os.environ if source is None else source)
    for key in (
        "ANDROID_SERIAL",
        "ADB_SERVER_SOCKET",
        "ADB_TRACE",
        "ADB_VENDOR_KEYS",
        "ANDROID_ADB_SERVER_ADDRESS",
        "ANDROID_ADB_SERVER_PORT",
        "SCRCPY_SERVER_PATH",
        "ADB",
        "ADB_SERVER_PORT",
    ):
        env.pop(key, None)
    env["ADB_SERVER_SOCKET"] = "tcp:127.0.0.1:5037"
    env["ANDROID_ADB_SERVER_ADDRESS"] = "127.0.0.1"
    env["ANDROID_ADB_SERVER_PORT"] = "5037"
    return env


def diagnostic(output: str, label: str) -> str:
    lower = output.lower()
    if "unauthorized" in lower:
        return "Phone unauthorized: unlock it and approve this PC while the screen still works."
    if "offline" in lower:
        return (
            "Phone is offline. Check its cable and boot state; no global ADB restart was attempted."
        )
    if "permission denied" in lower or "not permitted" in lower:
        return f"{label}: access denied by Android or the local filesystem."
    if "no space" in lower:
        return f"{label}: insufficient storage."
    if "clipboard" in lower:
        return (
            "Clipboard transfer failed. Use explicit viewer copy/paste; contents were not logged."
        )
    if "audio" in lower:
        return "Audio forwarding failed. Try video-only or the AAC audio compatibility option."
    if "uhid" in lower:
        return "Physical keyboard unavailable. Select the SDK compatibility keyboard and retry."
    return f"{label} failed. Check connection, permissions, and backend compatibility."


class Runner:
    """Run only registered child process groups; never log command arguments."""

    def run(
        self,
        argv: Sequence[str],
        *,
        label: str,
        cancel: threading.Event,
        timeout: float | None = 10,
        env: Mapping[str, str] | None = None,
        secret: str | None = None,
        started: Callable[[], None] | None = None,
        output: Callable[[str], None] | None = None,
        pass_fds: tuple[int, ...] = (),
    ) -> Result:
        if cancel.is_set():
            raise Cancelled("Operation cancelled.")
        if secret is not None and (
            not secret.isascii() or not secret.isdigit() or len(secret) != 6
        ):
            raise ConsoleError("Enter the six-digit pairing code shown on the phone.")
        try:
            child = subprocess.Popen(
                list(argv),
                stdin=subprocess.PIPE if secret is not None else subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=env,
                start_new_session=True,
                pass_fds=pass_fds,
            )
        except OSError as exc:
            raise ConsoleError(
                f"{label} could not start. Check the configured executable."
            ) from exc
        stdout = bytearray()
        stderr = bytearray()
        pending_output: dict[int, bytearray] = {id(stdout): bytearray(), id(stderr): bytearray()}
        prompt = bytearray()
        pairing = secret is not None
        sent = False
        began = time.monotonic()
        try:
            if started is not None:
                started()
            with selectors.DefaultSelector() as selector:
                assert child.stdout is not None and child.stderr is not None
                selector.register(child.stdout, selectors.EVENT_READ, stdout)
                selector.register(child.stderr, selectors.EVENT_READ, stderr)
                while selector.get_map():
                    if cancel.is_set():
                        raise Cancelled("Operation cancelled.")
                    if timeout is not None and time.monotonic() - began >= timeout:
                        raise ConsoleError(f"{label} timed out. No shared server was restarted.")
                    events = selector.select(0.1)
                    for key, _ in events:
                        chunk = os.read(key.fd, 4096)
                        target: bytearray = key.data
                        if not chunk:
                            pending = pending_output[id(target)]
                            if output is not None and not pairing and pending:
                                output(pending.decode("utf-8", errors="replace") + "\n")
                                pending.clear()
                            selector.unregister(key.fileobj)
                            continue
                        target.extend(chunk)
                        if len(target) > 262144:
                            if timeout is None:
                                del target[:-65536]
                            else:
                                raise ConsoleError(f"{label} returned too much data.")
                        if secret is not None and not sent:
                            prompt.extend(chunk)
                            del prompt[:-4096]
                            if b"enter pairing code:" in prompt.lower():
                                assert child.stdin is not None
                                child.stdin.write((secret + "\n").encode("ascii"))
                                child.stdin.flush()
                                child.stdin.close()
                                secret = None
                                sent = True
                                prompt.clear()
                        elif output is not None and not pairing:
                            pending = pending_output[id(target)]
                            pending.extend(chunk)
                            while b"\n" in pending:
                                line, _, rest = pending.partition(b"\n")
                                pending[:] = rest
                                output(line.decode("utf-8", errors="replace") + "\n")
                            if len(pending) > 8192:
                                output(pending.decode("utf-8", errors="replace") + "\n")
                                pending.clear()
                    if not events and child.poll() is not None:
                        break
            code = child.wait(timeout=2)
            if pairing:
                accepted = sent and b"Successfully paired to" in stdout
                return Result(
                    code, "Successfully paired to selected endpoint" if accepted else "", ""
                )
            return Result(
                code,
                stdout.decode("utf-8", errors="replace"),
                stderr.decode("utf-8", errors="replace"),
            )
        except (BrokenPipeError, subprocess.TimeoutExpired) as exc:
            raise ConsoleError(f"{label} closed unexpectedly.") from exc
        finally:
            secret = None
            if child.poll() is None:
                self.signal_group(child.pid, signal.SIGINT)
                try:
                    child.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    self.signal_group(child.pid, signal.SIGKILL)
                    child.wait()
                    if output is not None:
                        output(
                            "ERROR: Forced termination; upstream cleanup is unverified. "
                            "No shared mappings were removed."
                        )
            for stream in (child.stdout, child.stderr, child.stdin):
                if stream is not None:
                    stream.close()

    @staticmethod
    def signal_group(pid: int, sig: signal.Signals) -> None:
        # The owned child may exit between poll() and the signal.
        with suppress(ProcessLookupError):
            os.killpg(pid, sig)
