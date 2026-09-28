from __future__ import annotations

from collections.abc import Callable
from functools import partial
from importlib.resources import files
from pathlib import Path

from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import (
    QAction,
    QCloseEvent,
    QColor,
    QIcon,
    QKeySequence,
    QPalette,
)
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QFileDialog,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMessageBox,
    QProgressBar,
    QPushButton,
    QScrollArea,
    QSpinBox,
    QTabWidget,
    QTextBrowser,
    QTreeWidget,
    QTreeWidgetItem,
    QVBoxLayout,
    QWidget,
)

from .adb import NAVIGATION, component
from .controller import Controller
from .models import ACCEPTANCE, ConsoleError, Device, Profile, State
from .scrcpy import SessionOptions

SHORTCUTS = """<b>In the external viewer (left Alt is the modifier)</b><br>
Click / drag / scroll: touch input; hold click: long press<br>
Right click: Back &nbsp; Middle click: Home &nbsp; Alt+S: Recents<br>
Alt+F or F11: fullscreen &nbsp; Alt+W: fit &nbsp; Alt+G: 1:1 pixels<br>
Alt+Left/Right: rotate view &nbsp; Alt+R: rotate phone<br>
Ctrl+click-and-drag: pinch/rotate app content<br>
Alt+O: physical display off &nbsp; Alt+Shift+O: display on<br>
Alt+P: power key &nbsp; Alt+K: physical keyboard layout<br>
<b>Alt+C: explicitly copy from phone &nbsp; Alt+V: explicitly paste to phone</b><br>
Ordinary Ctrl+V only pastes the phone's clipboard; automatic sync is disabled.<br>
Alt+Q: close viewer and stop reconnect. Ctrl+Shift+X stops from this console."""

PREPARATION = """<b>Prepare while the screen works</b><br>
1. Enable Developer options and USB debugging yourself. Connect a data cable,
unlock normally, and approve this PC's RSA prompt. Select persistent trust only
after reviewing the security implications.<br>
2. Verify and enroll the USB S10 below. Protect this PC and its ADB key.<br>
3. Start control. Test mouse, keyboard, navigation, and Alt+K keyboard layout.
Use SDK keyboard compatibility if UHID fails.<br>
4. Start the reconnect exercise, hide the physical display, and unplug/replug
USB twice without touching the phone. Wait for each disappearance to be observed.
Confirm the actual live image and input yourself, not just process startup.<br>
5. Rehearse app restart, phone reboot and normal known-PIN entry, and PC restart
only when you can recover. The app never reboots either device.<br>
6. Review authorization expiry/revocation on the phone. Do not disable the screen
lock. Optional wireless pairing and HDMI/DeX should also be rehearsed now."""

LIMITS = """<b>Not an unlock bypass or a guarantee of total access.</b> If this PC was
not already trusted, software cannot approve the RSA prompt invisibly.
Factory reset, revoked/lost keys, credential-encrypted storage before first unlock,
secure app content, failed USB hardware, or OS policy may block use. Enter your
known PIN yourself only where normal OS input permits it. Do not paste secrets.<br><br>
<b>Fallback:</b> the S10 supports USB-C HDMI / Samsung DeX to an external monitor
with a compatible powered hub and keyboard/mouse. Rehearse first-use Start/Continue
prompts and normal unlock before screen failure. This is not DeX-for-PC on Linux.
Most PC HDMI ports are outputs, not capture inputs. OTG input alone gives no image
or ADB authorization. If authorization and external display both fail, seek
owner-authorized display/USB repair; do not reset the phone to recover data."""


