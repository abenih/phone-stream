import os
import shutil
import subprocess
import sys
import threading
import time

import pytest

from s10_console.models import Cancelled, ConsoleError
from s10_console.processes import Runner


def run(script, cancel, **kwargs):
    return Runner().run(
        [sys.executable, "-c", script], label="Synthetic process", cancel=cancel, **kwargs
    )


def test_real_process_stdout_stderr_exit(cancel):
    result = run("import sys;print('ok');print('err',file=sys.stderr);sys.exit(3)", cancel)
    assert (result.code, result.stdout.strip(), result.stderr.strip()) == (3, "ok", "err")


def test_missing_process(cancel):
    with pytest.raises(ConsoleError, match="could not start"):
        Runner().run(["/nonexistent/s10-test-executable"], label="Test", cancel=cancel)


def test_timeout_and_output_ceiling(cancel):
    with pytest.raises(ConsoleError, match="timed out"):
        run("import time;time.sleep(5)", cancel, timeout=0.1)
    with pytest.raises(ConsoleError, match="too much"):
        run("print('x'*300000)", cancel)


def test_pairing_fragmented_prompt_stdin_not_argv_or_output(cancel):
    notices = []
    result = run(
        "import sys,time;print('Enter pair',end='',flush=True);time.sleep(.05);"
        "print('ing code: ',end='',flush=True);code=input();"
        "assert len(code)==6 and sys.argv==['-c'];print(code);"
        "print('Successfully paired to synthetic')",
        cancel,
        secret="123456",
        output=notices.append,
    )
    assert result.code == 0
    assert "Successfully paired" in result.stdout
    assert "123456" not in result.stdout + result.stderr
    assert notices == []


def test_pairing_unrecognized_prompt_never_receives_code(cancel):
    with pytest.raises(ConsoleError, match="timed out"):
        run("print('Enter password:',flush=True);input()", cancel, secret="123456", timeout=0.15)


def test_failed_pair_code_is_not_returned(cancel):
    result = run(
        "print('Enter pairing code:',flush=True);print(input());print('Failed')",
        cancel,
        secret="123456",
    )
    assert "123456" not in result.stdout
    assert not result.stdout


def test_cancellation_only_owned_process_group(cancel, tmp_path):
    foreign = subprocess.Popen([sys.executable, "-c", "import time;time.sleep(20)"])
    marker = tmp_path / "owned-pid"
    timer = threading.Timer(0.4, cancel.set)
    timer.start()
    try:
        with pytest.raises(Cancelled):
            run(
                "import os,time,pathlib;pathlib.Path("
                + repr(str(marker))
                + ").write_text(str(os.getpid()));time.sleep(20)",
                cancel,
            )
        pid = int(marker.read_text())
        with pytest.raises(ProcessLookupError):
            os.kill(pid, 0)
        assert foreign.poll() is None
    finally:
        timer.cancel()
        foreign.terminate()
        foreign.wait()


def test_force_kill_hung_owned_child_reports_unverified_cleanup(cancel):
    notices = []
    timer = threading.Timer(0.2, cancel.set)
    timer.start()
    began = time.monotonic()
    try:
        with pytest.raises(Cancelled):
            run(
                "import signal,time;signal.signal(signal.SIGINT,signal.SIG_IGN);time.sleep(20)",
                cancel,
                output=notices.append,
            )
        assert any("cleanup is unverified" in item for item in notices)
        assert time.monotonic() - began < 5
    finally:
        timer.cancel()


def test_native_stdout_telemetry_arrives_before_child_exit(cancel):
    stdbuf = shutil.which("stdbuf")
    assert stdbuf, "Arch/Linux coreutils stdbuf is required"
    chunks = []

    def received(chunk):
        chunks.append(chunk)
        if "Texture:" in "".join(chunks):
            cancel.set()

    script = (
        "import ctypes,time;ctypes.CDLL(None).printf(b'INFO: Texture: 720x1600\\n');time.sleep(10)"
    )
    with pytest.raises(Cancelled):
        Runner().run(
            [stdbuf, "-oL", "-eL", sys.executable, "-c", script],
            label="Synthetic native logger",
            cancel=cancel,
            timeout=2,
            output=received,
        )
    assert "INFO: Texture: 720x1600" in "".join(chunks)


def test_split_stdout_and_stderr_log_lines_are_not_interleaved(cancel):
    chunks = []
    run(
        "import os,time;"
        "os.write(2,b'WA');time.sleep(.03);"
        "os.write(1,b'INFO: Texture: 720x1600\\n');time.sleep(.03);"
        "os.write(2,b'RN: Could not set window icon\\n');"
        "os.write(2,b'ERROR: final unterminated error')",
        cancel,
        output=chunks.append,
    )
    assert "INFO: Texture: 720x1600\n" in chunks
    assert "WARN: Could not set window icon\n" in chunks
    assert "ERROR: final unterminated error\n" in chunks
