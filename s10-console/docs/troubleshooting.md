# Troubleshooting and boundaries

| Status / symptom | Safe next step |
| --- | --- |
| No enrolled phone | Refresh, explicitly select the USB SM-G973F, verify hardware identity, then enroll. Never select a different phone because it is present. |
| scrcpy missing | Install the official Arch `scrcpy` package through your normal package manager. Only the official Genymobile project is the upstream download source. The app installs nothing automatically. |
| Unsupported scrcpy | This release targets 4.1; capability-compatible 4.x is accepted, not hardware-certified. Safety flags are mandatory. Check the client/server package is complete and matching. |
| ADB server absent | Review Prepare > Start local ADB. Starting an absent normal-user server is explicit. |
| Incompatible server | Coordinate with the program/person that owns it. This app will not kill, replace, or restart it. |
| Unauthorized | Unlock normally and approve this PC while the display/HDMI is usable. You cannot approve the RSA dialog invisibly. |
| Offline / missing | Check data cable, USB socket, boot state and power. Do not reset the device or use global reconnect commands. |
| No permissions | Review your host's Android USB permissions/udev rules through your normal administration process. The app does not change them. |
| Wrong model / identity changed | Stop. Verify the physical phone and explicit selection. The app targets SM-G973F/beyond1/API31 only. |
| Running process but black / frozen view | Process status is not capture evidence. Review the phone's boot/lock state, protected content, encoder support and cable. Protected surfaces can legitimately remain blank. |
| UHID failed / wrong keyboard layout | Use Alt+K to select the layout or explicitly save an SDK keyboard profile. SDK Unicode/IME input is limited. |
| Wi-Fi unavailable | Return to USB. Reopen phone-side Wireless debugging, review pairing and connection ports, and explicitly bind its TLS service again. No legacy plaintext fallback. |
| Audio failed | Turn audio off or opt into AAC compatibility. Only normal output is supported; Android12 output forwarding may mute local sound. No call-audio/microphone promise. |
| Clipboard failed | Use Alt+C / Alt+V in the viewer, not automatic sync. Some apps/OS states deny clipboard access. Never paste passwords or PINs. |
| File access denied | Only accessible shared storage is supported. Choose an existing parent directory, not Android/ or private app storage. Scoped storage and pre-unlock encryption still apply. |
| Transfer interrupted | Pulls keep the existing destination and remove the temporary local file. Pushes may leave a partial phone file; verify/delete that explicitly. |
| Forced process termination | Upstream cleanup is unverified. The console never guesses which shared mappings belong to it. Coordinate inspection with other ADB users; do not remove all forwards/reverses. |
| Configuration rejected | Original data is preserved. Check private ownership/permissions and version/schema; do not blindly overwrite credentials or unrelated files. |
| Another console instance | Close the existing instance using this configuration before starting another. |
| Wayland window-icon warning | Some GNOME versions lack the optional icon protocol. This warning alone does not establish a video/control failure. |
| Phone busy / shared lease held | Ask the hardware coordinator/current owner for a handoff. Never delete the lock file, kill its owner, or use an alternate ADB command to bypass it. |

## Reported black lock screen on this S10

The owner reported that the lock screen is black/not visible in the normal PC
viewer. This is an unresolved recovery blocker, not something the app's
authorization check can fix. The production console does not require an unlocked
phone merely to start the viewer or expose Wake. However, the OS still decides
what can be displayed and controlled.

No screen-off/no-power-on option was active, and scrcpy starts unpaused by
default. That does not prove the later runtime state: a phone may sleep, the
viewer may be paused, or protected content may be blank. An explicit owner
action can use **Wake**, **left Alt+Shift+O** (display on), or **left Alt+Shift+Z**
(unpause), without removing the lock. Do not type a PIN blindly into an unknown
screen. These recovery actions were not performed during the investigation.

