# Game Controls Link - Android development slice

A separate Kotlin/XML Android app: **S10 Host** runs the game or other selected
app; **A06 Client** receives H.264 screen video and can optionally send manual
single-finger touches over the same authenticated TLS connection.

**This is an initial development slice, not a validated game controller.**
There is no official game support or endorsement. COD capture/input
compatibility, account safety, latency, thermal behavior, and Samsung-specific
runtime behavior have not been verified. JVM tests are not live-device evidence.
See [the validation record](docs/validation.md) for exact executed checks.

## Isolation

- Application ID / namespace: `dev.gamecontrols.link`.
- App label: **Game Controls Link (dev)**; version `0.1.0-dev` / versionCode `1`.
- Android 12 / API 31 minimum; API 36 target.
- This APK cannot update `dev.phonestream` or an app with another application ID.
  Its new local debug signing identity is not an existing installed app's key.
- No ADB, device lease, installation, phone capture, settings change, or phone
  input was authorized or performed during development.
- No root, permission enabling, lock/PIN bypass, anti-cheat bypass, game-memory
  access, macros, auto-aim, recoil assistance, global navigation, or remote text
  entry. Audio and camera are absent, not merely muted.
- No code or scripts manage S10 Console, Phone Stream, Game Controls, another
  workspace, another process, hotspot settings, cellular data, VPNs, or routes.

## Implemented vertical slice

1. **Explicit roles.** A native ViewBinding setup screen selects S10 Host or A06
   Client. The Client viewer is a separate, non-exported Activity.
2. **Session-only pairing.** The Host explicitly opens a two-minute invitation
   on one existing private LAN interface. It contains an IPv4 endpoint, the full
   SHA-256 Host SPKI pin, and a cryptographically random 256-bit token. The Client
   reviews the endpoint and fingerprint before connecting.
3. **Authenticated encrypted transport.** TLS 1.3 validates the pinned,
   self-signed EC Host certificate before the Client transmits the token.
   The Host consumes the token exactly once before admitting session traffic.
   A single framed TLS connection carries video, control, acknowledgements, and
   heartbeat traffic. There is no unauthenticated input path or trust-all mode.
4. **Locally consented capture.** Pairing starts neither capture nor control.
   The Host separately requests Android MediaProjection consent; a visible
   mediaProjection foreground service starts before using that fresh grant.
   MediaCodec surface encoding targets a maximum 1280-pixel long edge, 30 fps,
   and 3 Mbit/s, with codec-aligned, preferably exact-aspect dimensions.
5. **Native viewing.** A SurfaceView decoder uses bounded input/in-flight queues
   and drains delayed codec output even if no new packet arrives. Stale or
   overloaded video stops the session rather than silently dropping dependent
   H.264 frames and continuing with corrupt or increasingly delayed pictures.
6. **Separately approved manual control.** The Host user must manually enable
   Accessibility, choose a user-installed launchable target, and confirm
   **Allow control and open selected app**. A fresh in-memory grant is issued
   only after that app becomes the active, focused window. Touches account for
   the Client's letterboxing and any encoder content inset; bar touches are
   rejected, not clamped to screen edges.
7. **Fail-closed lifecycle.** Stop/disconnect invalidates the session, clears
   input, attempts to end the app's continued gesture, closes its sockets, and
   releases capture/decoder resources. There is no automatic reconnect or
   resumption of capture. Every new session needs a new invitation, new
   MediaProjection consent, and new optional control approval.

### Networking and trust

The first version supports canonical RFC1918 IPv4 literals only. Both endpoints
must be unicast hosts on the selected interface's subnet; DNS, IPv6, public IPs,
CGNAT, loopback, link-local addressing, cross-subnet routes, cellular fallback,
VPN transport, discovery, relays, STUN/TURN, and cloud services are not used.

The Host binds port `48765` to **one explicitly selected existing local address**,
not all interfaces. The Client creates its socket through a matching existing
Android Wi-Fi/Ethernet `Network`. Loss of that selected path ends the session.
The Host can also offer an already-enabled hotspot interface named `wlanN`,
`apN`, `swlanN`, or `softapN` when Android does not represent it as a `Network`.
This is a conservative interface-name allowlist, not proof of Samsung hotspot
compatibility. The app never enables or reconfigures tethering. Unknown layouts
fail with an explanatory status instead of changing networking.

