import shlex
from pathlib import Path

import pytest

from s10_console.adb import component, remote_file
from s10_console.models import ConsoleError
from s10_console.processes import Result


@pytest.mark.parametrize(
    "path",
    [
        "/data/data/private",
        "/sdcard/../data/secret",
        "/sdcard/Android/data/private",
        "/sdcard//Download/a",
        "/sdcard/Download/",
        "/sdcard/./secret",
        "/sdcard/.android_secure/a",
        "/sdcard/a\ninput keyevent 3",
    ],
)
def test_restricted_or_unsafe_paths(path):
    with pytest.raises(ConsoleError):
        remote_file(path)


@pytest.mark.parametrize("value", ["-n", "com.example/.Main;id", "$(id)/.Main", "com.a/Main\nx"])
def test_components_are_not_shell_commands(value):
    with pytest.raises(ConsoleError):
        component(value)


def test_remote_shell_quoting_is_explicit(make_adb, device, cancel):
    adb, runner = make_adb([""])
    args = ["printf", "%s", "a';touch /not-run;$(id)"]
    adb.shell(device, args, cancel)
    assert shlex.split(runner.calls[0][-1]) == args
    assert runner.calls[0][5:8] == ["-t", "7", "shell"]


def test_android_zero_exit_launch_error_is_failure(make_adb, device, cancel):
    adb, _ = make_adb(["Error type 3\nError: Activity class does not exist"])
    with pytest.raises(ConsoleError):
        adb.shell(device, ["am", "start", "-n", "com.example/.Main"], cancel)


def test_launchable_apps_strictly_parsed(make_adb, device, cancel):
    adb, _ = make_adb(["com.example/.Main\ncom.example/.Main\ncom.browser/Activity\n"])
    assert adb.apps(device, cancel) == ["com.browser/Activity", "com.example/.Main"]


def test_push_no_overwrite_without_confirmation(make_adb, device, cancel, tmp_path):
    local = tmp_path / "input"
    local.write_text("sample")
    adb, runner = make_adb(["/storage/emulated/0", "/storage/emulated/0/Download", "file"])
    with pytest.raises(ConsoleError, match="Confirm replacement"):
        adb.transfer(device, "push", local, "/sdcard/Download/sample", cancel, False)
    assert all("push" not in call for call in runner.calls)


def test_push_spaces_unicode_metacharacters_are_safe_arguments(make_adb, device, cancel, tmp_path):
    local = tmp_path / "sample - input.txt"
    local.write_text("sample")
    remote = "/sdcard/Download/a ; $(id) ' \u00e9.txt"
    adb, runner = make_adb(["/storage/emulated/0", "/storage/emulated/0/Download", "missing", ""])
    adb.transfer(device, "push", local, remote, cancel, False)
    assert runner.calls[-1][-3:] == ["push", str(local), remote]
    assert shlex.quote(remote) in runner.calls[-2][-1]


@pytest.mark.parametrize(
    "parent,status",
    [
        ("/data/private", "file"),
        ("/storage/emulated/0/Android/data", "file"),
        ("/storage/emulated/0/Download", "link"),
        ("/storage/emulated/0/Download", "other"),
    ],
)
def test_resolved_private_or_symlink_refused(make_adb, device, cancel, tmp_path, parent, status):
    adb, runner = make_adb(["/storage/emulated/0", parent, status])
    with pytest.raises(ConsoleError):
        adb.transfer(device, "pull", tmp_path / "out", "/sdcard/Download/a", cancel, False)
    assert all("pull" not in call for call in runner.calls)


def test_failed_pull_preserves_existing_and_removes_partial(make_adb, device, cancel, tmp_path):
    destination = tmp_path / "existing"
    destination.write_text("preserve")
    adb, _ = make_adb(
        [
            "/storage/emulated/0",
            "/storage/emulated/0/Download",
            "file",
            Result(1, "", "permission denied"),
        ]
    )
    with pytest.raises(ConsoleError):
        adb.transfer(device, "pull", destination, "/sdcard/Download/a", cancel, True)
    assert destination.read_text() == "preserve"
    assert not list(tmp_path.glob(".s10-download-*"))


def test_successful_pull_publishes_private_file(make_adb, device, cancel, tmp_path):
    adb, runner = make_adb(["/storage/emulated/0", "/storage/emulated/0/Download", "file", ""])
    original = runner.run

    def run(argv, **kwargs):
        if "pull" in argv:
            Path(argv[-1]).write_text("transferred")
        return original(argv, **kwargs)

    runner.run = run
    destination = tmp_path / "result"
    adb.transfer(device, "pull", destination, "/sdcard/Download/a", cancel, False)
    assert destination.read_text() == "transferred"
    assert destination.stat().st_mode & 0o777 == 0o600


def test_failed_push_warns_about_partial_remote_file(make_adb, device, cancel, tmp_path):
    local = tmp_path / "input"
    local.write_text("sample")
    adb, _ = make_adb(
        [
            "/storage/emulated/0",
            "/storage/emulated/0/Download",
            "missing",
            Result(1, "", "no space"),
        ]
    )
    with pytest.raises(ConsoleError, match="partial remote file"):
        adb.transfer(device, "push", local, "/sdcard/Download/a", cancel, False)