Android12's `FLAG_SECURE` contract excludes protected windows from screenshots
and non-secure displays. scrcpy4.1's SurfaceControl fallback explicitly avoids
creating a secure display on Android12 because shell permission cannot do so.
This is a possible explanation, **not proof that this Samsung keyguard has that
flag or that secure capture caused this incident**. No flag bypass, root change,
credential collection, or lock removal is supplied.

If the lock screen remains unavailable, use a previously prepared compatible
USB-C HDMI/DeX monitor plus keyboard/mouse, or a temporarily usable physical
display for normal owner unlock. First-use DeX prompts may still require
preparation. Do not rely on this app alone for fully screenless locked recovery
until that scenario is actually demonstrated.

## Start, Stop and reconnect

Start verifies the enrolled phone immediately before launching scrcpy. It never
falls back to another phone or transport. During a user-started session, a
disconnection can trigger at most **six recovery launches**, with 1/2/4/8-second
bounded backoff and a **60-second recovery deadline** for each loss. The total
launch budget is reset only by a new user Start, not repeated brief connections.
If the backend or identity is invalid, stop with a visible error.

Closing the viewer normally (including **left Alt+Q**) disables reconnect.
**Stop control** or **Ctrl+Shift+X in the focused console** cancels this app's
pending operations and owned children. This is not a global Wayland shortcut.
Closing the console waits for owned process shutdown; it does not detach hidden
capture. No login/autostart service is installed.

The backend retains its normal cleanup. If it does not exit on SIGINT, the app
can kill only its owned process group and reports unverified cleanup. It never
kills a shared ADB server or indiscriminately removes device mappings.

## Shared hardware lease

This console cooperates with other phone tools using one shared default lock:
`~/.local/state/phone-device-leases/<enrolled-serial>.lock`. The serial comes from
explicit runtime enrollment, never tracked source. Acquisition is exclusive,
nonblocking OS `flock`; busy means stop visibly, not wait indefinitely or fall back
to another phone. The app never unlinks/replaces a lease inode or treats a saved
PID as proof of ownership.

The lease covers phone-specific ADB, pairing, identity checks, control, files,
reconnect waits and cleanup. Operations within the same console reuse its held
lease. The viewer inherits the descriptor so a console crash cannot release
exclusion while that viewer is still alive. An ordinary process crash without a
surviving child releases its descriptors automatically.

Before enrollment, Refresh reads only local Samsung USB sysfs descriptors.
Explicit verification acquires that serial's lease before consulting ADB and
checking the model. After enrollment, ADB refresh also requires its lease.
Version/help checks and key-file metadata checks do not touch the phone.

For agent-directed testing, an **explicit coordinator/user grant is additionally
required**; obtaining a free OS lock is not permission to disrupt the phone.
`coordination.json` is policy, not process-liveness authority. Existing internet,
tethering, proxy, hotspot and VPN usage must remain unchanged. Replug/reboot/radio
tests need a separately agreed maintenance window.

## Viewer controls

The viewer is a native external window. It is not embedded or remotely manipulated
with X11/Wayland focus hacks.

The native backend is launched through the standard Linux `coreutils` `stdbuf`
helper with line-buffered stdout/stderr. scrcpy's informational logs otherwise
remain buffered while piped, hiding timely renderer/decoder diagnostics. This
changes host-side logging only, not capture, transport, or phone settings.

| Action | In the focused viewer |
| --- | --- |
| Back / Home / Recents | Right click / middle click / Alt+S |
| Fullscreen | Alt+F or F11 |
| Fit / pixel-perfect | Alt+W / Alt+G |
| Rotate view / phone | Alt+Left or Right / Alt+R |
| Pinch / rotate content | Ctrl+click-and-drag |
| Physical display off / on | Alt+O / Alt+Shift+O |
| Power key | Alt+P (screen-off mode may turn it back off shortly afterward) |
| Physical keyboard settings | Alt+K with UHID |
| Explicit phone copy / PC paste | Alt+C / Alt+V |
| Stop / close viewer | Alt+Q |

