import array
import math
import runpy
from pathlib import Path


def test_in_memory_tone_detector_distinguishes_frequency_and_silence():
    module = runpy.run_path(str(Path(__file__).parents[1] / "fixtures/clipboard/audio_probe.py"))
    metrics = module["window_metrics"]
    tone = array.array(
        "h", (int(3000 * math.sin(2 * math.pi * 997 * index / 48000)) for index in range(4800))
    )
    other = array.array(
        "h", (int(3000 * math.sin(2 * math.pi * 440 * index / 48000)) for index in range(4800))
    )
    assert metrics(tone.tobytes()) == (True, False, True)
    assert metrics(other.tobytes()) == (False, False, True)
    assert metrics(bytes(9600)) == (False, True, False)
