# phone-stream

This repository currently contains **[S10 Console](s10-console/README.md)**, a
USB-first Linux desktop manager for official scrcpy. It targets an explicitly
enrolled Samsung Galaxy S10 SM-G973F / Android 12 and uses a separate native
viewer. It does not require a companion APK or a cloud service.

This first publication is the **reviewed original S10 Console baseline
snapshot**, selected from its owner's stable source and verified offline. It is
not the latest complete final-verification branch: all later, unconfirmed
final-verifier changes and results are omitted.

The older bidirectional PhoneStream experiment, unfinished repairs, hotspot
gateway/provider integrations, and live-device test harnesses are not part of
this source snapshot. Runtime device identities, credentials, media, local
configuration, and generated distributions are intentionally excluded.

## Install and use

Prerequisites: Linux, Python 3.12-3.14, [uv](https://docs.astral.sh/uv/), official
scrcpy 4.1 (or capability-compatible 4.x), ADB, and GNU coreutils `stdbuf`.
The development host used Arch's `scrcpy` and `android-tools` packages. Other
Linux distributions must provide equivalent dependencies; they are not bundled.

```sh
git clone https://github.com/abenih/phone-stream.git
cd phone-stream/s10-console
uv sync --locked
uv run s10-console
```

Opening the console is idle: it does not start capture or inspect phones.
Follow the [preparation guide](s10-console/docs/preparation.md) to authorize,
verify, and enroll your own phone before choosing **Start control**. Coordinate
exclusive device ownership with other tools first; never interrupt a working
hotspot or tethered connection to bypass a busy lease.

See the [application README](s10-console/README.md) for wheel builds, a user-local
desktop launcher, and detailed controls. Dependencies remain separately installed;
their [upstream references and licensing](s10-console/docs/third-party.md) are
preserved. No new open-source license is granted for this project's own code.

## Test without a phone

```sh
cd s10-console
uv sync --locked
uv run ruff check src tests
uv run mypy
uv run pytest -q
```

The suite uses synthetic identities, fake ADB responses, temporary lease
directories, owned test subprocesses, and offscreen Qt. It does not establish
live hardware acceptance. The retained audio-analysis helper is exercised only
with generated in-memory samples, never real audio.

## Known limitations

USB screen, mouse, and keyboard use have prior owner-reported evidence.
Controlled X11 clipboard results do **not** confirm native Wayland clipboard
behavior. Enabled-lock/dead-screen recovery, reboot and no-touch reconnect,
wireless recovery, physical display power, and complete audio behavior are not
guaranteed. There is no credential, secure-content, or authorization bypass.

The [acceptance matrix](s10-console/docs/acceptance.md) separates implementation,
offline checks, historical observations, and unverified scenarios. This source
publication itself performs no phone, microphone, clipboard, or network-setting
operations.