The Host identity is generated in this app's own AndroidKeyStore UID/alias
(`game-controls-link-host-tls-v1`). It is never imported from another app. Pairing
tokens, capture grants, control grants, and the Client invitation are kept in
memory; there is no saved peer database or automatic remembered trust. Backup
and device transfer are disabled.

The private invitation must be transferred through a **trusted private channel**.
Anyone with the complete unexpired invitation can authenticate the first Client
connection. A LAN is not trusted merely because it is local. Compare the full
fingerprint with the Host before connecting; no short numeric comparison or QR
scanner is implemented.

Copying an invitation is an explicit user action. The app marks its clipboard
entry sensitive on Android 13+ and clears it only if it still matches the entry
this Activity copied. The OS clipboard or the user's chosen transfer channel
may retain a copy; an expired/consumed token no longer authenticates. Invitation
fields are excluded from View state, autofill, and personalized keyboard
learning. Setup and viewer windows use `FLAG_SECURE`.

### Control and lifecycle boundaries

The Accessibility service inspects active-window/package and bounds metadata to
restrict input. Although Android requires window-content capability for that
metadata, this implementation does not read, traverse, store, or transmit text,
children, or screenshots through Accessibility. Only MediaProjection supplies
video. System/locked windows and points outside the selected app or underneath
another window are rejected. The service does not approve Android permissions.

Only **one finger** is supported. Input is acknowledged stop-and-wait; unsent
MOVE events may coalesce but DOWN/UP order is preserved. A stationary
continuation is sent only while a real Client finger remains held.

- Native gesture slices: 16 ms; callback deadline: 250 ms.
- Client unsent-input age bound: 250 ms; ACK deadline: 300 ms.
- Host input dispatch age bound: 150 ms; a closed connection immediately makes
  its queued input ineligible, even before the main thread handles disconnect.
- Host held-finger idle deadline: 350 ms.
- Continuous single-finger hold hard limit: 10 seconds; exceeding it stops the
  session. Control grants also expire after 10 minutes without silent renewal.
- Heartbeats: approximately 500 ms; loss stops the connection within the
  two-second liveness deadline, subject to Android thread scheduling.
- Media queue age bound: 500 ms; server queue max 4 frames / 2 MiB, decoder
  queued-plus-in-flight max 6 access units / 2 MiB.

**Android Accessibility has no public raw `ACTION_CANCEL` injection API.**
On stop/revocation the service ends only its own continued stroke with a terminal
continuation. It never injects a dummy tap elsewhere. Android may refuse or
cancel that release; the app reports this rather than claiming guaranteed
cancellation. Already submitted short terminal gestures may finish. This is
not silently ignored: an unconfirmed release disables further grants until the
Accessibility service is manually restarted. This is
not equivalent to hardware touch input, and it is not an implementation of
independent multi-touch joystick plus aiming controls.

| Trigger | Implemented action |
| --- | --- |
| Host setup backgrounds before capture | Close invitation/paired session; an outstanding OS projection dialog has a bounded consent deadline |
| Host UI backgrounds into the game during sharing | Foreground-service screen sharing continues intentionally |
| Selected controlled app loses foreground / a focused overlay appears | Stop session and release input |
| Client loses focus, pauses, backgrounds, rotates, or loses its Surface | Disconnect, discard input/video, release decoder; Host stops on connection loss |
| Host display rotates, resizes, or changes density | Stop capture and session; require fresh invitation/consent |
| Projection is revoked or captured content becomes hidden | Stop session |
| Host locks / screen switches off / capture notifications are disabled | Stop session |
| Accessibility is interrupted/lost during control | Stop session |
| Local Revoke control | Release input and return to view-only sharing |
| Malformed, stale, overlapping, ungranted, or wrong-role input | Stop session |
| Stop notification, removed Host task, socket/heartbeat loss, queue overload | Stop owned resources; never auto-resume |

Orient both phones **before** starting. Entering an app that forces a different
Host orientation ends this slice's session; it does not silently reuse consent
or map touches with stale geometry. The video already represents the current
display orientation, so touch mapping must not apply a second rotation.
For Android 12/API 31, control requires exact capture/encoder aspect ratio;
otherwise it remains view-only because uniform MediaProjection scaling was
standardized in Android 12L. Android 14+ requests default-display capture and
rejects content-size changes rather than treating a partial-app capture as the
whole screen.

