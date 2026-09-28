from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PySide6.QtCore import QLockFile, QTimer
from PySide6.QtWidgets import QApplication, QMessageBox

from . import __version__
from .controller import Controller
from .models import ConsoleError
from .store import Store, private
from .ui import Window, theme


def main() -> int:
    parser = argparse.ArgumentParser(description="User-started, USB-first Galaxy S10 control")
    parser.add_argument("--version", action="version", version=f"S10 Console {__version__}")
    parser.add_argument("--config-dir", type=Path, help="Private configuration directory")
    parser.add_argument(
        "--smoke-test",
        action="store_true",
        help="Open idle UI and exit after 1 second; no ADB or phone operations",
    )
    args = parser.parse_args()
    app = QApplication(sys.argv[:1])
    app.setApplicationName("S10 Console")
    app.setOrganizationName("S10Console")
    theme(app)
    try:
        store = Store(args.config_dir)
        settings = store.load()
        store.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        private(store.directory, True)
        lock = QLockFile(str(store.directory / "console.lock"))
        if not lock.tryLock(0):
            raise ConsoleError(
                "Another S10 Console instance owns this configuration. "
                "Close it before starting another controller."
            )
        controller = Controller(settings, store)
        window = Window(controller)
    except (ConsoleError, OSError) as exc:
        QMessageBox.critical(None, "S10 Console cannot start", str(exc))
        return 1
    window.show()
    if args.smoke_test:
        QTimer.singleShot(1000, window.close)
    result = app.exec()
    lock.unlock()
    return result
