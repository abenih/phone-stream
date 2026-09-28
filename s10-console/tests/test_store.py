import base64
import json
from dataclasses import asdict

import pytest

from s10_console.models import ConsoleError, Settings
from s10_console.store import Store, key_readiness, parse_settings


def test_private_atomic_roundtrip(tmp_path, identity):
    store = Store(tmp_path / "config")
    settings = Settings(identity=identity)
    store.save(settings)
    assert store.load() == settings
    assert store.directory.stat().st_mode & 0o777 == 0o700
    assert store.path.stat().st_mode & 0o777 == 0o600
    assert list(store.directory.iterdir()) == [store.path]


def test_missing_configuration_is_idle(tmp_path):
    settings = Store(tmp_path / "absent").load()
    assert settings.identity is None
    assert settings.acceptance == {}
    assert not settings.tls_service


@pytest.mark.parametrize(
    "mutation",
    [
        {"schema": 2},
        {"schema": True},
        {"profiles": []},
        {"name": ""},
        {"identity": {}},
        {"favorites": ["com.example/.Main;id"]},
        {"tls_service": "192.168.1.1:5555"},
        {"acceptance": {"fake guarantee": "passed"}},
        {"unexpected": "field"},
    ],
)
def test_invalid_schema_never_defaults(mutation):
    raw = {"schema": 1, **asdict(Settings())}
    raw.update(mutation)
    with pytest.raises(ConsoleError):
        parse_settings(raw)


def test_corruption_preserved(tmp_path):
    store = Store(tmp_path / "config")
    store.directory.mkdir(mode=0o700)
    store.path.write_text("{broken")
    store.path.chmod(0o600)
    with pytest.raises(ConsoleError):
        store.load()
    assert store.path.read_text() == "{broken"


def test_unsafe_file_and_symlink_refused(tmp_path):
    store = Store(tmp_path / "config")
    store.save(Settings())
    store.path.chmod(0o644)
    with pytest.raises(ConsoleError, match="Unsafe"):
        store.load()
    store.path.unlink()
    target = tmp_path / "target"
    target.write_text("preserve")
    store.path.symlink_to(target)
    with pytest.raises(ConsoleError, match="Unsafe"):
        store.save(Settings())
    assert target.read_text() == "preserve"


def test_failed_replace_keeps_old_configuration(tmp_path, monkeypatch):
    store = Store(tmp_path / "config")
    store.save(Settings())
    previous = store.path.read_bytes()

    def fail(*args):
        raise OSError("disk failure")

    monkeypatch.setattr("os.replace", fail)
    with pytest.raises(ConsoleError, match="save"):
        store.save(Settings(name="Changed"))
    assert store.path.read_bytes() == previous
    assert not list(store.directory.glob(".settings-*"))


def test_key_readiness_never_reads_private_key(tmp_path, monkeypatch):
    root = tmp_path / "android"
    root.mkdir(mode=0o700)
    key = root / "adbkey"
    key.write_text("PRIVATE SECRET")
    key.chmod(0o600)
    (root / "adbkey.pub").write_bytes(base64.b64encode(b"synthetic public key") + b" user@host")
    original = type(key).read_bytes

    def read(path):
        assert path != key
        return original(path)

    monkeypatch.setattr(type(key), "read_bytes", read)
    result = key_readiness(root)
    assert "SHA-256" in result
    assert "PRIVATE SECRET" not in result


def test_no_session_secrets_serial_in_source_not_required(tmp_path, identity):
    store = Store(tmp_path / "config")
    store.save(Settings(identity=identity))
    document = json.loads(store.path.read_text())
    assert "serial" in document["identity"]
    assert not {"pin", "pairing_code", "clipboard", "active"} & document.keys()
