# Feature and acceptance status

This guide separates implemented behavior, offline regression coverage, and
historical hardware observations. **A dead-screen recovery guarantee has not
been established.** Rehearse the relevant scenarios on your own authorized
phone/PC before relying on them.

## Included implementation

- Native Qt Control, Prepare and Tools pages with a separate managed scrcpy window.
- Explicit USB enrollment and repeated serial/model/API identity verification.
- Visible unauthorized/offline/missing/ambiguous errors, without first-phone fallback.
- H.264 quality profiles, UHID keyboard and selectable SDK compatibility.
- Start/Stop, cancellation, bounded reconnect and shared exclusive device leases.
- Manual viewer clipboard shortcuts with automatic synchronization disabled.
- Opt-in normal-output audio, AAC compatibility and physical display-off.
- Fixed navigation keys, explicit app enumeration/launch and saved favorites.
- Single-file shared-storage push/pull with explicit overwrite handling.
- Optional paired Wireless Debugging, TLS preflight and identity binding.
- Private atomic settings and key-readiness checks without private-key reads.
- Separate owner-confirmed acceptance records, wheel/source packaging and launcher.

Fullscreen, rotation, fit/1:1, pinch, clipboard and display power use native
viewer shortcuts, not embedded Qt controls. Shared-storage transfers are not
private-app backups. Upstream scrcpy can install dropped APKs: do not drag them
onto its window; this console's explicit tools do not install APKs.

## Offline verification

The README provides the reproducible Ruff, strict mypy and pytest commands.
The suite uses synthetic identities, fake ADB/protocol responses, offscreen Qt,
temporary configuration/lease directories and independently owned test processes.
It does not connect to a phone or prove hardware behavior. The audio-metrics test
uses generated in-memory samples, not a microphone or playback monitor.

Coverage includes identity changes and ambiguity; unavailable/unauthorized devices;
safe command construction and privacy defaults; backend capability failures;
bounded retries and cancellation; owned-process lifetime; fragmented pairing
prompts and secret redaction; rejected plaintext handshakes; schema-checked private
settings; safe file paths and partial transfers; launcher quoting and idle UI.

Regressions cover stale backend/key/device error state, failed observation-loop
shutdown, and idle lease release when a reconnect timer expires. Lock tests use
temporary synthetic leases, never the production device's lease.

The publication review is offline. Historical package hashes and old test counts
are not substitutes for checking the exact published snapshot. Generated wheels,
private receipts, live harnesses and local device state are not committed here.

## Historical hardware observations and limits

The following summarizes the original baseline owner's development evidence,
not later final-verifier results, a current device inspection, or a fresh
acceptance run for this publication. No serials, endpoints, clipboard
contents, screenshots, recordings, credentials or private result files are
included. Owner-confirmed behavior, technical telemetry and controlled fixtures
provide different strengths of evidence.

| Scenario | Recorded evidence | Boundary |
| --- | --- | --- |
| USB identity and authorization | Exact S10 model/API and existing trust observed | No authorization prompt was bypassed. |
| Screen, mouse and keyboard | Owner confirmed usable unlocked phone content and input | Does not establish locked-screen recovery or every app. |
| Renderer/decoder startup | Native renderer and decoded texture telemetry observed | Process/decoder startup alone does not prove visible usable content. |
| Scoped Stop/cleanup | Completed trials reported owned-process exit and preserved mapping baseline | No statement about a currently running viewer or lease is made. |
| Viewer stop/start | Technically observed on the already authorized USB path | Not a USB replug, phone reboot or shared-server restart test. |
| Explicit clipboard | Bidirectional transfer passed in a controlled X11/XWayland fixture | Native Wayland/manual failures remain unresolved; not every app was tested. |
| Fullscreen | Owner confirmation and controlled X11 observation | Does not establish all rotation or navigation behavior. |
| Navigation | Back delivery and Home/Recents fixture lifecycle changes observed | Visual task-switcher behavior and complete recovery sequence not confirmed. |
| Public file transfer | Controlled push/pull reported exact hash match and overwrite refusal | Does not establish private/scoped-storage access or encrypted pre-unlock storage. |
| Normal audio | Playback-stream initialization reported | Audibility and complete audio behavior unconfirmed; no microphone/call-audio claim. |
| Enabled-lock PC unlock | Black/not-visible lock screen reported | Blocked/unconfirmed; cause not established. Never enter a PIN blindly. |
| Two no-touch USB replug cycles | Not established | Needs owner observation of recovered view and input. |
| Physical display off/wake | Not established | Needs explicit opt-in and a physical recovery path. |
| Paired Wi-Fi reconnect | Not established | Needs authorized pairing, identity and changed-port/fallback testing. |
| PC/ADB restart and phone reboot | Not established | Owner action only, with a maintenance window and known-PIN fallback. |
| Protected content | Not established | Android may legitimately blank or block protected surfaces; no bypass. |
| General app launching | Not independently established | An owned diagnostic fixture is not a general-app acceptance test. |
| HDMI/DeX recovery | Not established | Needs compatible hardware, first-use preparation and normal owner unlock. |

Read the preparation and troubleshooting guides before live use. Any future
agent-directed hardware exercise requires explicit authorization and coordinated
exclusive device ownership. Replug, reboot, radio, hotspot and tethering changes
are not part of source verification.
