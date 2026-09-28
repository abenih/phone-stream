from PySide6.QtCore import Qt

from s10_console.controller import Controller
from s10_console.models import Settings, State
from s10_console.store import Store
from s10_console.ui import Window, theme


def test_idle_ui_no_device_or_capture(qtbot, qapp, tmp_path):
    theme(qapp)
    c = Controller(Settings(), Store(tmp_path / "config"))
    window = Window(c)
    qtbot.addWidget(window)
    window.show()
    assert window.tabs.count() == 3
    assert not window.start_button.isEnabled()
    assert not window.stop_button.isEnabled()
    assert not window.audio.isChecked()
    assert not window.screen_off.isChecked()
    assert not c.jobs
    assert window.records.topLevelItem(0).text(1) == "Untested"
    assert window.device_box.currentData() is None
    window.close()


def test_ui_active_stop_shortcut_and_disabled_tools(qtbot, tmp_path, identity):
    c = Controller(Settings(identity=identity), Store(tmp_path / "config"))
    window = Window(c)
    qtbot.addWidget(window)
    window.show()
    window.activateWindow()
    qtbot.waitUntil(window.isActiveWindow)
    assert window.start_button.isEnabled()
    assert all(not item.isEnabled() for item in window.nav)
    c.recovery.start()
    c.state = State.RUNNING
    c.changed.emit()
    assert window.stop_button.isEnabled()
    assert all(item.isEnabled() for item in window.nav)
    assert not window.profile.isEnabled()
    qtbot.keyClick(
        window,
        Qt.Key.Key_X,
        Qt.KeyboardModifier.ControlModifier | Qt.KeyboardModifier.ShiftModifier,
    )
    qtbot.waitUntil(lambda: not c.active)
    assert c.state == State.IDLE
    window.close()


def test_selecting_different_candidate_clears_verification(qtbot, tmp_path, identity):
    c = Controller(Settings(), Store(tmp_path / "config"))
    window = Window(c)
    qtbot.addWidget(window)
    c.candidate = identity
    window.update_state()
    assert window.enroll_button.isEnabled()
    window.selection_changed()
    assert c.candidate is None
    assert not window.enroll_button.isEnabled()
    window.close()
