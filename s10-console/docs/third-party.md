# Dependencies and upstream references

S10 Console is a separate desktop manager, not a fork of scrcpy or the older
PhoneStream companion. No Android APK or custom Android SDK build is required.
No commercial UI library or proprietary service is required.

| Dependency | Use / licensing |
| --- | --- |
| Python | Runtime, PSF license. |
| PySide6 Essentials / Shiboken6 / Qt | Qt Core/Gui/Widgets bindings. Distributed under their applicable LGPLv3/GPLv3 or commercial terms; this app requires no purchased commercial license. Retain the installed distribution's notices and comply with its redistribution/relinking obligations if repackaging it. |
| scrcpy | Separate official executable and matching temporary server, Apache-2.0. Installed by the owner through Arch, not bundled in this app's wheel. |
| Android platform tools / ADB | Separate normal-user backend from Arch android-tools; retain its upstream notices. |
| GNU coreutils stdbuf | Existing system helper for line-buffered native logs, GPL-3.0-or-later; invoked separately, not bundled. |
| Hatchling, uv, pytest/pytest-qt, Ruff, mypy | Development/build tooling, not phone agents. See each installed distribution's license metadata. |

The console's wheel contains its own Python source, icon, and guides, not a
vendored scrcpy/ADB/Qt runtime. The lockfile pins Python dependencies; the OS
package manager owns scrcpy, ADB and their native dependencies.

The existing local PhoneStream manifest/theme/ADB helper/tests were inspected
read-only as design prior art. This app does not depend on that worktree, its
environment, pairing store, credentials, SDK, or generated artifacts.

## Verified official behavior

- [scrcpy v4.1 README and Apache-2.0 notice](https://github.com/Genymobile/scrcpy/blob/v4.1/README.md)
- [Official Linux distribution / Arch package route](https://github.com/Genymobile/scrcpy/blob/v4.1/doc/linux.md)
- [Control, explicit clipboard and file drops](https://github.com/Genymobile/scrcpy/blob/v4.1/doc/control.md)
- [Actual clipboard dispatch](https://github.com/Genymobile/scrcpy/blob/v4.1/app/src/input_manager.c)
- [Actual CLI flags](https://github.com/Genymobile/scrcpy/blob/v4.1/app/src/cli.c)
- [Native INFO/stdout and WARN/stderr logging](https://github.com/Genymobile/scrcpy/blob/v4.1/app/src/util/log.c)
- [Exit status 0 normal / 1 startup failure / 2 disconnection](https://github.com/Genymobile/scrcpy/blob/v4.1/app/scrcpy.1)
- [Keyboard modes / layout](https://github.com/Genymobile/scrcpy/blob/v4.1/doc/keyboard.md)
- [Android12 output audio and limitations](https://github.com/Genymobile/scrcpy/blob/v4.1/doc/audio.md)
- [Display-off and wake](https://github.com/Genymobile/scrcpy/blob/v4.1/doc/device.md)
- [Capture paths and Android12 secure-display limitation](https://github.com/Genymobile/scrcpy/blob/v4.1/server/src/main/java/com/genymobile/scrcpy/video/ScreenCapture.java)
- [Android12 FLAG_SECURE contract](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/core/java/android/view/WindowManager.java)
- [Viewer shortcuts](https://github.com/Genymobile/scrcpy/blob/v4.1/doc/shortcuts.md)
- [Android ADB authorization / Wireless Debugging](https://developer.android.com/tools/adb)
- [ADB protocol including STLS](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/protocol.md)
- [ADB secure service discovery](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/transport_mdns.cpp)
- [ADB service-to-transport naming](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/socket_spec.cpp)
- [Samsung S10 HDMI/DeX / first use / Windows DeX-for-PC](https://www.samsung.com/us/support/answer/ANS10001955/)
- [Samsung broken-screen external display/input recovery](https://www.samsung.com/us/support/answer/ANS10002919/)
- [Qt for Python licensing](https://doc.qt.io/qtforpython-6/licenses.html)
