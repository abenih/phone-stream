from dataclasses import replace

import pytest

from s10_console.adb import devices
from s10_console.models import ConsoleError, MissingDevice, Profile
from s10_console.processes import Result
from s10_console.scrcpy import Scrcpy, SessionOptions

LISTING = (
    "List of devices attached\n"
    "SYNTHETIC001 device usb:1-2 model:SM_G973F device:beyond1 transport_id:7\n"
    "SECOND001 device usb:1-5 model:SM_G973F transport_id:10\n"
    "OTHER001 device usb:1-3 model:SM_A065F transport_id:8\n"
    "LOCKED001 unauthorized usb:1-4 transport_id:9\n"
    "OFFLINE001 offline transport_id:12\n"
    "BOOT001 recovery usb:1-8 transport_id:13\n"
    "NOPERM001 no permissions (udev rules) usb:1-9 transport_id:14\n"
    "192.168.1.20:39001 device model:SM_G973F transport_id:15\n"
)
PROPS = "SYNTHETIC001\nSM-G973F\nsamsung\nbeyond1\n31\n"


def test_enumeration_preserves_all_states_without_adopting():
    found = devices(LISTING)
    assert [item.state for item in found] == [
        "device",
        "device",
        "device",
        "unauthorized",
        "offline",
        "recovery",
        "no permissions",
        "device",
    ]
    assert sum(item.eligible for item in found) == 2
    assert not found[-1].usb


def test_exact_device_no_other_phone_probes(make_adb, identity, cancel):
    adb, runner = make_adb([LISTING, LISTING, PROPS])
    selected = adb.resolve(identity, Profile("USB"), "", cancel)
    assert selected.transport_id == 7
    assert all(call[5:7] == ["devices", "-l"] or call[5:7] == ["-t", "7"] for call in runner.calls)


def test_replug_uses_new_transport_not_saved_id(make_adb, identity, cancel):
    listing = LISTING.replace("transport_id:7", "transport_id:55").replace("usb:1-2", "usb:9-8")
    adb, runner = make_adb([listing, listing, PROPS])
    assert adb.resolve(identity, Profile("USB"), "", cancel).transport_id == 55
    assert runner.calls[-1][5:7] == ["-t", "55"]


@pytest.mark.parametrize("state", ["offline", "unauthorized", "recovery", "sideload"])
def test_unusable_state_never_probed(make_adb, identity, cancel, state):
    listing = LISTING.replace("SYNTHETIC001 device", f"SYNTHETIC001 {state}")
    adb, runner = make_adb([listing, listing])
    with pytest.raises(ConsoleError, match=state):
        adb.resolve(identity, Profile("USB"), "", cancel)
    assert all("shell" not in call for call in runner.calls)


def test_missing_never_adopts_other_s10(make_adb, identity, cancel):
    adb, runner = make_adb([LISTING.replace("SYNTHETIC001", "ANOTHER001")])
    with pytest.raises(MissingDevice):
        adb.resolve(identity, Profile("USB"), "", cancel)
    assert len(runner.calls) == 1


def test_duplicate_serial_fails_ambiguity(make_adb, identity, cancel):
    listing = LISTING.replace("SECOND001", "SYNTHETIC001")
    adb, _ = make_adb([listing])
    with pytest.raises(ConsoleError, match="Ambiguous"):
        adb.resolve(identity, Profile("USB"), "", cancel)


@pytest.mark.parametrize(
    "props",
    [
        PROPS.replace("SYNTHETIC001", "SECOND001"),
        PROPS.replace("SM-G973F", "SM-A065F"),
        PROPS.replace("beyond1", "beyond2"),
        PROPS.replace("31", "30"),
        PROPS.replace("31", "garbage"),
        "",
    ],
)
def test_mismatched_or_incomplete_identity_refused(make_adb, device, cancel, props):
    adb, _ = make_adb([LISTING, props])
    with pytest.raises(ConsoleError):
        adb.inspect(device, cancel)


def test_transport_replaced_between_listing_and_inspection(make_adb, device, cancel):
    adb, _ = make_adb([LISTING.replace("transport_id:7", "transport_id:18")])
    with pytest.raises(MissingDevice):
        adb.inspect(device, cancel)


def test_other_phone_is_not_probed(make_adb, device, cancel):
    adb, runner = make_adb([LISTING])
    with pytest.raises(ConsoleError, match="Select the USB"):
        adb.inspect(replace(device, endpoint="OTHER001", model="SM-A065F", transport_id=8), cancel)
    assert len(runner.calls) == 1


@pytest.mark.parametrize("value", ["bad", "evil;id device", "-s device", "a device transport_id:x"])
def test_malformed_listing(value):
    with pytest.raises(ConsoleError):
        devices(value)


def test_no_permission_placeholder_serial_is_visible():
    found = devices("???????????? no permissions (udev) usb:1-1\n")
    assert found[0].state == "no permissions"
    assert not found[0].eligible


def test_missing_usb_metadata_requires_physical_devpath(make_adb, device, cancel):
    listing = LISTING.replace("usb:1-2 ", "")
    adb, runner = make_adb([listing, "usb:1-4", PROPS])
    assert adb.inspect(device, cancel).serial == device.endpoint
    assert runner.calls[1][-1] == "get-devpath"


def test_non_usb_path_not_enrolled_from_serial_heuristic(make_adb, device, cancel):
    listing = LISTING.replace("usb:1-2 ", "")
    adb, runner = make_adb([listing, "unknown"])
    with pytest.raises(ConsoleError, match="physical USB"):
        adb.inspect(device, cancel)
    assert all("shell" not in call for call in runner.calls)


@pytest.mark.parametrize("adb_state, launches", [("device", True), ("unauthorized", False)])
def test_locked_authorized_transport_is_not_confused_with_unauthorized(
    make_adb, monkeypatch, identity, cancel, adb_state, launches
):
    # A locked S10 was observed to retain ADB state "device". Fixture focus/keyguard
    # requirements must never become prerequisites for launching its normal viewer.
    listing = LISTING.replace("SYNTHETIC001 device", f"SYNTHETIC001 {adb_state}")
    outputs = [listing, listing]
    if launches:
        outputs.extend([PROPS, Result(0, "", "")])
    adb, runner = make_adb(outputs)
    backend = Scrcpy(runner, adb, "/synthetic/scrcpy")
    monkeypatch.setattr(backend, "check", lambda _: "scrcpy 4.1")
    if launches:
        backend.view(
            identity,
            "",
            SessionOptions(Profile("USB"), "S10"),
            cancel,
            lambda: None,
            lambda _: None,
        )
    else:
        with pytest.raises(ConsoleError, match="unauthorized"):
            backend.view(
                identity,
                "",
                SessionOptions(Profile("USB"), "S10"),
                cancel,
                lambda: None,
                lambda _: None,
            )
    viewer_calls = [call for call in runner.calls if "/synthetic/scrcpy" in call]
    assert bool(viewer_calls) == launches
    assert all(
        not any("keyguard" in argument or "dumpsys" in argument for argument in call)
        for call in runner.calls
    )
