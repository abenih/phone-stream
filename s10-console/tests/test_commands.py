import pytest

from s10_console.models import ConsoleError, Profile
from s10_console.processes import Result, diagnostic, environment
from s10_console.scrcpy import (
    REQUIRED,
    Scrcpy,
    SessionOptions,
    capabilities,
    command,
    viewer_message,
)


def test_safe_default_command(device):
    argv = command("/scrcpy", device, SessionOptions(Profile("USB"), "My S10"))
    for required in (
        "--serial=SYNTHETIC001",
        "--no-audio",
        "--no-clipboard-autosync",
        "--keyboard=uhid",
        "--mouse=sdk",
        "--video-codec=h264",
        "--pause-on-exit=false",
        "--shortcut-mod=lalt",
    ):
        assert required in argv
    forbidden = (
        "record",
        "tcpip",
        "kill-adb",
        "mic",
        "stay-awake",
        "turn-screen-off",
        "power-off-on-close",
        "no-cleanup",
        "select-usb",
        "select-tcpip",
    )
    assert not any(word in arg for word in forbidden for arg in argv)


def test_opt_in_audio_and_display_off(device):
    argv = command("/scrcpy", device, SessionOptions(Profile("USB"), "Phone", True, True, True))
    assert "--audio-source=output" in argv
    assert "--audio-codec=aac" in argv
    assert "--require-audio" in argv
    assert "--turn-screen-off" in argv
    assert "--no-audio" not in argv


def test_name_is_one_argument_not_shell(device):
    argv = command("/scrcpy", device, SessionOptions(Profile("USB"), 'Phone";$(touch nope)'))
    assert argv[-2].startswith("--window-title=")
    assert len([a for a in argv if "touch" in a]) == 1


@pytest.mark.parametrize("version", ["scrcpy 2.7", "scrcpy 4.0", "scrcpy 5.0", "unknown"])
def test_incompatible_version_fails_closed(version):
    with pytest.raises(ConsoleError):
        capabilities(version, " ".join(REQUIRED))


def test_missing_flag_fails_closed():
    with pytest.raises(ConsoleError, match="privacy"):
        capabilities("scrcpy 4.1", " ".join(REQUIRED[2:]))
    assert capabilities("scrcpy 4.1", " ".join(REQUIRED)) == "scrcpy 4.1"


@pytest.mark.parametrize(
    "change",
    [{"size": 100}, {"fps": 200}, {"bitrate": True}, {"keyboard": "evil"}, {"transport": "tcpip"}],
)
def test_invalid_profiles(change):
    with pytest.raises(ConsoleError):
        Profile("Test", **change)


def test_environment_cannot_select_remote_adb_or_leak_trace():
    env = environment(
        {
            "ANDROID_SERIAL": "other",
            "ADB_TRACE": "all",
            "ADB_VENDOR_KEYS": "/keys",
            "SCRCPY_SERVER_PATH": "/foreign",
            "ADB": "/bad",
            "ADB_SERVER_SOCKET": "tcp:8.8.8.8:5037",
            "DISPLAY": ":0",
        }
    )
    assert env["ADB_SERVER_SOCKET"] == "tcp:127.0.0.1:5037"
    assert env["DISPLAY"] == ":0"
    assert (
        not {"ANDROID_SERIAL", "ADB_TRACE", "ADB_VENDOR_KEYS", "SCRCPY_SERVER_PATH", "ADB"}
        & env.keys()
    )


def test_failed_backend_probe(make_adb, cancel):
    adb, runner = make_adb([Result(1, "", "missing shared library"), "help"])
    backend = Scrcpy(runner, adb, "/synthetic/scrcpy")
    with pytest.raises(ConsoleError, match="capability check"):
        backend.check(cancel)


@pytest.mark.parametrize(
    "raw",
    [
        "clipboard ERROR SECRET CLIPBOARD",
        "audio error SECRET",
        "permission denied SECRET",
        "UHID failed SECRET",
        "offline SECRET",
    ],
)
def test_diagnostics_never_echo_contents(raw):
    assert "SECRET" not in diagnostic(raw, "Test")


def test_client_protocol_mismatch_never_contacts_shared_server(make_adb, cancel, monkeypatch):
    adb, runner = make_adb(["Android Debug Bridge version 1.0.40\n"])
    adb._client_checked = False

    def forbidden():
        raise AssertionError("Must reject the client before touching the server")

    monkeypatch.setattr("s10_console.adb.server_ready", forbidden)
    with pytest.raises(ConsoleError, match="Unsupported ADB client"):
        adb.list_devices(cancel)
    assert runner.calls == [["/synthetic/adb", "version"]]


def test_wayland_icon_warning_is_not_a_capture_failure():
    message = viewer_message(
        "WARN: Could not set window icon: wayland: required xdg_toplevel_icon_v1 not supported"
    )
    assert message is not None and "window icon" in message
    assert "Viewer failed" not in message
    assert viewer_message("INFO: Renderer: opengl") is None
    assert "failed" in viewer_message("ERROR: Unknown native backend error")


def test_clipboard_warning_retains_actionable_redacted_failure():
    message = viewer_message("WARN: Could not request clipboard: SECRET")
    assert "Clipboard transfer failed" in message
    assert "SECRET" not in message


def test_viewer_uses_line_buffered_logging(make_adb, monkeypatch, identity, device, cancel):
    adb, runner = make_adb([Result(0, "", "")])
    backend = Scrcpy(runner, adb, "/synthetic/scrcpy")
    backend.line_buffer = "/usr/bin/stdbuf"
    monkeypatch.setattr(backend, "check", lambda cancel: "scrcpy 4.1")
    monkeypatch.setattr(adb, "resolve", lambda *args, **kwargs: device)
    backend.view(
        identity,
        "",
        SessionOptions(Profile("USB"), "S10"),
        cancel,
        lambda: None,
        lambda output: None,
        lease_fd=123,
    )
    assert runner.calls[0][:4] == ["/usr/bin/stdbuf", "-oL", "-eL", "/synthetic/scrcpy"]
    assert runner.options[0]["pass_fds"] == (123,)
