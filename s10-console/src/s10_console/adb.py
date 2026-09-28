from __future__ import annotations

import ipaddress
import re
import shlex
import shutil
import socket
import struct
import threading
import time
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from .models import ConsoleError, Device, Identity, MissingDevice, Profile, serial_text, text
from .processes import Result, Runner, diagnostic, environment

TLS_KIND = "_adb-tls-connect._tcp"
PROPERTIES = (
    "ro.serialno",
    "ro.product.model",
    "ro.product.manufacturer",
    "ro.product.device",
    "ro.build.version.sdk",
)
NAVIGATION = {"Back": 4, "Home": 3, "Recents": 187, "Wake": 224}


def devices(output: str) -> list[Device]:
    result = []
    for line in output.splitlines():
        words = line.split()
        if not words or words[0] in ("List", "*"):
            continue
        if len(words) < 2:
            raise ConsoleError("Malformed ADB device listing.")
        state = "no permissions" if words[1:3] == ["no", "permissions"] else words[1]
        endpoint = (
            words[0]
            if state == "no permissions" and set(words[0]) == {"?"}
            else serial_text(words[0])
        )
        props = dict(part.split(":", 1) for part in words[2:] if ":" in part)
        transport = props.get("transport_id", "0")
        if not transport.isascii() or not transport.isdecimal():
            raise ConsoleError("Malformed ADB transport identity.")
        model = props.get("model", "").replace("_", "-")
        usb = bool(props.get("usb")) or (
            ":" not in endpoint and "." not in endpoint and not endpoint.startswith("emulator-")
        )
        result.append(Device(endpoint, state, model, int(transport), usb, props.get("usb", "")))
    return result


def service_name(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,200}\._adb-tls-connect\._tcp", value):
        raise ConsoleError(
            "Expected a discovered TLS Wireless Debugging service, not an IP address."
        )
    return value


def lan_endpoint(value: str) -> str:
    match = re.fullmatch(r"([0-9.]+):([0-9]{1,5})", value)
    if match is None:
        raise ConsoleError("Use the phone's same-LAN IPv4 address and port (address:port).")
    try:
        address = ipaddress.IPv4Address(match[1])
    except ipaddress.AddressValueError as exc:
        raise ConsoleError("Invalid IPv4 address.") from exc
    networks = ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
    if not any(address in ipaddress.ip_network(n) for n in networks):
        raise ConsoleError("Only private same-LAN IPv4 endpoints are supported. Use USB otherwise.")
    port = int(match[2])
    if not 1 <= port <= 65535 or port == 5555:
        raise ConsoleError("Use the Wireless Debugging pairing port, not legacy port 5555.")
    return f"{address}:{port}"


@dataclass(frozen=True)
class Service:
    name: str
    endpoint: str


def services(output: str) -> list[Service]:
    found = []
    for line in output.splitlines():
        words = line.split()
        if len(words) != 3 or words[1].rstrip(".") != TLS_KIND:
            continue
        full = f"{words[0]}.{TLS_KIND}"
        found.append(Service(service_name(full), lan_endpoint(words[2])))
    return found


def component(value: str) -> str:
    if (
        not re.fullmatch(
            r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+/"
            r"\.?[A-Za-z_$][A-Za-z0-9_.$]*",
            value,
        )
        or len(value) > 300
    ):
        raise ConsoleError("Invalid launchable Android component.")
    return value


def remote_file(value: str) -> str:
    text(value, 1024)
    path = PurePosixPath(value)
    if not value.startswith("/sdcard/") or any(p in (".", "..") for p in value.split("/")):
        raise ConsoleError("Choose a file beneath /sdcard/, without path traversal.")
    if len(path.parts) < 3 or path.parts[2] in ("Android", ".android_secure"):
        raise ConsoleError("Private/restricted Android storage is not available.")
    if value.endswith("/") or "//" in value:
        raise ConsoleError("Choose one file, not a directory.")
    return str(path)


