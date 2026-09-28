from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import StrEnum


class ConsoleError(Exception):
    """An actionable, safe-to-display error."""


class MissingDevice(ConsoleError):
    pass


class Cancelled(ConsoleError):
    pass


def text(value: object, maximum: int = 128) -> str:
    if (
        not isinstance(value, str)
        or not 1 <= len(value) <= maximum
        or any(ord(c) < 32 or ord(c) == 127 for c in value)
    ):
        raise ConsoleError("Invalid text in configuration or device response.")
    return value


def serial_text(value: object) -> str:
    result = text(value)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]*", result):
        raise ConsoleError("Invalid device serial.")
    return result


@dataclass(frozen=True)
class Device:
    endpoint: str
    state: str
    model: str
    transport_id: int
    usb: bool
    usb_path: str = ""

    @property
    def eligible(self) -> bool:
        return self.usb and (
            self.model.replace("_", "-") == "SM-G973F" or self.state == "usb metadata"
        )


@dataclass(frozen=True)
class Identity:
    serial: str
    model: str
    manufacturer: str
    codename: str
    api: int

    def __post_init__(self) -> None:
        serial_text(self.serial)
        if (
            self.model != "SM-G973F"
            or self.manufacturer.lower() != "samsung"
            or self.codename != "beyond1"
            or type(self.api) is not int
            or self.api != 31
        ):
            raise ConsoleError("This console targets the Galaxy S10 SM-G973F / Android 12 only.")


@dataclass(frozen=True)
class Profile:
    name: str
    transport: str = "usb"
    size: int = 1600
    fps: int = 60
    bitrate: int = 8
    keyboard: str = "uhid"

    def __post_init__(self) -> None:
        text(self.name, 60)
        if self.transport not in ("usb", "wifi") or self.keyboard not in ("uhid", "sdk"):
            raise ConsoleError("Unsupported transport or keyboard profile.")
        for value, low, high in ((self.size, 640, 2560), (self.fps, 15, 60), (self.bitrate, 1, 20)):
            if type(value) is not int or not low <= value <= high:
                raise ConsoleError("Profile quality is outside supported limits.")


ACCEPTANCE = (
    "USB viewing and input",
    "Two USB reconnects without phone touch",
    "Viewer and console restart",
    "Display off and wake",
    "TLS Wi-Fi reconnect",
    "PC / ADB restart (owner action)",
    "Phone reboot / known PIN (owner action)",
    "Protected content limitations reviewed",
    "App launch and disposable file transfer",
    "HDMI / DeX fallback rehearsed",
)


@dataclass
class Settings:
    identity: Identity | None = None
    name: str = "My Galaxy S10"
    profiles: list[Profile] = field(
        default_factory=lambda: [
            Profile("USB Balanced"),
            Profile("Wi-Fi Conservative", "wifi", 1280, 30, 4),
        ]
    )
    tls_service: str = ""
    favorites: list[str] = field(default_factory=list)
    acceptance: dict[str, str] = field(default_factory=dict)


class State(StrEnum):
    IDLE = "Idle"
    CHECKING = "Checking identity"
    STARTING = "Starting viewer"
    RUNNING = "Viewer running"
    WAITING = "Waiting for the same phone"
    STOPPING = "Stopping"
    FAILED = "Stopped with an error"


@dataclass
class Recovery:
    attempts: int = 0
    deadline: float | None = None
    active: bool = False

    def start(self) -> None:
        self.attempts = 0
        self.deadline = None
        self.active = True

    def stop(self) -> None:
        self.active = False

    def disconnected(self, now: float) -> None:
        if self.deadline is None:
            self.deadline = now + 60

    def delay(self, now: float) -> int:
        if (
            not self.active
            or self.attempts >= 6
            or (self.deadline is not None and now >= self.deadline)
        ):
            raise ConsoleError("Recovery limit reached. Check the cable/phone, then click Start.")
        return (1, 2, 4, 8, 8, 8)[self.attempts]

    def launched(self, now: float) -> None:
        self.delay(now)
        self.attempts += 1

    def connected(self) -> None:
        self.deadline = None
