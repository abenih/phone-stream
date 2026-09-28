# S10 Console

A local, USB-first desktop console for **your previously authorized Galaxy S10
SM-G973F**. Manages official scrcpy in a separate native window on Linux/Wayland.
No companion APK, per-session MediaProjection dialog, recording, account, or cloud.

**Prepare before losing the display.** Without USB debugging and this PC's prior
authorization, the app cannot approve the phone's RSA prompt invisibly. ADB access
does not guarantee access before first unlock, to protected content, or after key
revocation. Keep your screen lock and know your PIN; this is not an unlock bypass.

**Enabled-lock recovery remains unconfirmed:** a black/not-visible lock screen
was reported in prior testing. Unlocked control does not establish PC credential
entry with the lock enabled. The cause was not established as power, pause, or
protected content.
Prepare a visible owner-unlock fallback such as compatible HDMI/DeX before
relying on a dead panel.

## Run

Requires Linux, Python 3.12-3.14, uv, ADB (`android-tools` on Arch), official scrcpy 4.1, and the standard
Linux `coreutils` `stdbuf` helper for timely native diagnostics.
On Arch, review/install the `scrcpy` package through your normal package manager;
the application never installs system packages or restarts a shared ADB server.

```sh
cd s10-console
uv sync --locked
uv run s10-console
```

Opening the console does not start capture or inspect phones. Use **Refresh devices**,
select the correct local Samsung USB descriptor, verify its identity, and enroll it on
**Prepare**. Only **Start control** opens the viewer. Audio and display-off are
off by default. See the preparation guide before treating the setup as a recovery tool.

**Stop:** left Alt+Q in the viewer, Stop control, or Ctrl+Shift+X in the focused
console. Stop cancels this app's work, not other programs' shared ADB connections.
Closing the app does not leave hidden capture running.

The console honors the shared per-phone `flock` at
`~/.local/state/phone-device-leases/<runtime-serial>.lock`. If another tool/session
owns it, phone actions fail visibly until an explicit handoff. Never delete the
lock or disrupt existing tethering/VPN sessions to work around it.

## Prepare and recover

- [Before-display-failure preparation](docs/preparation.md)
- [Troubleshooting, shortcuts, privacy and fallback](docs/troubleshooting.md)
- [Exact feature and hardware acceptance status](docs/acceptance.md)
- [Dependency licensing and verified official sources](docs/third-party.md)

USB trust must be prepared first. Optional Wi-Fi uses Android's paired encrypted
Wireless Debugging, not legacy plaintext TCP/IP. Missing TLS discovery or changed
pairing requires USB recovery. No phone or PC is ever rebooted by the app.

Default quality: USB H.264 / 1600px / 60fps / 8Mbps; Wi-Fi 1280px / 30fps / 4Mbps.
The default keyboard is UHID; SDK compatibility is selectable. Fullscreen, rotation,
fit/1:1, gestures, manual clipboard and display power are native **viewer**
shortcuts, not embedded Qt controls.

Files are explicit single-file transfers in accessible `/sdcard/` storage;
app launching uses discovered launchable components. No private-data scrape,
notifications, recording, screenshots, VPN or reverse-control features are included.

## Desktop launcher and packages

Build source and wheel distributions:

```sh
uv build --out-dir dist
```

For an independent user-local installation from the built wheel:

```sh
uv tool install --python python3 dist/s10_console-0.1.0-py3-none-any.whl
~/.local/bin/s10-console-install-launcher
```

The launcher installer writes only `s10-console.desktop` and its icon below the
user's XDG data directory. It installs no autostart service. The desktop entry uses
an absolute executable, so it does not depend on the current directory or shell
activation. Keep that installation/environment in place.

For a launcher using this development checkout instead:

```sh
uv run s10-console-install-launcher
```

This points at this checkout's `.venv`; moving/deleting it breaks that launcher.
Do not run both installations with the same configuration simultaneously.

To test desktop entry generation without touching your real application menu:

```sh
uv run s10-console-install-launcher --data-home /absolute/private/test-prefix
```

## Verification without a phone

From `s10-console/`:

```sh
uv sync --locked
uv run ruff check src tests
uv run mypy
uv run pytest -q
uv run s10-console --smoke-test --config-dir /absolute/private/test-config
```

Tests use synthetic fixtures, reject unmocked Python connection attempts, and run Qt offscreen.
Lease tests use temporary directories and synthetic identities, not a live phone's
lease. The retained audio helper is tested only with generated in-memory samples.
Device-driving fixture harnesses, APKs, media, and local acceptance records are not
included in this publication. No live test is needed to run the commands above.
The idle smoke mode opens a real Qt window then exits without invoking ADB, reading
phone data, or starting capture. Use `QT_QPA_PLATFORM=offscreen` for headless smoke.
Launcher validation uses `desktop-file-validate` when available.

**Live acceptance is separate.** Prior testing reported authorized USB identity
and native renderer / decoder startup; the owner confirmed visible phone content,
keyboard input and mouse control. Controlled X11 clipboard results do not confirm
native Wayland behavior. See the acceptance matrix for remaining gaps: these
results do not establish complete dead-panel recovery in every phone state.
This source publication performs no live-device or real-audio checks.