def read_exact(sock: socket.socket, count: int, deadline: float | None = None) -> bytes:
    deadline = deadline if deadline is not None else time.monotonic() + 2
    result = bytearray()
    while len(result) < count:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ConsoleError("ADB readiness response timed out.")
        sock.settimeout(remaining)
        chunk = sock.recv(count - len(result))
        if not chunk:
            raise ConsoleError("ADB server closed its status connection.")
        result.extend(chunk)
    return bytes(result)


def server_ready() -> bool:
    try:
        with socket.create_connection(("127.0.0.1", 5037), timeout=1) as sock:
            deadline = time.monotonic() + 2
            sock.sendall(b"000chost:version")
            if read_exact(sock, 4, deadline) != b"OKAY":
                raise ConsoleError("Cannot verify the existing local ADB server.")
            length = int(read_exact(sock, 4, deadline), 16)
            if not 1 <= length <= 8 or int(read_exact(sock, length, deadline), 16) != 41:
                raise ConsoleError("Incompatible shared ADB server. Ask its owner to resolve it.")
            return True
    except ConnectionRefusedError:
        return False
    except (OSError, ValueError) as exc:
        raise ConsoleError("Cannot inspect local ADB safely; no server restart attempted.") from exc


def tls_endpoint_ready(endpoint: str) -> None:
    """Require ADB's STLS upgrade before sending any authentication or phone commands."""
    host, port = lan_endpoint(endpoint).split(":")
    payload = b"host::\0"
    connect = int.from_bytes(b"CNXN", "little")
    packet = struct.pack(
        "<6I", connect, 0x01000001, 1024 * 1024, len(payload), sum(payload), connect ^ 0xFFFFFFFF
    )
    try:
        with socket.create_connection((host, int(port)), timeout=2) as sock:
            sock.sendall(packet + payload)
            command, version, _, size, _, magic = struct.unpack("<6I", read_exact(sock, 24))
            if (
                command != int.from_bytes(b"STLS", "little")
                or magic != command ^ 0xFFFFFFFF
                or version < 0x01000000
                or size != 0
            ):
                raise ConsoleError("Endpoint did not require TLS. Plaintext ADB is refused.")
    except OSError as exc:
        raise MissingDevice("TLS endpoint is unreachable. Use USB to recover.") from exc