## Offline development build

The project reuses the approved cached toolchain versions:

| Component | Version |
| --- | --- |
| JDK | 17 |
| Gradle | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.3.21 |
| Android platform / Build Tools | 36 / 35.0.0 |
| Core / Activity / Lifecycle | 1.17.0 / 1.11.0 / 2.9.4 |
| Material / coroutines / JUnit | 1.13.0 / 1.10.2 / 4.13.2 |
| Serialization core (transitive AndroidX SavedState) | 1.9.0, explicitly constrained to the cached verified version |

The wrapper requires a workspace-local Gradle distribution and dependency
cache. It **fails before attempting a download** if that distribution is absent.
For a fresh checkout, provision the approved Gradle 8.13 binary distribution
and its needed dependency artifacts into `android/.gradle-user-home/`; do not
point this build at another session's writable Gradle home.

The standard distribution cache path is
`wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/`.
The wrapper JAR and distribution hashes are retained in
[the wrapper directory](android/gradle/wrapper). The dependency SHA-256 metadata
and generated Gradle lockfiles are included. The checksum metadata was imported
from the user-provided sanitized reference; it is not independent upstream
provenance certification.

With an existing approved JDK, SDK and populated local cache:

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=/home/benji/.local/share/phone-stream/android-sdk

./android/gradlew :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The wrapper always adds `--offline --no-daemon --max-workers=2`. SDK/JDK
auto-downloads are disabled, Gradle parallel execution is off, and Kotlin
compilation runs in-process. No emulator, ADB, platform-tools, or physical device
is needed for these tasks. Android tooling may warn that platform-tools is not
installed; do not install it to satisfy a warning.

Build caches, Java temporary files, Android user configuration, and a newly
generated debug signing key are isolated in ignored subdirectories under
`android/`. No release key or existing app signing key is copied.

Outputs after successful validation:

- APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
- Stable local copy: `dist/game-controls-link-0.1.0-dev.apk`.
- JVM reports: `android/protocol/build/reports/tests/test/` and
  `android/app/build/reports/tests/testDebugUnitTest/`.
- Lint: `android/app/build/reports/lint-results-debug.html`.

## Future live prerequisites - not performed

1. An explicit handoff/device grant to this session's actual actor, coordinated
   with the canonical exclusive device lease. Another session's existing grant
   is not inherited. The current development work has no phone grant.
2. Device-owner approval to install this new app independently on each phone.
   Preserve all existing apps, pairings, cellular data, hotspot, and routes.
3. A compatible **already working** on-link LAN path. Verify the Host interface
   and Client Wi-Fi path without disrupting the S10's internet connection.
4. Local user notification and fresh MediaProjection approval. Accessibility is
   optional, manually enabled, and separately approved per session. Android 13+
   may apply its restricted-settings policy to sideloaded apps; this app does
   not circumvent it.
5. Validate AndroidKeyStore-backed TLS on both actual Android versions, then
   screen encode/decode and all stop/release cases with a public, non-sensitive
   fixture before considering a game. Test portrait/landscape, letterboxes,
   rejection of system windows, disconnect, revocation, rotation, and Client
   backgrounding. A network-loss test must not disable the S10's mobile data or
   hotspot; use app-local disconnect/Stop or a separately authorized method.
6. Measure frame age, input latency, resource cleanup, temperature, battery, and
   behavior under load. No competitive-latency or thermal-safety assertion is
   justified yet. Any game/account trial needs a separate informed decision.

## Provenance and non-goals

The seed worktree was empty. The new app, protocol, UI, and tests are independent
source. Public Android capture/codec/gesture patterns and pin verification were
adapted after reading the user-provided sanitized Phone Stream Android reference.
The reference wrapper binary, dependency verification metadata, and necessary
cached build artifacts were copied and checked; its source tree was not built
or modified in place. No device identity, pairing store, signing key, phone
artifact, or old session identity was imported.

The transport is a new small phone-to-phone protocol, not interoperable with
Phone Stream protocol 3 or the Python Game Controls mapper. Audio, multi-touch,
saved pairing, QR scanning, discovery, adaptive bitrate, automatic rotation
recovery, game-specific layouts, and store publication are out of scope for
this first slice. Kotlin/AndroidX/Material/Gradle dependencies retain their own
licenses; this private development work does not assign a new license to
user-owned application source.
