"""Owned null-sink measurement; PCM is reduced to booleans in memory, never saved."""

import array
import json
import math
import os
import selectors
import signal
import subprocess
import sys
import threading
import time


def pulse(*args):
    result = subprocess.run(["pactl", *args], capture_output=True, text=True, timeout=5)
    if result.returncode:
        raise RuntimeError("The isolated Pulse operation failed.")
    return result.stdout.strip()


def listing(kind):
    return json.loads(pulse("--format=json", "list", kind))


def window_metrics(pcm):
    samples = array.array("h")
    samples.frombytes(pcm)
    if sys.byteorder != "little":
        samples.byteswap()
    coefficient = 2 * math.cos(2 * math.pi * 997 / 48000)
    first = second = energy = 0.0
    for value in samples:
        current = value + coefficient * first - second
        second, first = first, current
        energy += value * value
    power = first * first + second * second - coefficient * first * second
    rms = math.sqrt(energy / len(samples)) if samples else 0
    ratio = 2 * power / (len(samples) * energy) if energy else 0
    return rms > 64 and ratio > 0.6, rms < 20, rms > 64


class AudioProbe:
    def __init__(self, lease_fd, cancel):
        self.name = f"s10_console_audio_test_{os.getpid()}"
        self.lease_fd, self.cancel = lease_fd, cancel
        self.module = None
        self.sink = None
        self.monitor = None
        self.process = None
        self.thread = None
        self.error = None
        self.tone_windows = self.silent_windows = self.nonzero_windows = 0
        self.frames = 0
        self.default_before = pulse("get-default-sink")

    def create(self):
        value = pulse(
            "load-module",
            "module-null-sink",
            f"sink_name={self.name}",
            "rate=48000",
            "channels=1",
            "channel_map=mono",
            "sink_properties=device.description=S10ConsolePrivateTest",
        )
        if not value.isdigit():
            raise RuntimeError("Test sink module returned no ownership identifier.")
        self.module = int(value)
        sinks = [s for s in listing("sinks") if s.get("name") == self.name]
        if len(sinks) != 1:
            raise RuntimeError("Owned null sink is unavailable.")
        self.sink = sinks[0]["index"]
        self.monitor = sinks[0]["monitor_source_name"]
        sources = [s for s in listing("sources") if s.get("name") == self.monitor]
        if len(sources) != 1 or str(sources[0].get("monitor_of_sink")) != str(self.sink):
            raise RuntimeError("Refusing a source that is not the owned sink's monitor.")
        if pulse("get-default-sink") != self.default_before:
            raise RuntimeError("Default output changed while creating test sink; aborting.")

    def verify_viewer_route(self, pid):
        for _ in range(20):
            matches = [
                s
                for s in listing("sink-inputs")
                if str(s.get("properties", {}).get("application.process.id")) == str(pid)
            ]
            if matches:
                if len(matches) != 1 or str(matches[0].get("sink")) != str(self.sink):
                    raise RuntimeError("Owned viewer is not exclusively routed to the test sink.")
                if matches[0].get("corked") is False:
                    return
            time.sleep(0.1)
        raise RuntimeError("Owned viewer created no identifiable Pulse playback stream.")

    def start(self):
        self.process = subprocess.Popen(
            [
                "timeout",
                "--signal=INT",
                "6",
                "parec",
                f"--device={self.monitor}",
                "--raw",
                "--format=s16le",
                "--rate=48000",
                "--channels=1",
                "--latency-msec=20",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            stdin=subprocess.DEVNULL,
            start_new_session=True,
            pass_fds=(self.lease_fd,),
        )
        self.thread = threading.Thread(target=self._measure, name="owned-null-sink-measurement")
        self.thread.start()

    def _measure(self):
        pending = bytearray()
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(self.process.stdout, selectors.EVENT_READ)
                deadline = time.monotonic() + 8
                while time.monotonic() < deadline and not self.cancel.is_set():
                    events = selector.select(0.1)
                    if not events and self.process.poll() is not None:
                        break
                    for key, _ in events:
                        data = os.read(key.fd, 16384)
                        if not data:
                            return
                        pending.extend(data)
                        while len(pending) >= 9600:
                            block = bytes(pending[:9600])
                            del pending[:9600]
                            tone, quiet, nonzero = window_metrics(block)
                            self.frames += 4800
                            self.tone_windows += int(tone)
                            self.silent_windows += int(quiet)
                            self.nonzero_windows += int(nonzero)
                if time.monotonic() >= deadline:
                    self.error = "owned_monitor_read_deadline"
        except OSError:
            self.error = "owned_monitor_read_error"
        finally:
            pending.clear()

    def finish(self):
        self.thread.join(timeout=9)
        if self.thread.is_alive():
            raise RuntimeError("Owned monitor measurement did not finish.")
        if not self.frames and self.error is None:
            self.error = "no_frames_from_owned_monitor"
        return {
            "measured_frames": self.frames,
            "target_997hz_detected": self.tone_windows >= 3,
            "silence_control_observed": self.silent_windows >= 3,
            "nonzero_signal_observed": self.nonzero_windows >= 3,
            "measurement_error": self.error,
        }

    def close(self):
        if self.process is not None:
            if self.process.poll() is None:
                try:
                    os.killpg(self.process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
            self.process.wait(timeout=3)
            if self.thread is not None:
                self.thread.join(timeout=3)
            self.process.stdout.close()
        if self.module is not None:
            matches = [m for m in listing("modules") if m.get("index") == self.module]
            if (
                len(matches) != 1
                or matches[0].get("name") != "module-null-sink"
                or f"sink_name={self.name}" not in matches[0].get("argument", "")
            ):
                raise RuntimeError("Test module ownership changed; refusing unrelated cleanup.")
            pulse("unload-module", str(self.module))
            self.module = None
        return pulse("get-default-sink") == self.default_before