class Adb:
    def __init__(self, runner: Runner, executable: str | None = None) -> None:
        self.runner = runner
        self.executable = executable or shutil.which("adb") or ""
        self.env = environment()
        self._client_checked = False

    def command(
        self,
        args: list[str],
        cancel: threading.Event,
        *,
        timeout: float = 10,
        secret: str | None = None,
        check_server: bool = True,
    ) -> Result:
        if not self.executable:
            raise ConsoleError("ADB is missing. Install the Arch android-tools package.")
        self.check_client(cancel)
        if check_server and not server_ready():
            raise ConsoleError("ADB server is not running. Use Prepare > Start local ADB.")
        result = self.runner.run(
            [self.executable, "-H", "127.0.0.1", "-P", "5037", *args],
            label="ADB operation",
            cancel=cancel,
            timeout=timeout,
            env=self.env,
            secret=secret,
        )
        if result.code:
            raise ConsoleError(diagnostic(result.stdout + result.stderr, "ADB operation"))
        return result

    def check_client(self, cancel: threading.Event) -> None:
        if self._client_checked:
            return
        result = self.runner.run(
            [self.executable, "version"],
            label="ADB client version",
            cancel=cancel,
            env=self.env,
        )
        if result.code or not re.search(
            r"(?m)^Android Debug Bridge version 1\.0\.41$", result.stdout
        ):
            raise ConsoleError("Unsupported ADB client protocol. No shared server was modified.")
        self._client_checked = True

    def start_server(self, cancel: threading.Event) -> str:
        if not server_ready():
            self.command(["start-server"], cancel, check_server=False)
        if not server_ready():
            raise ConsoleError("Local ADB server did not become available.")
        return "Local ADB server is available. No existing server was stopped."

    def list_devices(self, cancel: threading.Event) -> list[Device]:
        return devices(self.command(["devices", "-l"], cancel).stdout)

    def list_services(self, cancel: threading.Event) -> list[Service]:
        return services(self.command(["mdns", "services"], cancel).stdout)

    def inspect(self, device: Device, cancel: threading.Event) -> Identity:
        current = [
            d
            for d in self.list_devices(cancel)
            if d.endpoint == device.endpoint and d.transport_id == device.transport_id
        ]
        if len(current) != 1:
            raise MissingDevice("Selected transport disappeared. Refresh and select it again.")
        if current[0].state != "device" or device.transport_id <= 0:
            raise ConsoleError(f"Selected phone is {current[0].state}; control is unavailable.")
        if device.usb and not current[0].eligible:
            raise ConsoleError("Select the USB Galaxy S10 SM-G973F; other phones are ineligible.")
        if device.usb and not current[0].usb_path:
            path = self.command(
                ["-t", str(device.transport_id), "get-devpath"], cancel
            ).stdout.strip()
            if not path.startswith("usb:"):
                raise ConsoleError("Cannot verify a physical USB transport for enrollment.")
        script = "; ".join(f"getprop {name}" for name in PROPERTIES)
        result = self.command(["-t", str(device.transport_id), "shell", script], cancel)
        values = result.stdout.strip().splitlines()
        if len(values) != 5 or not values[4].isascii() or not values[4].isdigit():
            raise ConsoleError("Cannot verify the phone's complete hardware/OS identity.")
        identity = Identity(values[0], values[1], values[2], values[3], int(values[4]))
        if device.usb and identity.serial != device.endpoint:
            raise ConsoleError("USB serial disagrees with phone identity. Enrollment refused.")
        return identity

    def resolve(
        self,
        identity: Identity,
        profile: Profile,
        tls_service: str,
        cancel: threading.Event,
        *,
        connect_missing: bool = False,
    ) -> Device:
        if profile.transport == "wifi":
            service_name(tls_service)
            matching_services = [s for s in self.list_services(cancel) if s.name == tls_service]
            if not matching_services:
                raise MissingDevice("Paired TLS endpoint unavailable. Reconnect USB to recover.")
            if len(matching_services) != 1:
                raise ConsoleError("Ambiguous TLS service; use USB to recover.")
            tls_endpoint_ready(matching_services[0].endpoint)
            endpoint = tls_service
        else:
            endpoint = identity.serial
        matches = [d for d in self.list_devices(cancel) if d.endpoint == endpoint]
        if not matches and profile.transport == "wifi" and connect_missing:
            self.connect_service(endpoint, cancel)
            matches = [d for d in self.list_devices(cancel) if d.endpoint == endpoint]
        if not matches:
            raise MissingDevice("Enrolled phone is missing. Check USB or the paired TLS service.")
        if len(matches) != 1:
            raise ConsoleError("Ambiguous device identity; no phone was selected.")
        chosen = matches[0]
        if chosen.usb != (profile.transport == "usb"):
            raise ConsoleError("Device transport does not match the selected profile.")
        if self.inspect(chosen, cancel) != identity:
            raise ConsoleError("Device identity changed. Refusing control of a different phone.")
        return chosen

    def shell(self, device: Device, args: list[str], cancel: threading.Event) -> str:
        result = self.command(["-t", str(device.transport_id), "shell", shlex.join(args)], cancel)
        combined = result.stdout + result.stderr
        if re.search(r"(?im)^(?:error|exception|securityexception|failure)\b", combined):
            raise ConsoleError(diagnostic(combined, "Android action"))
        return result.stdout.strip()

    def pair(self, endpoint: str, code: str, cancel: threading.Event) -> str:
        result = self.command(["pair", lan_endpoint(endpoint)], cancel, timeout=60, secret=code)
        if "Successfully paired to" not in result.stdout:
            raise ConsoleError("Pairing did not succeed. Request a fresh code on the S10.")
        return "Pairing accepted. Now select its TLS service and bind its verified S10 identity."

    def bind(self, name: str, identity: Identity, cancel: threading.Event) -> str:
        service_name(name)
        matches = [s for s in self.list_services(cancel) if s.name == name]
        if len(matches) != 1:
            raise ConsoleError("TLS service disappeared. Refresh discovery; no plaintext fallback.")
        tls_endpoint_ready(matches[0].endpoint)
        self.connect_service(name, cancel)
        self.resolve(identity, Profile("Verify TLS", "wifi"), name, cancel)
        return name

    def connect_service(self, name: str, cancel: threading.Event) -> None:
        result = self.command(["connect", service_name(name)], cancel, timeout=15)
        if not re.search(r"(?m)^(?:already )?connected to ", result.stdout):
            raise ConsoleError("TLS service did not connect. Use USB for recovery.")

    def apps(self, device: Device, cancel: threading.Event) -> list[str]:
        output = self.shell(
            device,
            [
                "cmd",
                "package",
                "query-activities",
                "--brief",
                "--components",
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.LAUNCHER",
            ],
            cancel,
        )
        return sorted({component(line.strip()) for line in output.splitlines() if "/" in line})

    def transfer(
        self,
        device: Device,
        direction: str,
        local: Path,
        remote: str,
        cancel: threading.Event,
        overwrite: bool,
    ) -> str:
        remote = remote_file(remote)
        local = local.absolute()
        root = self.shell(device, ["readlink", "-f", "/sdcard"], cancel)
        parent = self.shell(device, ["readlink", "-f", str(PurePosixPath(remote).parent)], cancel)
        if (
            not root.startswith("/storage/")
            or not (parent == root or parent.startswith(root + "/"))
            or parent == root + "/Android"
            or parent.startswith(root + "/Android/")
        ):
            raise ConsoleError("The resolved destination is outside accessible shared storage.")
        quoted = shlex.quote(remote)
        script = (
            f"if [ -L {quoted} ]; then echo link; "
            f"elif [ -f {quoted} ]; then echo file; "
            f"elif [ -e {quoted} ]; then echo other; else echo missing; fi"
        )
        status = self.command(
            ["-t", str(device.transport_id), "shell", script], cancel
        ).stdout.strip()
        if status not in ("file", "missing"):
            raise ConsoleError("Choose a regular remote file, not a directory or symbolic link.")
        if local.is_symlink():
            raise ConsoleError("Choose a regular local file, not a symbolic link.")
        if direction == "push":
            if not local.is_file():
                raise ConsoleError("Select one existing regular local file.")
            if status == "file" and not overwrite:
                raise ConsoleError("Confirm replacement of the remote file.")
            try:
                self.command(
                    ["-t", str(device.transport_id), "push", str(local), remote],
                    cancel,
                    timeout=300,
                )
            except ConsoleError as exc:
                raise ConsoleError(f"{exc} A partial remote file may remain; verify it.") from exc
            return "File sent. Interrupted pushes can leave a partial remote file; verify it."
        if direction != "pull":
            raise ConsoleError("Unsupported transfer direction.")
        if status != "file":
            raise ConsoleError("The selected remote file does not exist.")
        if local.exists() and (not overwrite or not local.is_file()):
            raise ConsoleError("Confirm replacement of a regular local file.")
        import os
        import tempfile

        fd, name = tempfile.mkstemp(prefix=".s10-download-", dir=local.parent)
        os.close(fd)
        temporary = Path(name)
        try:
            self.command(
                ["-t", str(device.transport_id), "pull", remote, str(temporary)],
                cancel,
                timeout=300,
            )
            if not overwrite and local.exists():
                raise ConsoleError("Destination appeared during transfer; it was not replaced.")
            os.replace(temporary, local)
        finally:
            temporary.unlink(missing_ok=True)
        return "File received."