def theme(app: QApplication) -> None:
    app.setStyle("Fusion")
    dark = app.styleHints().colorScheme() == Qt.ColorScheme.Dark
    bg, surface, fg, muted, border, accent, selected = (
        ("#101820", "#1a2632", "#f0f5fa", "#b4c5d6", "#415568", "#78dcca", "#082c28")
        if dark
        else ("#f1f5f8", "#ffffff", "#182c3c", "#465e72", "#b6c7d5", "#006b5d", "#ffffff")
    )
    palette = QPalette()
    for role, color in (
        (QPalette.ColorRole.Window, bg),
        (QPalette.ColorRole.Base, surface),
        (QPalette.ColorRole.AlternateBase, bg),
        (QPalette.ColorRole.Button, surface),
        (QPalette.ColorRole.WindowText, fg),
        (QPalette.ColorRole.Text, fg),
        (QPalette.ColorRole.ButtonText, fg),
        (QPalette.ColorRole.Highlight, accent),
        (QPalette.ColorRole.HighlightedText, selected),
        (QPalette.ColorRole.ToolTipBase, surface),
        (QPalette.ColorRole.ToolTipText, fg),
        (QPalette.ColorRole.PlaceholderText, muted),
    ):
        palette.setColor(role, QColor(color))
    for role in (
        QPalette.ColorRole.Text,
        QPalette.ColorRole.ButtonText,
        QPalette.ColorRole.WindowText,
    ):
        palette.setColor(QPalette.ColorGroup.Disabled, role, QColor(muted))
    app.setPalette(palette)
    app.setStyleSheet(f"""
        QWidget {{ font-size: 13px; }}
        QLabel#title {{ font-size: 28px; font-weight: 700; }}
        QLabel#subtitle {{ color: {muted}; }}
        QLabel#status {{ padding: 12px; border: 1px solid {border};
            border-radius: 8px; background: {surface}; }}
        QGroupBox {{ border: 1px solid {border}; border-radius: 9px; margin-top: 16px;
            padding: 16px 12px 12px; font-weight: 600; }}
        QGroupBox::title {{ subcontrol-origin: margin; left: 12px; padding: 0 5px; }}
        QPushButton, QComboBox, QLineEdit, QSpinBox {{ min-height: 28px; padding: 4px 8px; }}
        QPushButton#start:enabled {{ background: {accent}; color: {selected};
            border: 1px solid {accent}; border-radius: 6px; font-weight: 700; }}
        QPushButton:focus {{ border: 2px solid {accent}; }}
        QTabBar::tab {{ padding: 10px 24px; }}
        QCheckBox {{ padding: 4px 0; }}
        QTreeWidget {{ alternate-background-color: {bg}; }}
    """)


def label(value: str, *, rich: bool = False) -> QLabel:
    widget = QLabel(value)
    widget.setWordWrap(True)
    widget.setTextFormat(Qt.TextFormat.RichText if rich else Qt.TextFormat.PlainText)
    widget.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
    return widget


