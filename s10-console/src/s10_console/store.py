from __future__ import annotations

import base64
import binascii
import hashlib
import json
import os
import stat
import tempfile
from dataclasses import asdict
from pathlib import Path

from .models import ACCEPTANCE, ConsoleError, Identity, Profile, Settings, text


def fields(value: object, expected: set[str]) -> dict[str, object]:
    if not isinstance(value, dict) or set(value) != expected:
        raise ConsoleError("Invalid configuration fields. The original file has not been replaced.")
    return {str(k): v for k, v in value.items()}


def integer(value: object) -> int:
    if type(value) is not int:
        raise ConsoleError("Expected an integer in configuration.")
    return value


def parse_settings(raw: object) -> Settings:
    obj = fields(
        raw, {"schema", "identity", "name", "profiles", "tls_service", "favorites", "acceptance"}
    )
    if type(obj["schema"]) is not int or obj["schema"] != 1:
        raise ConsoleError("Unsupported configuration version.")
    identity = None
    if obj["identity"] is not None:
        item = fields(obj["identity"], {"serial", "model", "manufacturer", "codename", "api"})
        identity = Identity(
            text(item["serial"]),
            text(item["model"]),
            text(item["manufacturer"]),
            text(item["codename"]),
            integer(item["api"]),
        )
    profiles_raw = obj["profiles"]
    if not isinstance(profiles_raw, list) or not 1 <= len(profiles_raw) <= 12:
        raise ConsoleError("Expected between 1 and 12 profiles.")
    profiles = []
    for value in profiles_raw:
        p = fields(value, {"name", "transport", "size", "fps", "bitrate", "keyboard"})
        profiles.append(
            Profile(
                text(p["name"], 60),
                text(p["transport"]),
                integer(p["size"]),
                integer(p["fps"]),
                integer(p["bitrate"]),
                text(p["keyboard"]),
            )
        )
    if len({p.name for p in profiles}) != len(profiles):
        raise ConsoleError("Profile names must be unique.")
    service = obj["tls_service"]
    if not isinstance(service, str):
        raise ConsoleError("Invalid TLS service.")
    if service:
        from .adb import service_name

        service_name(service)
    favorites = obj["favorites"]
    if not isinstance(favorites, list) or len(favorites) > 30:
        raise ConsoleError("Invalid favorites list.")
    from .adb import component

    checked_favorites = [component(text(item, 300)) for item in favorites]
    acceptance = obj["acceptance"]
    if not isinstance(acceptance, dict) or set(acceptance) - set(ACCEPTANCE):
        raise ConsoleError("Invalid acceptance records.")
    checked_records = {text(k): text(v, 200) for k, v in acceptance.items()}
    return Settings(
        identity, text(obj["name"], 60), profiles, service, checked_favorites, checked_records
    )


def private(path: Path, directory: bool = False) -> None:
    info = path.lstat()
    kind = stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)
    if not kind or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ConsoleError(
            f"Unsafe owner/type/permissions on {path}. Expected private "
            f"{'directory (0700)' if directory else 'file (0600)'}."
        )


class Store:
    def __init__(self, directory: Path | None = None) -> None:
        root = Path(os.environ.get("XDG_CONFIG_HOME", str(Path.home() / ".config")))
        if not root.is_absolute():
            raise ConsoleError("XDG_CONFIG_HOME must be an absolute path.")
        self.directory = directory if directory is not None else root / "s10-console"
        self.path = self.directory / "settings.json"

    def load(self) -> Settings:
        try:
            if not self.directory.exists() and not self.directory.is_symlink():
                return Settings()
            private(self.directory, True)
            if not self.path.exists() and not self.path.is_symlink():
                return Settings()
            private(self.path)
            if self.path.stat().st_size > 65536:
                raise ConsoleError("Configuration is too large.")
            raw: object = json.loads(self.path.read_text(encoding="utf-8"))
            return parse_settings(raw)
        except (OSError, ValueError) as exc:
            raise ConsoleError("Cannot read configuration; original file preserved.") from exc

    def save(self, settings: Settings) -> None:
        raw = {"schema": 1, **asdict(settings)}
        parse_settings(raw)
        temporary: str | None = None
        try:
            self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
            private(self.directory, True)
            if self.path.exists() or self.path.is_symlink():
                private(self.path)
            fd, temporary = tempfile.mkstemp(prefix=".settings-", dir=self.directory)
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(raw, stream, ensure_ascii=True, indent=2)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
            temporary = None
            directory_fd = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        except OSError as exc:
            raise ConsoleError("Could not save private configuration.") from exc
        finally:
            if temporary is not None:
                Path(temporary).unlink(missing_ok=True)


def key_readiness(directory: Path | None = None) -> str:
    root = directory if directory is not None else Path.home() / ".android"
    try:
        private(root, True)
        private(root / "adbkey")
        public = root / "adbkey.pub"
        info = public.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_size > 16384:
            raise ConsoleError("Unsafe ADB public key file.")
        encoded = public.read_bytes().split()[0]
        fingerprint = hashlib.sha256(base64.b64decode(encoded, validate=True)).hexdigest()
        return (
            "Private key permissions are restrictive. Public key SHA-256:\n"
            f"{fingerprint}\n"
            "This is the key on disk, not proof of which key the shared server loaded. "
            "No private key was read. Test a user-performed PC restart before relying on it."
        )
    except (OSError, IndexError, binascii.Error) as exc:
        raise ConsoleError(
            "ADB key files are missing or unreadable. Authorize this PC first."
        ) from exc
