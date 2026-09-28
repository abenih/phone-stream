import threading
import time

import pytest

from s10_console.controller import Controller, Outcome, Work
from s10_console.leases import LeasePool
from s10_console.models import (
    ACCEPTANCE,
    Cancelled,
    ConsoleError,
    MissingDevice,
    Recovery,
    Settings,
    State,
)
from s10_console.processes import Result
from s10_console.scrcpy import SessionOptions
from s10_console.store import Store


@pytest.fixture
def controller(qapp, tmp_path, identity):
    c = Controller(
        Settings(identity=identity),
        Store(tmp_path / "config"),
        leases=LeasePool(tmp_path / "leases"),
    )
    yield c
    c.stop()
    c.pool.waitForDone(5000)
    qapp.processEvents()


def options(c):
    return SessionOptions(c.settings.profiles[0], c.settings.name)


def complete(qtbot, c):
    qtbot.waitUntil(lambda: "view" not in c.jobs, timeout=3000)
    c.retry.stop()


def test_normal_viewer_exit_ends_intent(qtbot, controller, monkeypatch):
    c = controller
    started_states = []
    c.changed.connect(lambda: started_states.append(c.state))

    def view(identity, service, opts, cancel, started, notice, deadline, lease_fd):
        started()
        return Result(0, "", "")

    monkeypatch.setattr(c.scrcpy, "view", view)
    c.start(options(c))
    complete(qtbot, c)
    assert State.RUNNING in started_states
    assert c.state == State.IDLE and not c.active
    assert not c.retry.isActive()


@pytest.mark.parametrize(
    "failure",
    [
        ConsoleError("unauthorized"),
        ConsoleError("wrong identity"),
        ConsoleError("missing backend"),
        MissingDevice("missing"),
    ],
)
def test_initial_failure_never_retries(qtbot, controller, monkeypatch, failure):
    c = controller

    def view(*args):
        raise failure

    monkeypatch.setattr(c.scrcpy, "view", view)
    c.start(options(c))
    complete(qtbot, c)
    assert c.state == State.FAILED and not c.active
    assert c.status == str(failure)


def test_retry_exactly_six_launches_then_stops(qtbot, controller, monkeypatch):
    c = controller
    calls = []

    def view(identity, service, opts, cancel, started, notice, deadline, lease_fd):
        calls.append(identity)
        started()
        return Result(2, "", "")

    monkeypatch.setattr(c.scrcpy, "view", view)
    c.start(options(c))
    complete(qtbot, c)
    for _ in range(6):
        assert c.state == State.WAITING
        c.retry_view()
        complete(qtbot, c)
    assert len(calls) == 7  # initial launch plus six recoveries
    assert all(item == c.settings.identity for item in calls)
    assert c.recovery.attempts == 6
    assert not c.active and c.state == State.FAILED


def test_recovery_deadline_and_global_attempt_budget():
    r = Recovery()
    r.start()
    r.disconnected(100)
    assert r.delay(159.9) == 1
    with pytest.raises(ConsoleError):
        r.delay(160)
    r.start()
    for _ in range(6):
        r.disconnected(100)
        r.launched(101)
        r.connected()
    with pytest.raises(ConsoleError):
        r.delay(102)
    r.start()
    assert r.attempts == 0


def test_stop_cancels_pending_view_and_no_late_restart(qtbot, controller, monkeypatch):
    c = controller
    entered = threading.Event()

    def view(identity, service, opts, cancel, started, notice, deadline, lease_fd):
        entered.set()
        cancel.wait(2)
        raise Cancelled("Stopped")

    monkeypatch.setattr(c.scrcpy, "view", view)
    c.start(options(c))
    qtbot.waitUntil(entered.is_set)
    c.stop()
    complete(qtbot, c)
    c.retry_view()
    assert not c.jobs and not c.active and not c.retry.isActive()


def test_normal_exit_cancels_queued_phone_actions(controller):
    c = controller
    c.recovery.start()
    c.state = State.RUNNING
    view = Work("view", c.events, lambda _: None)
    launch = Work("launch", c.events, lambda _: None)
    c.jobs = {"view": view, "launch": launch}
    c.finished("view", Outcome(Result(0, "", "")))
    assert launch.cancel.is_set()
    c.finished("launch", Outcome("should not appear"))
    assert "should not appear" not in c.status


def test_unexpected_worker_error_completes_with_visible_failure(qtbot, controller, monkeypatch):
    c = controller

    def bad(cancel):
        raise TypeError("sensitive detail")

    c.submit("synthetic", bad)
    qtbot.waitUntil(lambda: not c.jobs)
    assert "TypeError" in c.status and "sensitive detail" not in c.status


