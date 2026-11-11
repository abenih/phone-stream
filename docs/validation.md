# Android slice validation - 2026-10-01

## Result

**Initial native Host/Client slice implemented, built, and JVM-tested.
No live Android or game validation was performed.**

Worktree: `/home/benji/projects/phone-stream.worktrees/android-game-controls-tool-development`

Actual session actor: `copilotcli:/c62358b4-6a60-46b6-a4b3-657324f1b0cc`

The parent reported releasing its canonical S10 lease. That was explicitly
**not** a grant to this actor. This session performed no ADB command, lease
acquisition, installation, phone capture/input, pairing change, networking
change, or operation on an existing phone app. No source was committed or
published.

## Final executed gate

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
ANDROID_HOME=/home/benji/.local/share/phone-stream/android-sdk \
./android/gradlew \
  :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The wrapper adds `--offline --no-daemon --max-workers=2`. The final run used the
generated dependency locks and the unchanged imported SHA-256 dependency
verification metadata; it did **not** write new verification metadata or locks.

**BUILD SUCCESSFUL in 2m 53s; 58 actionable tasks: 30 executed, 28 up-to-date.**

| Check | Observed result |
| --- | --- |
| `:protocol:test` | 62 tests, 0 failures, 0 errors, 0 skipped |
| `:app:testDebugUnitTest` | 7 tests, 0 failures, 0 errors, 0 skipped |
| Total JVM tests | **69 passed** |
| `:app:lintDebug` | **0 errors, 0 issue warnings**; empty lint issues XML |
| Kotlin compilation | Passed, no Kotlin compiler warnings in the final run |
| `:app:assembleDebug` | Passed; real locally debug-signed APK produced |
| `git diff --check` | Passed |
| APK signature verification with JDK 17 / Build Tools 35.0.0 | Passed, APK Signature Scheme v2, one debug signer |
| `aapt2 dump badging` | Confirmed application ID, version, min/target SDK, label, permissions |
| Live-device checks / emulator / instrumentation | **Not run; no device authorization** |

Build-tool informational warnings remain:

- SDK platform-tools is not installed. This gate does not need it; it was not
  installed or downloaded to satisfy the warning.
- Android lint treats the pure JVM `:protocol` module as an external dependency
  rather than Android-linting it. Its runtime code is compiled and tested by
  `:protocol:test`. No new lint plugin or baseline was added.

The gate is a targeted debug-build validation. A release build, store signing,
`connectedAndroidTest`, generic full `build`, and any installation task were
not run. The compiler and lint checks do not establish device compatibility.

## Test inventory

| Runtime-shared component or policy | Tests |
| --- | ---: |
| Capture/encode sizing, nested letterboxing, coordinates and rotation geometry | 7 |
| Host input grant/order, TTL, held-finger bounds, completion idempotence | 8 |
| Invitation format, one-use admission, expiry, revocation and redaction | 8 |
| Literal private IPv4 and on-link subnet restrictions | 4 |
| Client stop-and-wait touch order, MOVE coalescing, release and timeout | 6 |
| Bounded outbound queue and session-fence lifecycle | 6 |
| Main-thread input receipt age and closed/obsolete connection rejection | 3 |
| Real local TLS 1.3 sockets, pin rejection, token rejection and encrypted framing | 5 |
| Fake-device runtime decoder pump, delayed output, in-flight limits and cleanup | 6 |
| Wire round trips, fragmentation, truncation, lengths, UTF-8 and invalid packets | 9 |
| Manifest, permissions, Accessibility scope, backup, exported entry points and UI-state policy | 6 |
| AndroidKeyStore digest authorization policy for Conscrypt | 1 |
| **Total** | **69** |

The TLS tests use freshly generated, temporary JDK EC fixture identities and
loopback-only sockets. They execute the same pin checker, pairing handshake,
framing, queue and connection workers used by the app. Fixtures are removed
after the tests. They are **not** evidence that AndroidKeyStore-backed Conscrypt
TLS works on the S10/A06.

Decoder tests run the actual pure Kotlin input/output pump against a fake
codec. They prove queue/order/deadline behavior, not hardware H.264 decoding,
Surface lifecycle, image quality, crop correctness on Samsung, or latency.

The key-policy test checks the actual authorization constants used by the
Android identity generator. No AndroidKeyStore key was generated on a phone.

## Artifact

Local deliverable: [game-controls-link-0.1.0-dev.apk](../dist/game-controls-link-0.1.0-dev.apk)

Build output: [app-debug.apk](../android/app/build/outputs/apk/debug/app-debug.apk)

| Property | Verified value |
| --- | --- |
| Application ID | `dev.gamecontrols.link` |
| Label | `Game Controls Link (dev)` |
| Version | `0.1.0-dev`, versionCode `1` |
| Minimum / target / compile SDK | `31` / `36` / `36` |
| APK size | `15,806,350` bytes |
| APK SHA-256 | `a45f4b8e7f5cadcede83fea871863038e8b38affc84fc6d90f0e86499ab5cfbd` |
| Debug signing certificate SHA-256 | `b2184fcd4289cd44af4247fa3031f97129f5bcfb74e879c0a51246ac556826ca` |

