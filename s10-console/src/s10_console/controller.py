from __future__ import annotations

import threading
import time
from collections.abc import Callable
from copy import deepcopy
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from PySide6.QtCore import QObject, QRunnable, QThreadPool, QTimer, Signal, Slot

from .adb import NAVIGATION, Adb, Service, component
from .leases import LeasePool, usb_candidates
from .models import (
    ACCEPTANCE,
    Cancelled,
    ConsoleError,
    Device,
    Identity,
    MissingDevice,
    Recovery,
    Settings,
    State,
)
from .processes import Result, Runner, diagnostic
from .scrcpy import Scrcpy, SessionOptions, viewer_message
from .store import Store, key_readiness


@dataclass(frozen=True)
class Outcome:
    value: object = None
    error: ConsoleError | None = None


class Events(QObject):
    finished = Signal(str, object)
    started = Signal(str)
    notice = Signal(str)


class Work(QRunnable):
    def __init__(
        self, key: str, events: Events, action: Callable[[threading.Event], object]
    ) -> None:
        super().__init__()
        self.key, self.events, self.action = key, events, action
        self.cancel = threading.Event()

    def run(self) -> None:
        try:
            result = Outcome(self.action(self.cancel))
        except ConsoleError as exc:
            result = Outcome(error=exc)
        except Exception as exc:
            result = Outcome(
                error=ConsoleError(
                    f"Operation failed unexpectedly ({type(exc).__name__}); "
                    "no success was recorded. Check inputs and report this error."
                )
            )
        self.events.finished.emit(self.key, result)