def test_observation_not_equivalent_to_owner_success(controller, device):
    c = controller
    c.watching = True
    for listing in ([device], [], [device], [], [device]):
        c.jobs["devices"] = Work("devices", c.events, lambda _: None)
        c.finished("devices", Outcome(listing))
    assert c.cycles == 2
    assert not c.settings.acceptance
    c.record(ACCEPTANCE[1], True)
    assert c.settings.acceptance[ACCEPTANCE[1]].startswith("Owner confirmed")


def test_reconnect_acceptance_requires_two_observations(controller):
    with pytest.raises(ConsoleError, match="two USB"):
        controller.record(ACCEPTANCE[1], True)


def test_phone_tools_require_active_verified_session(controller):
    with pytest.raises(ConsoleError, match="Start control"):
        controller.navigate("Home")


def test_saved_settings_never_restart_capture(qapp, tmp_path, identity):
    store = Store(tmp_path / "config")
    store.save(Settings(identity=identity))
    c = Controller(store.load(), store)
    assert c.state == State.IDLE and not c.jobs and not c.active


def test_failed_save_rolls_back_runtime_selection(controller, monkeypatch):
    c = controller
    original = c.settings.name
    c.settings.name = "Unsaved change"

    def fail(settings):
        raise ConsoleError("Cannot save")

    monkeypatch.setattr(c.store, "save", fail)
    with pytest.raises(ConsoleError, match="Cannot save"):
        c.save()
    assert c.settings.name == original


def test_quiet_running_backend_and_benign_warning_do_not_fail(
    qtbot,
    controller,
    monkeypatch,
):
    c = controller
    messages = []
    c.message.connect(messages.append)

    def view(identity, service, opts, cancel, started, notice, deadline, lease_fd):
        started()
        notice("WA")
        notice("RN: Could not set window icon: unsupported Wayland icon protocol\n")
        cancel.wait(3)
        raise Cancelled("Stopped")

    monkeypatch.setattr(c.scrcpy, "view", view)
    c.start(options(c))
    qtbot.waitUntil(lambda: c.state == State.RUNNING)
    qtbot.wait(150)
    assert c.state == State.RUNNING and c.active
    assert any("window icon" in message for message in messages)
    assert not any("Viewer failed" in message for message in messages)
    c.stop()
    complete(qtbot, c)


def finish_synthetic_job(controller, key, outcome):
    controller.jobs[key] = Work(key, controller.events, lambda _: None)
    controller.finished(key, outcome)


@pytest.mark.parametrize(
    "key, attribute, successful_value",
    [("backend", "backend", "scrcpy 4.1"), ("key", "key_status", "Public key check passed")],
)
def test_failed_probe_replaces_previous_success(controller, key, attribute, successful_value):
    finish_synthetic_job(controller, key, Outcome(successful_value))
    assert successful_value in getattr(controller, attribute)
    error = ConsoleError("Synthetic probe failure")
    finish_synthetic_job(controller, key, Outcome(error=error))
    assert getattr(controller, attribute) == str(error)
    assert controller.status == str(error)


def test_failed_device_refresh_clears_stale_devices_and_stops_observation(controller, device):
    controller.set_watching(True)
    finish_synthetic_job(controller, "devices", Outcome([device]))
    controller.usb_gap = True
    controller.cycles = 1
    assert controller.devices and controller.watch.isActive()

    finish_synthetic_job(
        controller, "devices", Outcome(error=ConsoleError("Synthetic scan failed"))
    )

    assert controller.devices == []
    assert not controller.watching and not controller.watch.isActive()
    assert not controller.seen_usb and not controller.usb_gap
    controller.set_watching(True)
    assert controller.cycles == 0
    assert not controller.seen_usb and not controller.usb_gap
    controller.set_watching(False)


def test_expired_retry_timer_releases_idle_lease_without_launch(
    controller, identity, monkeypatch, tmp_path
):
    assert controller.leases.directory == tmp_path / "leases"
    controller.session_lease_fd = controller.leases.acquire(identity.serial)
    controller.session_lease = identity.serial
    controller.recovery.start()
    controller.recovery.deadline = time.monotonic() - 1
    controller.state = State.WAITING
    launches = []
    monkeypatch.setattr(controller, "launch_view", lambda: launches.append(True))

    controller.retry.timeout.emit()

    assert controller.state == State.FAILED
    assert not controller.active and not controller.jobs and not launches
    with LeasePool(tmp_path / "leases").hold(identity.serial):
        pass
    assert controller.session_lease is None and controller.session_lease_fd is None
    assert not controller.leases.held
