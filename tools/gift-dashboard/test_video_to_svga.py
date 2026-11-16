#!/usr/bin/env python3
"""Regression tests for the SVGA authoring tool's blank-clip guard.

Run:  /workspace/venv/bin/python tools/gift-dashboard/test_video_to_svga.py

A clip whose pixels are all transparent renders nothing in the room, yet
it still charges the sender. Gift 2585 shipped exactly like that: the
source SVGA had 96 fully-transparent frames, and the coverage guard was
skipped whenever the source already had alpha (`mode == "none"`). These
tests pin the guard to the whole clip, not just the keyed path.
"""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import video_to_svga as v2s  # noqa: E402


def write_svga(path: Path, frames: list[np.ndarray], fps: float = 12.0) -> None:
    v2s.build_svga(frames, str(path), fps, frames[0].shape[0], quantize=False, colors=256)


def blank_frames(n: int = 4, size: int = 64) -> list[np.ndarray]:
    # RGBA, every pixel fully transparent.
    return [np.zeros((size, size, 4), dtype=np.uint8) for _ in range(n)]


def visible_frames(n: int = 4, size: int = 64) -> list[np.ndarray]:
    f = np.zeros((size, size, 4), dtype=np.uint8)
    f[size // 4 : 3 * size // 4, size // 4 : 3 * size // 4] = [255, 60, 60, 255]
    return [f.copy() for _ in range(n)]


class CoverageTest(unittest.TestCase):
    def test_blank_frames_report_zero_coverage(self):
        self.assertEqual(0.0, v2s.subject_coverage(blank_frames()))

    def test_visible_frames_report_coverage(self):
        self.assertGreater(v2s.subject_coverage(visible_frames()), 0.05)


class PassthroughGuardTest(unittest.TestCase):
    """The CLI must refuse to reproduce a blank clip."""

    def run_tool(self, src: Path, out: Path) -> int:
        proc = subprocess.run(
            [sys.executable, str(HERE / "video_to_svga.py"), str(src), "--passthrough", "-o", str(out)],
            capture_output=True,
            text=True,
        )
        return proc.returncode

    def test_blank_clip_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            src, out = Path(d) / "blank.svga", Path(d) / "out.svga"
            write_svga(src, blank_frames())
            self.assertEqual(3, self.run_tool(src, out))
            self.assertFalse(out.exists(), "a rejected clip must not be written")

    def test_visible_clip_is_written(self):
        with tempfile.TemporaryDirectory() as d:
            src, out = Path(d) / "good.svga", Path(d) / "out.svga"
            write_svga(src, visible_frames())
            self.assertEqual(0, self.run_tool(src, out))
            self.assertTrue(out.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
