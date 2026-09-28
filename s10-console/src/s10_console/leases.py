from __future__ import annotations

import fcntl
import os
import stat
import threading
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path

from .models import ConsoleError, Device, serial_text
from .store import private


@dataclass
class HeldLease:
    fd: int
    references: int = 1


class LeasePool:
    """Cooperative, process-held hardware exclusion; never replace a lease inode."""

    def __init__(self, directory: Path | None = None) -> None:
        self.directory = directory or Path.home() / ".local/state/phone-device-leases"
        self.lock = threading.RLock()
        self.held: dict[str, HeldLease] = {}

    def acquire(self, serial: str) -> int:
        serial_text(serial)
        with self.lock:
            existing = self.held.get(serial)
            if existing is not None:
                existing.references += 1
                return existing.fd
            fd: int | None = None
            try:
                self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
                private(self.directory, True)
                path = self.directory / f"{serial}.lock"
                fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC, 0o600)
                info = os.fstat(fd)
                if (
                    not stat.S_ISREG(info.st_mode)
                    or info.st_uid != os.getuid()
                    or info.st_mode & 0o077
                    or info.st_nlink != 1
                ):
                    raise ConsoleError("Unsafe shared phone lease file; it was not changed.")
                try:
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError as exc:
                    raise ConsoleError(
                        "Phone busy: another session holds the shared hardware lease. "
                        "Ask the coordinator for a handoff; no waiting, fallback or owner kill."
                    ) from exc
                self.held[serial] = HeldLease(fd)
                return fd
            except (OSError, ConsoleError) as exc:
                if fd is not None:
                    os.close(fd)
                if isinstance(exc, ConsoleError):
                    raise
                raise ConsoleError("Cannot acquire the shared phone lease safely.") from exc

    def release(self, serial: str) -> None:
        with self.lock:
            lease = self.held.get(serial)
            if lease is None:
                raise ConsoleError("Cannot release a phone lease this app does not own.")
            lease.references -= 1
            if lease.references == 0:
                # Closing releases our descriptor; an inherited viewer descriptor keeps
                # exclusion in place if this console unexpectedly dies first.
                os.close(lease.fd)
                del self.held[serial]

    @contextmanager
    def hold(self, serial: str) -> Iterator[int]:
        fd = self.acquire(serial)
        try:
            yield fd
        finally:
            self.release(serial)

    def run(
        self, serial: str, action: Callable[[threading.Event], object], cancel: threading.Event
    ) -> object:
        with self.hold(serial):
            return action(cancel)


def usb_candidates(root: Path = Path("/sys/bus/usb/devices")) -> list[Device]:
    """Read local USB descriptors before any phone-specific ADB operation."""
    found = []
    try:
        for entry in root.iterdir():
            try:
                if (entry / "idVendor").read_text().strip() != "04e8":
                    continue
                serial = serial_text((entry / "serial").read_text().strip())
                found.append(
                    Device(serial, "usb metadata", "Unverified Samsung USB", 0, True, entry.name)
                )
            except FileNotFoundError:
                continue
    except OSError as exc:
        raise ConsoleError(
            "Cannot read local USB descriptors. Check host USB permissions."
        ) from exc
    return found