class Window(QMainWindow):
    def __init__(self, controller: Controller) -> None:
        super().__init__()
        self.controller = controller
        self.closing = False
        self.setWindowTitle("S10 Console")
        self.setWindowIcon(QIcon(str(files("s10_console").joinpath("resources/s10-console.svg"))))
        self.resize(1020, 850)
        self.setMinimumSize(740, 580)
        root = QWidget()
        self.setCentralWidget(root)
        layout = QVBoxLayout(root)
        layout.setContentsMargins(24, 18, 24, 18)
        title = label("S10 Console")
        title.setObjectName("title")
        layout.addWidget(title)
        subtitle = label("Your phone, from your PC. USB first. Always under your control.")
        subtitle.setObjectName("subtitle")
        layout.addWidget(subtitle)
        self.status = label("")
        self.status.setObjectName("status")
        layout.addWidget(self.status)
        bar = QHBoxLayout()
        self.start_button = self.button("Start control", self.start, "start")
        self.stop_button = self.button("Stop control", controller.stop, "stop")
        bar.addWidget(self.start_button)
        bar.addWidget(self.stop_button)
        self.progress = QProgressBar()
        self.progress.setRange(0, 0)
        self.progress.setTextVisible(False)
        self.progress.setMaximumWidth(160)
        bar.addWidget(self.progress)
        bar.addStretch()
        bar.addWidget(self.button("Preparation guide", partial(self.guide, "preparation")))
        bar.addWidget(self.button("Help / limits", partial(self.guide, "troubleshooting")))
        layout.addLayout(bar)
        self.tabs = QTabWidget()
        layout.addWidget(self.tabs, 1)
        self.control_page()
        self.prepare_page()
        self.tools_page()
        stop = QAction("Stop control", self)
        stop.setShortcut(QKeySequence("Ctrl+Shift+X"))
        stop.triggered.connect(controller.stop)
        self.addAction(stop)
        quit_action = QAction("Quit", self)
        quit_action.setShortcut(QKeySequence.StandardKey.Quit)
        quit_action.triggered.connect(self.close)
        self.addAction(quit_action)
        controller.changed.connect(self.update_state)
        controller.message.connect(self.show_status)
        self.update_state()

    def guard(self, action: Callable[[], object]) -> None:
        try:
            action()
        except ConsoleError as exc:
            self.controller.report(str(exc))

    def button(self, title: str, action: Callable[[], object], name: str = "") -> QPushButton:
        widget = QPushButton(title)
        if name:
            widget.setObjectName(name)
        widget.clicked.connect(lambda: self.guard(action))
        return widget

    def page(self, title: str) -> QVBoxLayout:
        content = QWidget()
        layout = QVBoxLayout(content)
        layout.setContentsMargins(8, 8, 8, 12)
        layout.setSpacing(12)
        area = QScrollArea()
        area.setWidgetResizable(True)
        area.setFrameShape(QScrollArea.Shape.NoFrame)
        area.setWidget(content)
        self.tabs.addTab(area, title)
        return layout

    def group(self, page: QVBoxLayout, title: str) -> QVBoxLayout:
        box = QGroupBox(title)
        layout = QVBoxLayout(box)
        page.addWidget(box)
        return layout

    def control_page(self) -> None:
        page = self.page("Control")
        phone = self.group(page, "Enrolled phone")
        self.phone = label("")
        phone.addWidget(self.phone)
        self.backend = label("")
        phone.addWidget(self.backend)
        phone.addWidget(
            self.button("Check backend (no capture)", self.controller.check_dependencies)
        )
        profile = self.group(page, "Connection profile")
        row = QHBoxLayout()
        self.profile = QComboBox()
        self.profile.setObjectName("profile")
        self.profile.setAccessibleName("Connection and quality profile")
        row.addWidget(self.profile, 1)
        self.edit_profile = self.button("Edit / save profile", self.profile_dialog)
        row.addWidget(self.edit_profile)
        profile.addLayout(row)
        self.audio = QCheckBox("Forward normal phone audio (opt-in; may mute phone playback)")
        self.aac = QCheckBox("Use AAC audio compatibility")
        self.screen_off = QCheckBox("Turn physical display off while controlling (opt-in)")
        for widget in (self.audio, self.aac, self.screen_off):
            profile.addWidget(widget)
        profile.addWidget(
            label(
                "H.264 video. No recording, microphone, or automatic clipboard sync. "
                "Options apply to the next user-started session."
            )
        )
        navigation = self.group(page, "Phone navigation")
        row = QHBoxLayout()
        self.nav: list[QPushButton] = []
        for name in NAVIGATION:
            button = self.button(name, partial(self.controller.navigate, name))
            row.addWidget(button)
            self.nav.append(button)
        navigation.addLayout(row)
        viewer = self.group(page, "External scrcpy window")
        viewer.addWidget(
            label(
                "The live screen opens in a separate native window, not inside "
                "this console. A running process is not proof of working capture."
            )
        )
        viewer.addWidget(label(SHORTCUTS, rich=True))
        viewer.addWidget(
            label(
                "Avoid dragging files onto the external viewer: upstream scrcpy "
                "pushes dropped files and installs dropped APKs. "
                "Use this console's explicit file tools instead."
            )
        )
        page.addStretch()

    def prepare_page(self) -> None:
        page = self.page("Prepare")
        page.addWidget(label(PREPARATION, rich=True))
        device = self.group(page, "1. Verify and enroll the right USB phone")
        row = QHBoxLayout()
        self.refresh_button = self.button("Refresh devices", self.controller.refresh, "refresh")
        row.addWidget(self.refresh_button)
        row.addWidget(self.button("Start local ADB", self.confirm_server))
        row.addStretch()
        device.addLayout(row)
        self.device_box = QComboBox()
        self.device_box.setObjectName("devices")
        self.device_box.setAccessibleName("Explicit USB phone selection")
        self.device_box.activated.connect(self.selection_changed)
        device.addWidget(self.device_box)
        self.verify_button = self.button("Verify selected USB S10", self.verify, "verify")
        device.addWidget(self.verify_button)
        self.identity = label("No phone identity verified.")
        device.addWidget(self.identity)
        row = QHBoxLayout()
        self.name = QLineEdit(self.controller.settings.name)
        self.name.setMaxLength(60)
        self.name.setAccessibleName("Friendly phone name")
        row.addWidget(self.name, 1)
        self.enroll_button = self.button("Enroll this verified S10", self.enroll, "enroll")
        row.addWidget(self.enroll_button)
        device.addLayout(row)
        key = self.group(page, "2. Protect this PC's persistent ADB identity")
        key.addWidget(self.button("Check key presence / permissions", self.controller.check_key))
        self.key = label("")
        key.addWidget(self.key)
        key.addWidget(
            label(
                "A trusted ADB private key grants powerful phone access. Keep this PC "
                "and ~/.android private. No key is exported here. Optional backup is "
                "a separate owner decision: see docs/preparation.md."
            )
        )
        exercise = self.group(page, "3. Rehearse recovery without phone touch")
        self.watch_button = self.button("Start reconnect observation", self.toggle_watch)
        exercise.addWidget(self.watch_button)
        self.cycles = label("")
        exercise.addWidget(self.cycles)
        exercise.addWidget(
            label(
                "Observation checks only your enrolled S10 every 2 seconds. "
                "It does not prove that viewing or input recovered. "
                "Keep the display usable as a fallback."
            )
        )
        records = self.group(page, "4. Record actual owner-observed results")
        self.records = QTreeWidget()
        self.records.setHeaderLabels(["Scenario", "Evidence"])
        self.records.setRootIsDecorated(False)
        self.records.setMinimumHeight(230)
        self.records.setAlternatingRowColors(True)
        records.addWidget(self.records)
        row = QHBoxLayout()
        row.addWidget(self.button("Record selected: passed", lambda: self.record(True)))
        row.addWidget(self.button("Record selected: failed / blocked", lambda: self.record(False)))
        records.addLayout(row)
        page.addWidget(label(LIMITS, rich=True))
        page.addStretch()

    def tools_page(self) -> None:
        page = self.page("Tools")
        wireless = self.group(page, "Optional encrypted Wireless Debugging")
        wireless.addWidget(
            label(
                "Enroll over USB first. On your S10, explicitly enable Wireless "
                "Debugging on a trusted same-LAN network and choose Pair using "
                "pairing code. Pairing and connection ports are different. "
                "No legacy port 5555 or WAN connection is allowed."
            )
        )
        form = QFormLayout()
        self.endpoint = QLineEdit()
        self.endpoint.setPlaceholderText("192.168.1.20:37001 (pairing port)")
        self.endpoint.setMaxLength(32)
        self.code = QLineEdit()
        self.code.setEchoMode(QLineEdit.EchoMode.Password)
        self.code.setMaxLength(6)
        form.addRow("Pairing address", self.endpoint)
        form.addRow("Six-digit code", self.code)
        wireless.addLayout(form)
        self.pair_button = self.button("Pair this PC (explicit phone approval)", self.pair)
        wireless.addWidget(self.pair_button)
        wireless.addWidget(
            self.button("Discover TLS connection services", self.controller.discover)
        )
        self.service = QComboBox()
        self.service.setAccessibleName("Discovered TLS Wireless Debugging service")
        wireless.addWidget(self.service)
        self.bind_button = self.button("Connect and verify this service is my S10", self.bind)
        wireless.addWidget(self.bind_button)
        self.tls = label("")
        wireless.addWidget(self.tls)
        wireless.addWidget(
            label(
                "Stop ends this app's viewer, not shared ADB transport connections. "
                "If ports, pairing, or discovery fail, use USB. Android 12 does "
                "not have Android 17's Wi-Fi 2.0 reconnect guarantees."
            )
        )
        apps = self.group(page, "Launch an app on the controlled phone")
        self.apps_refresh = self.button(
            "Read launchable apps (on request only)", self.controller.refresh_apps
        )
        apps.addWidget(self.apps_refresh)
        self.apps_box = QComboBox()
        self.apps_box.setAccessibleName("Launchable application component")
        apps.addWidget(self.apps_box)
        row = QHBoxLayout()
        self.launch_button = self.button("Launch selected app", self.launch_app)
        self.favorite_button = self.button("Save as favorite", self.favorite)
        row.addWidget(self.launch_button)
        row.addWidget(self.favorite_button)
        apps.addLayout(row)
        files = self.group(page, "Transfer one chosen file")
        files.addWidget(
            label(
                "Only accessible shared storage under /sdcard/. No private app data, "
                "Android/ subtree, directory sync, or APK installation. Pulls use "
                "a private temporary file; interrupted pushes can leave a partial "
                "remote file. Protect the chosen local destination."
            )
        )
        form = QFormLayout()
        self.remote = QLineEdit("/sdcard/Download/")
        self.remote.setMaxLength(1024)
        form.addRow("Full phone file path", self.remote)
        files.addLayout(form)
        row = QHBoxLayout()
        self.push_button = self.button("Choose local file and send...", self.push)
        self.pull_button = self.button("Receive file to...", self.pull)
        row.addWidget(self.push_button)
        row.addWidget(self.pull_button)
        files.addLayout(row)
        self.cancel_button = self.button(
            "Cancel pending tool operation", self.controller.cancel_tools
        )
        page.addWidget(self.cancel_button)
        page.addStretch()

    def confirm(self, title: str, body: str) -> bool:
        return (
            QMessageBox.question(
                self,
                title,
                body,
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                QMessageBox.StandardButton.No,
            )
            == QMessageBox.StandardButton.Yes
        )

    def guide(self, name: str) -> None:
        if name not in ("preparation", "troubleshooting", "acceptance", "third-party"):
            raise ConsoleError("Unknown guide.")
        packaged = files("s10_console").joinpath(f"resources/docs/{name}.md")
        try:
            content = (
                packaged.read_text(encoding="utf-8")
                if packaged.is_file()
                else (Path(__file__).parents[2] / "docs" / f"{name}.md").read_text(encoding="utf-8")
            )
        except OSError as exc:
            raise ConsoleError("The installed preparation guide is missing or unreadable.") from exc
        dialog = QDialog(self)
        dialog.setWindowTitle("S10 Console guide")
        dialog.resize(820, 680)
        layout = QVBoxLayout(dialog)
        browser = QTextBrowser()
        browser.setOpenExternalLinks(True)
        browser.setMarkdown(content)
        layout.addWidget(browser)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Close)
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        dialog.exec()

    def confirm_server(self) -> None:
        selected = self.device_box.currentData()
        identity = self.controller.settings.identity
        if not isinstance(selected, Device) and identity is None:
            raise ConsoleError("Refresh local USB descriptors and select the S10 first.")
        serial = identity.serial if identity is not None else selected.endpoint
        if self.confirm(
            "Start local ADB?",
            "Start the normal-user localhost ADB server if absent? This may create "
            "this PC's key on first use. Existing servers are never stopped.",
        ):
            self.controller.start_server(serial)

    def selection_changed(self) -> None:
        self.controller.candidate = None
        self.update_state()

    def verify(self) -> None:
        value = self.device_box.currentData()
        if not isinstance(value, Device):
            raise ConsoleError("Explicitly choose the USB S10, not another connected phone.")
        self.controller.verify(value)

    def enroll(self) -> None:
        candidate = self.controller.candidate
        if candidate is not None and self.confirm(
            "Enroll this phone?",
            f"Remember {candidate.model}, serial {candidate.serial}, as your S10? "
            "Changing phones clears previous acceptance, Wi-Fi binding and favorites.",
        ):
            self.controller.enroll(self.name.text())

    def start(self) -> None:
        current = self.profile.currentData()
        if not isinstance(current, Profile):
            raise ConsoleError("Choose a saved connection profile.")
        self.controller.start(
            SessionOptions(
                current,
                self.controller.settings.name,
                self.audio.isChecked(),
                self.screen_off.isChecked(),
                self.aac.isChecked(),
            )
        )

    def profile_dialog(self) -> None:
        current = self.profile.currentData()
        if not isinstance(current, Profile):
            raise ConsoleError("Select a profile to edit.")
        dialog = QDialog(self)
        dialog.setWindowTitle("Save quality / transport profile")
        form = QFormLayout(dialog)
        name = QLineEdit(current.name)
        name.setMaxLength(60)
        transport = QComboBox()
        transport.addItems(["usb", "wifi"])
        transport.setCurrentText(current.transport)
        keyboard = QComboBox()
        keyboard.addItems(["uhid", "sdk"])
        keyboard.setCurrentText(current.keyboard)
        size, fps, bitrate = QSpinBox(), QSpinBox(), QSpinBox()
        for widget, low, high, value in (
            (size, 640, 2560, current.size),
            (fps, 15, 60, current.fps),
            (bitrate, 1, 20, current.bitrate),
        ):
            widget.setRange(low, high)
            widget.setValue(value)
        for title, control in (
            ("Profile name", name),
            ("Transport", transport),
            ("Keyboard", keyboard),
            ("Max dimension (px)", size),
            ("Frames per second", fps),
            ("Bitrate (Mbps)", bitrate),
        ):
            form.addRow(title, control)
        buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel
        )
        buttons.accepted.connect(dialog.accept)
        buttons.rejected.connect(dialog.reject)
        form.addRow(buttons)
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return
        updated = Profile(
            name.text(),
            transport.currentText(),
            size.value(),
            fps.value(),
            bitrate.value(),
            keyboard.currentText(),
        )
        profiles = self.controller.settings.profiles
        for index, item in enumerate(profiles):
            if item.name == updated.name:
                profiles[index] = updated
                break
        else:
            if len(profiles) >= 12:
                raise ConsoleError("At most 12 profiles are supported; reuse an existing name.")
            profiles.append(updated)
        self.controller.save()

    def toggle_watch(self) -> None:
        self.controller.set_watching(not self.controller.watching)

    def record(self, passed: bool) -> None:
        current = self.records.currentItem()
        if current is None:
            raise ConsoleError("Select the scenario you actually exercised.")
        if passed and not self.confirm(
            "Record owner-observed success?",
            "Confirm you personally observed this scenario on the enrolled S10. "
            "A process starting or synthetic tests are not sufficient evidence.",
        ):
            return
        self.controller.record(current.text(0), passed)

    def pair(self) -> None:
        code = self.code.text()
        self.code.clear()
        try:
            self.controller.pair(self.endpoint.text(), code)
        finally:
            code = ""

    def bind(self) -> None:
        value = self.service.currentData()
        if not isinstance(value, str) or not value:
            raise ConsoleError("Select a discovered TLS service.")
        if self.confirm(
            "Verify selected TLS service?",
            "Confirm this is the S10's connection service from its Wireless Debugging "
            "screen. Only its identity will be read; a mismatch is refused.",
        ):
            self.controller.bind(value)

    def launch_app(self) -> None:
        self.controller.launch_app(self.apps_box.currentText())

    def favorite(self) -> None:
        selected = component(self.apps_box.currentText())
        if selected not in self.controller.apps:
            raise ConsoleError("Refresh and select a launchable app first.")
        favorites = self.controller.settings.favorites
        if selected not in favorites:
            if len(favorites) >= 30:
                raise ConsoleError("Favorite limit reached.")
            favorites.append(selected)
            self.controller.save()

    def push(self) -> None:
        chosen, _ = QFileDialog.getOpenFileName(self, "Send one file")
        if not chosen:
            return
        remote = self.remote.text()
        if not self.confirm(
            "Send file?",
            f"Send the selected file to {remote}? If a regular file exists there, "
            "replace it? No APK will be installed.",
        ):
            return
        self.controller.transfer("push", Path(chosen), remote, True)

    def pull(self) -> None:
        chosen, _ = QFileDialog.getSaveFileName(self, "Receive phone file")
        if chosen:
            self.controller.transfer("pull", Path(chosen), self.remote.text(), True)

    def show_status(self, message: str) -> None:
        self.status.setText(f"{self.controller.state.value}  |  {message}")

    def update_state(self) -> None:
        c = self.controller
        self.show_status(c.status)
        locked = c.active or bool(c.jobs)
        running = c.state == State.RUNNING and c.active
        self.start_button.setEnabled(not locked and c.settings.identity is not None)
        self.start_button.setToolTip(
            "Enroll the verified USB S10 on Prepare first."
            if c.settings.identity is None
            else "Start the external viewer."
        )
        self.stop_button.setEnabled(c.active or bool(c.jobs))
        self.progress.setVisible(
            c.busy or c.state in (State.CHECKING, State.STARTING, State.STOPPING)
        )
        for widget in (self.profile, self.edit_profile, self.audio, self.screen_off, self.aac):
            widget.setEnabled(not locked)
        for widget in [
            *self.nav,
            self.apps_refresh,
            self.launch_button,
            self.push_button,
            self.pull_button,
        ]:
            widget.setEnabled(running and not c.busy)
            widget.setToolTip("Available only during verified, user-started control.")
        self.favorite_button.setEnabled(bool(c.apps) and not c.busy)
        self.cancel_button.setEnabled(c.busy)
        self.refresh_button.setEnabled(not c.busy)
        self.device_box.setEnabled(not locked)
        self.verify_button.setEnabled(not locked)
        self.enroll_button.setEnabled(c.candidate is not None and not locked)
        self.pair_button.setEnabled(c.settings.identity is not None and not locked)
        self.bind_button.setEnabled(c.settings.identity is not None and not locked)
        self.watch_button.setText(
            "Stop reconnect observation" if c.watching else "Start reconnect observation"
        )
        self.cycles.setText(
            f"Observed USB disappear/reappear cycles: {c.cycles} / 2. "
            "Owner confirmation of recovered viewing/input is still required."
        )
        identity = c.settings.identity
        self.phone.setText(
            f"{c.settings.name} | {identity.model} | serial {identity.serial}\n"
            "Enrolled identity is stored privately on this PC."
            if identity
            else "No enrolled phone. Go to Prepare and explicitly select your USB S10."
        )
        self.backend.setText("Backend: " + c.backend)
        self.key.setText(c.key_status)
        candidate = c.candidate
        self.identity.setText(
            f"Verified: {candidate.model}, serial {candidate.serial}, "
            f"{candidate.codename}, Android API {candidate.api}"
            if candidate
            else "No candidate verified. No phone will be adopted automatically."
        )
        self.tls.setText("Bound TLS service: " + (c.settings.tls_service or "None"))
        self.sync_combo(self.profile, [(p.name, p) for p in c.settings.profiles])
        device_items: list[tuple[str, object]] = [("Select a device explicitly...", None)]
        device_items.extend(
            (
                f"{d.model or 'Unknown model'} | {d.endpoint} | {d.state}"
                f" | {'USB' if d.usb else 'network'}"
                f"{'' if d.eligible else ' (not eligible for USB enrollment)'}",
                d,
            )
            for d in c.devices
        )
        self.sync_combo(self.device_box, device_items)
        self.sync_combo(
            self.service,
            [("Select a TLS service explicitly...", None)]
            + [(f"{s.name} | {s.endpoint}", s.name) for s in c.services],
        )
        choices = list(dict.fromkeys([*c.settings.favorites, *c.apps]))
        self.sync_combo(self.apps_box, [(item, item) for item in choices])
        selected = self.records.currentItem()
        selected_name = selected.text(0) if selected else ""
        self.records.clear()
        for item in ACCEPTANCE:
            entry = QTreeWidgetItem([item, c.settings.acceptance.get(item, "Untested")])
            self.records.addTopLevelItem(entry)
            if item == selected_name:
                self.records.setCurrentItem(entry)
        self.records.resizeColumnToContents(0)

    @staticmethod
    def sync_combo(widget: QComboBox, items: list[tuple[str, object]]) -> None:
        existing = [(widget.itemText(i), widget.itemData(i)) for i in range(widget.count())]
        if existing == items:
            return
        chosen = widget.currentData()
        widget.blockSignals(True)
        widget.clear()
        for title, data in items:
            widget.addItem(title, data)
        for index, (_, data) in enumerate(items):
            if data == chosen:
                widget.setCurrentIndex(index)
                break
        widget.blockSignals(False)

    def closeEvent(self, event: QCloseEvent) -> None:
        self.controller.watch.stop()
        if self.controller.jobs:
            event.ignore()
            if not self.closing:
                self.closing = True
                self.controller.stop()
            QTimer.singleShot(100, self.close)
            return
        self.controller.stop()
        self.controller.pool.waitForDone()
        event.accept()
