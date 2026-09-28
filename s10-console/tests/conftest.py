import os
import socket
import threading

import pytest

os.environ["QT_QPA_PLATFORM"] = "offscreen"

from s10_console.adb import Adb
from s10_console.models import Device, Identity
from s10_console.processes import Result, Runner


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    def forbidden(*args, **kwargs):
        raise AssertionError("Unit tests must not connect to ADB, a phone, or the network")

    monkeypatch.setattr(socket, "create_connection", forbidden)


@pytest.fixture
def identity():
    return Identity("SYNTHETIC001", "SM-G973F", "samsung", "beyond1", 31)


@pytest.fixture
def device():
    return Device("SYNTHETIC001", "device", "SM-G973F", 7, True)


@pytest.fixture
def cancel():
    return threading.Event()


class FakeRunner(Runner):
    def __init__(self, outputs):
        self.outputs = list(outputs)
        self.calls = []
        self.options = []

    def run(self, argv, **kwargs):
        self.calls.append(argv)
        self.options.append(kwargs)
        if kwargs["cancel"].is_set():
            from s10_console.models import Cancelled

            raise Cancelled("Cancelled")
        if not self.outputs:
            raise AssertionError(f"Unexpected synthetic command: {argv}")
        result = self.outputs.pop(0)
        if isinstance(result, Exception):
            raise result
        return result


@pytest.fixture
def make_adb(monkeypatch):
    monkeypatch.setattr("s10_console.adb.server_ready", lambda: True)

    def make(outputs):
        runner = FakeRunner(
            [Result(0, value, "") if isinstance(value, str) else value for value in outputs]
        )
        adb = Adb(runner, "/synthetic/adb")
        adb._client_checked = True
        return adb, runner

    return make