Display-off is a session opt-in, not simulated hardware failure. Test both continued
control and Alt+Shift+O wake on your phone. The physical power button can also wake
the panel. No stay-awake or screen timeout setting is silently changed.

Clipboard autosync is always disabled. Ordinary Ctrl+C/Ctrl+V stay within Android;
explicit Alt+C requests phone clipboard content, and Alt+V sends PC clipboard
content to Android. Apps may read pasted clipboard contents. The console itself
does not read either clipboard or log contents. Native scrcpy reports failures;
the console surfaces safe error categories, not raw content.

### Test manual clipboard transfer

Use **left Alt**, not right Alt, while the **native scrcpy phone window** is
focused. These explicit shortcuts work independently of the selected UHID or
SDK keyboard mode. The tested USB profile uses UHID. Merely selecting text and
pressing Ctrl+C/Ctrl+V in the phone window does not synchronize the PC clipboard
with autosync disabled.

1. **PC to phone:** copy a public sample such as `hello from pc` normally on the
   PC. Focus an ordinary editable phone text field in the viewer, then press
   **left Alt+V**. This explicitly transfers PC text into Android's clipboard
   and requests paste.
2. **Phone to PC:** select public text in a normal phone text field through the
   viewer, then press **left Alt+C**. Focus a PC text editor and press **Ctrl+V**.
   The Android copy action and explicit retrieval are handled even though
   automatic clipboard synchronization is disabled.

Some apps intercept copy/paste or block clipboard access. If an explicitly
authorized test fails, note the direction, exact shortcut, keyboard mode and
whether the field permits ordinary editing; do not send the clipboard contents.
No defect is established merely because automatic Ctrl-based cross-device sync
does not occur. Do not turn autosync on as a workaround without owner approval.
Use only public, non-sensitive samples, never passwords, PINs or tokens.

While other sessions share this phone, another live clipboard or audio test needs
a fresh coordinator grant. Preserve hotspot/tethering/VPN settings throughout.

### Explicit X11 compatibility trial

Actual bidirectional transfers passed in the controlled X11/XWayland fixture
test. The earlier native Wayland/manual case is still unresolved. After stopping
this console and arranging a shared-device handoff if needed, an **explicit
per-launch** troubleshooting trial can use:

```sh
SDL_VIDEO_DRIVER=x11 .venv/bin/s10-console
```

Run this from `s10-console/`; it changes only that launch's desktop backend, not
global settings, clipboard autosync, the phone, or the default saved profile.
It is not a claimed fix for all clipboard contexts or for the black lock screen.
The test-only `SDL_VIDEO_X11_XINPUT2=0` hint is not a production requirement and
must not be promoted into a global setting.

**Do not drag APKs onto the viewer.** Upstream scrcpy explicitly installs dropped
APKs and pushes other dropped files. Version 4.1 has no documented file-drop-disable
flag. This console exposes no APK installer and uses explicit file choosers.
This external-window behavior is not falsely represented as blocked.

## Stored data

Default location: `$XDG_CONFIG_HOME/s10-console/settings.json` (usually
`~/.config/s10-console/settings.json`), with a private 0700 directory and 0600 file.
It contains the phone identity, friendly name, quality profiles, TLS service name,
explicit app favorites, and timestamped owner acceptance records. It contains no
private keys, pairing codes, active-session intent, clipboard text, PINs, media,
or raw diagnostics.

The GUI does not poll devices or network services on idle launch. Refresh and
discovery are explicit; reconnect observation polls only after the owner starts
it. Wi-Fi preflight sends a minimal ADB protocol greeting to the selected
discovered LAN endpoint and requires TLS upgrade before authenticated ADB use;
it sends no key, PIN, or command. ADB performs the authenticated TLS exchange.
This is a local trusted-PC/LAN workflow, not a malicious-host isolation boundary.