class Controller(QObject):
    changed = Signal()
    message = Signal(str)

    def __init__(
        self,
        settings: Settings,
        store: Store,
        runner: Runner | None = None,
        leases: LeasePool | None = None,
    ) -> None:
        super().__init__()
        self.settings, self.store = settings, store
        self.saved_settings = deepcopy(settings)
        self.leases = leases or LeasePool()
        self.session_lease: str | None = None
        self.session_lease_fd: int | None = None
        self.runner = runner or Runner()
        self.adb = Adb(self.runner)
        self.scrcpy = Scrcpy(self.runner, self.adb)
        self.pool = QThreadPool(self)
        self.pool.setMaxThreadCount(3)
        self.events = Events(self)
        self.events.finished.connect(self.finished)
        self.events.started.connect(self.started)
        self.events.notice.connect(self.message)
        self.jobs: dict[str, Work] = {}
        self.devices: list[Device] = []
        self.services: list[Service] = []
        self.apps: list[str] = []
        self.candidate: Identity | None = None
        self.state = State.IDLE
        self.recovery = Recovery()
        self.options: SessionOptions | None = None
        self.reconnecting = False
        self.status = "No capture active. Refresh devices to begin preparation."
        self.backend = "Not checked"
        self.key_status = "Not checked; no private key is read."
        self.cycles = 0
        self.seen_usb = False
        self.usb_gap = False
        self.watching = False
        self.retry = QTimer(self)
        self.retry.setSingleShot(True)
        self.retry.timeout.connect(self.retry_view)
        self.watch = QTimer(self)
        self.watch.setInterval(2000)
        self.watch.timeout.connect(self.watch_tick)

    @property
    def busy(self) -> bool:
        return any(key != "view" for key in self.jobs)

    @property
    def active(self) -> bool:
        return self.recovery.active

    def report(self, message: str) -> None:
        self.status = message
        self.message.emit(message)
        self.changed.emit()

    def save(self) -> None:
        try:
            self.store.save(self.settings)
        except ConsoleError:
            self.settings = deepcopy(self.saved_settings)
            self.changed.emit()
            raise
        self.saved_settings = deepcopy(self.settings)
        self.changed.emit()

    def submit(self, key: str, action: Callable[[threading.Event], object]) -> None:
        if key in self.jobs or (key != "view" and self.busy):
            raise ConsoleError("Another operation is active. Finish or cancel it first.")
        task = Work(key, self.events, action)
        self.jobs[key] = task
        self.pool.start(task)
        self.changed.emit()

    def refresh(self) -> None:
        expected = self.settings.identity

        def scan(cancel: threading.Event) -> list[Device]:
            if expected is None:
                return usb_candidates()
            listed = self.adb.list_devices(cancel)
            if self.watching and expected is not None:
                for item in listed:
                    if (
                        item.endpoint == expected.serial
                        and item.state == "device"
                        and self.adb.inspect(item, cancel) != expected
                    ):
                        raise ConsoleError("Enrolled USB identity changed; recovery is unsafe.")
            return listed

        if expected is None:
            self.submit("devices", scan)
        else:
            self.device_submit("devices", expected.serial, scan)

    def device_submit(
        self, key: str, serial: str, action: Callable[[threading.Event], object]
    ) -> None:
        self.submit(key, lambda cancel: self.leases.run(serial, action, cancel))

    def verify(self, device: Device) -> None:
        if self.active or not device.eligible:
            raise ConsoleError("Stop control and explicitly select the eligible USB S10 first.")
        self.candidate = None

        def inspect(cancel: threading.Event) -> Identity:
            if device.state == "usb metadata":
                matches = [
                    item
                    for item in self.adb.list_devices(cancel)
                    if item.endpoint == device.endpoint
                ]
                if len(matches) != 1:
                    raise ConsoleError(
                        "Selected USB phone is absent or ambiguous in ADB. "
                        "Check debugging/authorization; no other phone was selected."
                    )
                return self.adb.inspect(matches[0], cancel)
            return self.adb.inspect(device, cancel)

        self.device_submit("identity", device.endpoint, inspect)

    def enroll(self, name: str) -> None:
        from .models import text

        if self.candidate is None or self.active:
            raise ConsoleError("Verify the USB S10 before enrollment.")
        if self.settings.identity != self.candidate:
            self.settings.tls_service = ""
            self.settings.acceptance.clear()
            self.settings.favorites.clear()
            self.cycles = 0
            self.seen_usb, self.usb_gap = False, False
        self.settings.identity = self.candidate
        self.settings.name = text(name, 60)
        self.save()
        self.report("S10 enrolled. Viewing/input and no-touch recovery are still untested.")

    def check_dependencies(self) -> None:
        self.submit("backend", self.scrcpy.check)

    def check_key(self) -> None:
        self.submit("key", lambda _: key_readiness())

    def start_server(self, serial: str) -> None:
        self.device_submit("server", serial, self.adb.start_server)

    def start(self, options: SessionOptions) -> None:
        if self.active or self.jobs:
            raise ConsoleError("Stop or finish the current operation first.")
        if self.settings.identity is None:
            raise ConsoleError("Enroll the verified USB S10 on Prepare before starting.")
        self.session_lease_fd = self.leases.acquire(self.settings.identity.serial)
        self.session_lease = self.settings.identity.serial
        self.options = options
        self.recovery.start()
        self.reconnecting = False
        self.launch_view()

    def launch_view(self) -> None:
        identity, options = self.settings.identity, self.options
        if identity is None or options is None:
            raise ConsoleError("No enrolled session profile.")
        service = self.settings.tls_service
        deadline = self.recovery.deadline
        self.state = State.CHECKING
        self.report("Verifying the exact enrolled phone before opening the external viewer.")
        pending_log = ""

        def notice(value: str) -> None:
            nonlocal pending_log
            pending_log += value
            if "Forced termination" in value or len(pending_log) > 8192:
                pending_log += "\n"
            lines = pending_log.split("\n")
            pending_log = lines.pop()
            for line in lines:
                message = viewer_message(line)
                if message is not None:
                    self.events.notice.emit(message)

        self.submit(
            "view",
            lambda cancel: self.scrcpy.view(
                identity,
                service,
                options,
                cancel,
                lambda: self.events.started.emit("view"),
                notice,
                deadline,
                self.session_lease_fd,
            ),
        )

    @Slot(str)
    def started(self, key: str) -> None:
        if key == "view" and self.active:
            if self.reconnecting:
                self.recovery.attempts += 1
            self.recovery.connected()
            self.state = State.RUNNING
            self.report(
                "External viewer process running. Confirm live image/input yourself. "
                "Stop: Alt+Q in viewer, or Ctrl+Shift+X here."
            )

    def stop(self) -> None:
        self.recovery.stop()
        self.retry.stop()
        self.watch.stop()
        self.watching = False
        for task in self.jobs.values():
            task.cancel.set()
        self.state = State.STOPPING if self.jobs else State.IDLE
        self.report("Stopping only console-owned processes. Shared ADB connections stay intact.")
        self.release_if_idle()

    def release_if_idle(self) -> None:
        if not self.active and not self.jobs and self.session_lease is not None:
            self.leases.release(self.session_lease)
            self.session_lease, self.session_lease_fd = None, None

    def cancel_tools(self) -> None:
        for key, task in self.jobs.items():
            if key != "view":
                task.cancel.set()

    def schedule_retry(self) -> None:
        try:
            self.recovery.disconnected(time.monotonic())
            seconds = self.recovery.delay(time.monotonic())
        except ConsoleError as exc:
            self.recovery.stop()
            self.state = State.FAILED
            self.report(str(exc))
            return
        self.reconnecting = True
        self.state = State.WAITING
        self.report(
            f"Connection lost; retrying only the same phone in {seconds}s. "
            f"{self.recovery.attempts}/6 recovery launches used; Stop cancels."
        )
        self.retry.start(seconds * 1000)

    @Slot()
    def retry_view(self) -> None:
        if not self.active:
            return
        try:
            self.recovery.delay(time.monotonic())
            self.launch_view()
        except ConsoleError as exc:
            self.recovery.stop()
            self.state = State.FAILED
            self.report(str(exc))
            self.release_if_idle()

    def verified_action(
        self, key: str, action: Callable[[Device, threading.Event], object]
    ) -> None:
        identity, options = self.settings.identity, self.options
        if not self.active or self.state != State.RUNNING or identity is None or options is None:
            raise ConsoleError("Start control of the verified S10 before using phone actions.")
        service = self.settings.tls_service
        self.device_submit(
            key,
            identity.serial,
            lambda cancel: action(
                self.adb.resolve(identity, options.profile, service, cancel),
                cancel,
            ),
        )

    def navigate(self, label: str) -> None:
        if label not in NAVIGATION:
            raise ConsoleError("Unsupported navigation action.")
        self.verified_action(
            "navigation",
            lambda device, cancel: self.adb.shell(
                device,
                ["input", "keyevent", str(NAVIGATION[label])],
                cancel,
            ),
        )

    def discover(self) -> None:
        if self.settings.identity is None:
            raise ConsoleError("Enroll the USB S10 before wireless discovery.")
        self.device_submit("services", self.settings.identity.serial, self.adb.list_services)

    def pair(self, endpoint: str, code: str) -> None:
        if self.settings.identity is None or self.active:
            raise ConsoleError("Enroll the USB S10, then stop control before pairing.")
        self.device_submit(
            "pair",
            self.settings.identity.serial,
            lambda cancel: self.adb.pair(endpoint, code, cancel),
        )

    def bind(self, name: str) -> None:
        identity = self.settings.identity
        if identity is None or self.active:
            raise ConsoleError("Enroll the USB S10, then stop control before binding Wi-Fi.")
        self.device_submit(
            "bind", identity.serial, lambda cancel: self.adb.bind(name, identity, cancel)
        )

    def refresh_apps(self) -> None:
        self.verified_action("apps", self.adb.apps)

    def launch_app(self, selected: str) -> None:
        selected = component(selected)
        if selected not in self.apps and selected not in self.settings.favorites:
            raise ConsoleError("Choose a discovered launchable app or an explicit saved favorite.")
        self.verified_action(
            "launch",
            lambda device, cancel: self.adb.shell(
                device,
                ["am", "start", "-n", selected],
                cancel,
            ),
        )

    def transfer(self, direction: str, local: Path, remote: str, overwrite: bool) -> None:
        self.verified_action(
            "transfer",
            lambda device, cancel: self.adb.transfer(
                device,
                direction,
                local,
                remote,
                cancel,
                overwrite,
            ),
        )

    def record(self, item: str, passed: bool) -> None:
        if item not in ACCEPTANCE or self.settings.identity is None:
            raise ConsoleError("Enroll the phone before recording acceptance.")
        if passed and item == ACCEPTANCE[1] and self.cycles < 2:
            raise ConsoleError("Observe two USB disappear/reappear cycles in this exercise first.")
        self.settings.acceptance[item] = (
            f"{'Owner confirmed' if passed else 'Failed / blocked'} "
            f"{datetime.now(UTC).isoformat(timespec='seconds')}"
        )
        self.save()

    def set_watching(self, enabled: bool) -> None:
        if enabled and self.settings.identity is None:
            raise ConsoleError("Enroll the S10 first.")
        self.watching = enabled
        if enabled:
            self.cycles = 0
            self.seen_usb, self.usb_gap = False, False
            self.watch.start()
        else:
            self.watch.stop()
        self.changed.emit()

    @Slot()
    def watch_tick(self) -> None:
        if not self.busy and self.state != State.STOPPING:
            self.refresh()

    @Slot(str, object)
    def finished(self, key: str, raw: object) -> None:
        try:
            self.finish_work(key, raw)
        finally:
            self.release_if_idle()

    def finish_work(self, key: str, raw: object) -> None:
        task = self.jobs.pop(key, None)
        if not isinstance(raw, Outcome) or task is None:
            self.report("Invalid worker completion; operation was not accepted.")
            return
        if task.cancel.is_set():
            if key == "view" or self.state == State.STOPPING:
                self.state = State.IDLE if not self.jobs else State.STOPPING
            self.report(
                "Operation stopped. No shared ADB connections were disconnected."
                + (
                    " A partial remote file may remain after a cancelled push."
                    if key == "transfer"
                    else ""
                )
            )
            return
        if key == "view":
            self.cancel_tools()
            if raw.error is not None:
                if isinstance(raw.error, MissingDevice) and self.reconnecting and self.active:
                    self.schedule_retry()
                else:
                    self.recovery.stop()
                    self.state = State.FAILED
                    self.report(str(raw.error))
            elif isinstance(raw.value, Result):
                if raw.value.code == 2 and self.active:
                    self.schedule_retry()
                else:
                    self.recovery.stop()
                    self.state = State.IDLE if raw.value.code == 0 else State.FAILED
                    self.report(
                        "Viewer closed; reconnect is disabled."
                        if raw.value.code == 0
                        else diagnostic(raw.value.stderr + raw.value.stdout, "Viewer startup")
                    )
            self.changed.emit()
            return
        if raw.error is not None:
            if key == "backend":
                self.backend = str(raw.error)
            elif key == "key":
                self.key_status = str(raw.error)
            elif key == "devices":
                self.devices = []
                if self.watching:
                    self.set_watching(False)
                    self.seen_usb, self.usb_gap = False, False
            if not isinstance(raw.error, Cancelled):
                self.report(str(raw.error))
            self.changed.emit()
            return
        value = raw.value
        if key == "devices" and isinstance(value, list):
            self.devices = [item for item in value if isinstance(item, Device)]
            if not self.devices and self.settings.identity is None:
                self.report(
                    "No Samsung USB descriptors found. Connect a data cable. "
                    "No phone-specific ADB operation was performed."
                )
            identity = self.settings.identity
            if self.watching and identity is not None:
                present = any(
                    d.endpoint == identity.serial and d.state == "device" for d in self.devices
                )
                if present:
                    if self.seen_usb and self.usb_gap:
                        self.cycles += 1
                    self.seen_usb, self.usb_gap = True, False
                elif self.seen_usb:
                    self.usb_gap = True
        elif key == "identity" and isinstance(value, Identity):
            self.candidate = value
            self.report(
                "Verified S10 identity. Review its serial and explicitly enroll this phone."
            )
        elif key == "services" and isinstance(value, list):
            self.services = [item for item in value if isinstance(item, Service)]
            if not self.services:
                self.report("No same-LAN TLS service found. Use USB; no plaintext fallback.")
        elif key == "apps" and isinstance(value, list):
            self.apps = [item for item in value if isinstance(item, str)]
            if not self.apps:
                self.report("No launchable apps were returned for this Android user.")
        elif key == "backend" and isinstance(value, str):
            self.backend = value + " capabilities verified; phone behavior is not yet tested."
        elif key == "key" and isinstance(value, str):
            self.key_status = value
        elif key == "bind" and isinstance(value, str):
            self.settings.tls_service = value
            try:
                self.save()
            except ConsoleError as exc:
                self.report(str(exc))
            else:
                self.report(
                    "TLS service bound to the enrolled S10. Select a Wi-Fi profile to start."
                )
        elif isinstance(value, str) and value:
            self.report(
                value if key in ("pair", "transfer", "server") else "Phone action completed."
            )
        self.changed.emit()
