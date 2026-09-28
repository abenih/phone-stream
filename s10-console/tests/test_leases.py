import os
import subprocess
import sys
import threading
from pathlib import Path

import pytest

from s10_console.leases import LeasePool, usb_candidates
from s10_console.models import ConsoleError

SERIAL = "SYNTHETIC001"

PROBE = """
import sys
from pathlib import Path
from s10_console.leases import LeasePool
from s10_console.models import ConsoleError
try:
    lease = LeasePool(Path(sys.argv[1]))
    lease.acquire(sys.argv[2])
except ConsoleError:
    sys.exit(73)
else:
    lease.release(sys.argv[2])
"""


def probe(directory):
    return subprocess.run(
        [sys.executable, "-c", PROBE, str(directory), SERIAL], capture_output=True, timeout=5
    ).returncode


def test_real_cross_process_exclusion_nested_reuse_and_no_unlink(tmp_path):
    directory = tmp_path / "leases"
    pool = LeasePool(directory)
    with pool.hold(SERIAL) as first:
        inode = (directory / f"{SERIAL}.lock").stat().st_ino
        assert probe(directory) == 73
        with pool.hold(SERIAL) as second:
            assert second == first
            assert probe(directory) == 73
        assert probe(directory) == 73
    assert probe(directory) == 0
    assert (directory / f"{SERIAL}.lock").stat().st_ino == inode


def test_crashed_owner_releases_os_lock_without_pid_authority(tmp_path):
    directory = tmp_path / "leases"
    holder = subprocess.Popen(
        [
            sys.executable,
            "-c",
            "import sys,time;from pathlib import Path;"
            "from s10_console.leases import LeasePool;"
            "pool=LeasePool(Path(sys.argv[1]));pool.acquire(sys.argv[2]);"
            "print('held',flush=True);time.sleep(60)",
            str(directory),
            SERIAL,
        ],
        stdout=subprocess.PIPE,
        text=True,
    )
    try:
        assert holder.stdout.readline().strip() == "held"
        assert probe(directory) == 73
        holder.kill()
        holder.wait(timeout=5)
        assert probe(directory) == 0
    finally:
        if holder.poll() is None:
            holder.kill()
            holder.wait()
        holder.stdout.close()


def test_inherited_viewer_fd_keeps_lock_until_viewer_exits(tmp_path):
    directory = tmp_path / "leases"
    pool = LeasePool(directory)
    fd = pool.acquire(SERIAL)
    child = subprocess.Popen([sys.executable, "-c", "import time;time.sleep(60)"], pass_fds=(fd,))
    try:
        pool.release(SERIAL)
        assert probe(directory) == 73
        child.terminate()
        child.wait(timeout=5)
        assert probe(directory) == 0
    finally:
        if child.poll() is None:
            child.kill()
            child.wait()


def test_busy_operation_never_runs_device_callback(tmp_path):
    directory = tmp_path / "leases"
    first, second = LeasePool(directory), LeasePool(directory)
    with first.hold(SERIAL):
        called = []
        with pytest.raises(ConsoleError, match="Phone busy"):
            second.run(SERIAL, lambda _: called.append(True), threading.Event())
        assert not called


def test_unsafe_lease_inode_not_replaced(tmp_path):
    directory = tmp_path / "leases"
    directory.mkdir(mode=0o700)
    foreign = tmp_path / "foreign"
    foreign.write_text("preserve")
    path = directory / f"{SERIAL}.lock"
    path.symlink_to(foreign)
    with pytest.raises(ConsoleError):
        LeasePool(directory).acquire(SERIAL)
    assert path.is_symlink()
    assert foreign.read_text() == "preserve"


def test_sysfs_candidates_require_no_adb_or_phone_operation(tmp_path):
    for name, vendor, serial in (
        ("1-1", "04e8", SERIAL),
        ("1-2", "1234", "NOTSAMSUNG"),
    ):
        entry = tmp_path / name
        entry.mkdir()
        (entry / "idVendor").write_text(vendor)
        (entry / "serial").write_text(serial)
    found = usb_candidates(tmp_path)
    assert len(found) == 1 and found[0].endpoint == SERIAL
    assert found[0].state == "usb metadata" and found[0].eligible
    assert found[0].transport_id == 0


def test_shared_default_path_is_not_worktree_scoped():
    assert LeasePool().directory == Path.home() / ".local/state/phone-device-leases"


def test_lease_reference_reuse_across_threads(tmp_path):
    pool = LeasePool(tmp_path / "leases")
    fd = pool.acquire(SERIAL)
    received = []

    def nested():
        with pool.hold(SERIAL) as nested_fd:
            received.append(nested_fd)

    thread = threading.Thread(target=nested)
    thread.start()
    thread.join(timeout=5)
    assert received == [fd]
    assert pool.held[SERIAL].references == 1
    pool.release(SERIAL)
    with pytest.raises(OSError):
        os.fstat(fd)
