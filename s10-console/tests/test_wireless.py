import struct

import pytest

from s10_console.adb import (
    lan_endpoint,
    server_ready,
    service_name,
    services,
    tls_endpoint_ready,
)
from s10_console.models import ConsoleError, MissingDevice, Profile
from s10_console.processes import Result

SERVICE = "adb-SYNTHETIC001-token._adb-tls-connect._tcp"
MDNS = "List of discovered mdns services\n" + (
    "adb-SYNTHETIC001-token _adb-tls-connect._tcp 192.168.1.20:37001\n"
    "legacy _adb._tcp 192.168.1.21:5555\n"
    "pair _adb-tls-pairing._tcp 192.168.1.20:37002\n"
)
LISTING = f"{SERVICE} device model:SM_G973F transport_id:20\n"
PROPS = "SYNTHETIC001\nSM-G973F\nsamsung\nbeyond1\n31\n"


class Socket:
    def __init__(self, data):
        self.data = bytearray(data)
        self.sent = []

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def sendall(self, data):
        self.sent.append(data)

    def settimeout(self, timeout):
        assert timeout > 0

    def recv(self, count):
        result = bytes(self.data[: min(count, 2)])
        del self.data[: len(result)]
        return result


def socket_fixture(monkeypatch, data):
    sock = Socket(data)
    monkeypatch.setattr("socket.create_connection", lambda *a, **k: sock)
    return sock


@pytest.mark.parametrize(
    "endpoint",
    [
        "8.8.8.8:1234",
        "127.0.0.1:5555",
        "192.168.1.1:5555",
        "192.168.1.1:0",
        "192.168.1.1:65536",
        "localhost:1234",
        "192.168.1.1:1234;id",
        "file:/etc/passwd",
    ],
)
def test_bad_endpoints_refused(endpoint):
    with pytest.raises(ConsoleError):
        lan_endpoint(endpoint)


def test_only_explicit_tls_services_accepted():
    found = services(MDNS)
    assert [(s.name, s.endpoint) for s in found] == [(SERVICE, "192.168.1.20:37001")]
    with pytest.raises(ConsoleError):
        service_name("192.168.1.20:37001")


def test_tls_probe_requires_stls_without_authentication(monkeypatch):
    command = int.from_bytes(b"STLS", "little")
    header = struct.pack("<6I", command, 0x01000000, 0, 0, 0, command ^ 0xFFFFFFFF)
    sock = socket_fixture(monkeypatch, header)
    tls_endpoint_ready("192.168.1.20:37001")
    assert sock.sent[0][:4] == b"CNXN"
    assert len(sock.sent) == 1
    assert b"AUTH" not in sock.sent[0]


@pytest.mark.parametrize("command", [b"AUTH", b"CNXN"])
def test_plaintext_service_cannot_pass_tls_probe(monkeypatch, command):
    number = int.from_bytes(command, "little")
    socket_fixture(monkeypatch, struct.pack("<6I", number, 1, 0, 0, 0, number ^ 0xFFFFFFFF))
    with pytest.raises(ConsoleError, match="Plaintext"):
        tls_endpoint_ready("192.168.1.20:37001")


def test_nonmutating_server_probe_compatible(monkeypatch):
    sock = socket_fixture(monkeypatch, b"OKAY00040029")
    assert server_ready()
    assert sock.sent == [b"000chost:version"]


def test_incompatible_server_never_killed(monkeypatch):
    sock = socket_fixture(monkeypatch, b"OKAY00040028")
    with pytest.raises(ConsoleError, match="Incompatible"):
        server_ready()
    assert b"kill" not in b"".join(sock.sent)


def test_missing_server_is_explicit(monkeypatch):
    def refused(*a, **k):
        raise ConnectionRefusedError()

    monkeypatch.setattr("socket.create_connection", refused)
    assert server_ready() is False


def test_wifi_identity_verified_before_use(make_adb, monkeypatch, identity, cancel):
    monkeypatch.setattr("s10_console.adb.tls_endpoint_ready", lambda endpoint: None)
    adb, _ = make_adb([MDNS, LISTING, LISTING, PROPS])
    assert adb.resolve(identity, Profile("WiFi", "wifi"), SERVICE, cancel).transport_id == 20


def test_reused_service_for_other_phone_rejected(make_adb, monkeypatch, identity, cancel):
    monkeypatch.setattr("s10_console.adb.tls_endpoint_ready", lambda endpoint: None)
    adb, _ = make_adb([MDNS, LISTING, LISTING, PROPS.replace("SYNTHETIC001", "FOREIGN")])
    with pytest.raises(ConsoleError, match="identity changed"):
        adb.resolve(identity, Profile("WiFi", "wifi"), SERVICE, cancel)


def test_lost_service_no_legacy_ip_fallback(make_adb, identity, cancel):
    adb, runner = make_adb(["legacy _adb._tcp 192.168.1.20:5555\n"])
    with pytest.raises(MissingDevice, match="TLS endpoint"):
        adb.resolve(identity, Profile("WiFi", "wifi"), SERVICE, cancel)
    assert all("connect" not in call for call in runner.calls)


def test_connect_error_zero_exit_is_not_success(make_adb, monkeypatch, identity, cancel):
    monkeypatch.setattr("s10_console.adb.tls_endpoint_ready", lambda endpoint: None)
    adb, _ = make_adb([MDNS, "failed to connect to service"])
    with pytest.raises(ConsoleError, match="did not connect"):
        adb.bind(SERVICE, identity, cancel)


def test_pair_code_passed_as_stdin_secret_not_argv(make_adb, cancel):
    adb, runner = make_adb(["Successfully paired to selected endpoint"])
    assert "Pairing accepted" in adb.pair("192.168.1.20:37002", "123456", cancel)
    assert runner.calls[0][-2:] == ["pair", "192.168.1.20:37002"]
    assert "123456" not in str(runner.calls)
    assert runner.options[0]["secret"] == "123456"


def test_failed_pairing_is_not_reported_as_connected(make_adb, cancel):
    adb, _ = make_adb([Result(0, "Failed: wrong code", "")])
    with pytest.raises(ConsoleError, match="did not succeed"):
        adb.pair("192.168.1.20:37002", "123456", cancel)


def test_bound_service_reconnected_only_for_explicit_view_start(
    make_adb,
    monkeypatch,
    identity,
    cancel,
):
    monkeypatch.setattr("s10_console.adb.tls_endpoint_ready", lambda endpoint: None)
    adb, runner = make_adb([MDNS, "", f"connected to {SERVICE}", LISTING, LISTING, PROPS])
    result = adb.resolve(identity, Profile("WiFi", "wifi"), SERVICE, cancel, connect_missing=True)
    assert result.endpoint == SERVICE
    assert runner.calls[2][-2:] == ["connect", SERVICE]


def test_ordinary_action_does_not_reconnect_a_missing_transport(
    make_adb,
    monkeypatch,
    identity,
    cancel,
):
    monkeypatch.setattr("s10_console.adb.tls_endpoint_ready", lambda endpoint: None)
    adb, runner = make_adb([MDNS, ""])
    with pytest.raises(MissingDevice):
        adb.resolve(identity, Profile("WiFi", "wifi"), SERVICE, cancel)
    assert all("connect" not in call for call in runner.calls)
