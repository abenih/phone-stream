# Retained audio-analysis test helper

`audio_probe.py` is original project fixture source, retained because
`tests/test_audio_probe.py` exercises its `window_metrics()` function with
generated in-memory PCM. Importing the module does not start a process or capture
audio. The unit test does not instantiate `AudioProbe`.

The optional `AudioProbe` class controls a separately owned PulseAudio test sink
and monitor. It is not an application feature or permission to run an audio test.
Do not invoke it against real audio as part of source verification.

The Android clipboard fixture, SDK build tooling, device-driving integration
harness, APKs, recordings, and private results are intentionally not included in
this publication. Historical integration observations in the acceptance guide
are bounded evidence, not tests reproduced by this source-only unit suite.