The APK declares Internet, network-state, foreground-service,
media-projection foreground-service, and notification permissions. AndroidX
also merges the app-scoped
`dev.gamecontrols.link.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. No microphone,
camera, overlay, storage, location, or automatic permission-grant capability
was found in the packaged permission list.

The new debug keystore remains in this worktree's ignored
`android/.local/.android/` area. It was not copied from another workspace,
published, or committed. A matching application ID/signature is required to
update an installed app; this APK cannot overwrite Phone Stream or the other
tools merely because it was developed in the same repository.

Verification commands:

```sh
/usr/lib/jvm/java-17-openjdk/bin/java \
  -jar /home/benji/.local/share/phone-stream/android-sdk/build-tools/35.0.0/lib/apksigner.jar \
  verify --verbose --print-certs android/app/build/outputs/apk/debug/app-debug.apk

/home/benji/.local/share/phone-stream/android-sdk/build-tools/35.0.0/aapt2 \
  dump badging android/app/build/outputs/apk/debug/app-debug.apk

sha256sum android/app/build/outputs/apk/debug/app-debug.apk
git diff --check
```

An earlier invocation of the SDK `apksigner` shell launcher succeeded but chose
the system Java executable and emitted a Java native-access warning. Signature
verification was repeated directly with JDK 17 as shown above and passed
without that warning.

## Build/review issues encountered and resolved

1. Initial build refused to proceed because this workspace had no Gradle
   distribution. Only then were the approved wrapper/distribution/cache
   artifacts restored locally. No network dependency resolution was used.
2. The initial minimal dependency graph requested AndroidX SavedState's
   serialization-core `1.7.3`, which was not cached. It was constrained to the
   existing verified `1.9.0` version. Core, core-JVM, and its BOM were copied
   after those missing-dependency failures. Serialization JSON and the
   serialization compiler plugin were not introduced.
3. The first complete debug gate passed 65 JVM tests and generated the APK,
   with 11 lint issue warnings. The introduced resource/KT extension warnings
   and codec-capability nullability warnings were corrected, not baselined.
4. A read-only code review found that Conscrypt may use `NONEwithECDSA` for
   TLS signing. The Host's new key alias authorizes both `NONE` and `SHA-256`,
   and key metadata is checked rather than silently replacing a mismatched
   stored identity.
5. Review also found that an initial DOWN queued behind a blocked Host main
   thread could dispatch after the Client's ACK deadline. Input now carries
   a receipt-time bound and rechecks the exact owning connection immediately
   before dispatch. Three runtime-shared regression tests cover that case.
6. Unconfirmed gesture release now disables additional grants until the
   Accessibility service is manually restarted. Failure is visible; no dummy
   replacement touch or permission workaround is used.

## Cache provenance and isolation checks

- 1,260 initially copied dependency/cache metadata files were SHA-256 compared
  with their read-only reference counterparts. Later copied serialization core
  artifacts/BOM were byte-compared as well. This worktree uses its own writable
  Gradle home.
- The extracted Gradle 8.13 distribution was recursively byte-compared with the
  approved reference copy. The original ZIP was not present, so this is not a
  fresh independent verification of that archive's published hash.
- Wrapper JAR SHA-256:
  `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`.
- Retained Gradle distribution SHA-256:
  `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.
- Dependency verification metadata SHA-256 after the final build:
  `d873aa9211f25a64f2dacfb77c668deeadb14d6ad66d6f25bebdfde1d9b72576`;
  unchanged from the imported reference.
- No old pairing stores, app identities, private signing keys, compiled phone
  artifacts, source builds, or running processes were imported or reused.

## Remaining acceptance work

**0 device verifications performed.** Android runtime behavior remains
unverified: Host AndroidKeyStore/TLS, LAN and existing Samsung hotspot binding,
MediaProjection consent/revocation, foreground notification behavior, hardware
encode/decode, Surface rendering, actual pixel/touch alignment, Accessibility
stroke continuation/release, and lifecycle cleanup under One UI.

The slice supports **one finger**, not concurrent movement/aim/fire. The
parent's separately tested USB/scrcpy implementation and its three-finger
results do not apply to this APK. Public Accessibility cannot guarantee raw
`ACTION_CANCEL`, hardware-like input, secure-surface capture, or game acceptance.
No COD support, account safety, performance, battery, or thermal result is
claimed.

Before live checks: obtain a separate explicit device handoff to this actor,
coordinate the exclusive lease, approve this new application's installation,
and grant permissions locally. Preserve working cellular data, hotspot,
networking, S10 Console, Game Controls and Phone Stream. Use a public,
non-sensitive fixture first. See [setup and lifecycle boundaries](../README.md)
for the intended flow and detailed limitations.
