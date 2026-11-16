#!/usr/bin/env python3
"""Regression tests for the edge-blending half of video_to_svga.

Run:  /workspace/venv/bin/python test_video_to_svga.py

These pin down the "no visible edge" behaviour an operator relies on when
they upload a video without background removal: the clip must reach full
transparency on its own outline (rounded corners, no straight edge), while a
keyed cutout must keep its own silhouette untouched.
"""

from __future__ import annotations

import unittest
from pathlib import Path
from unittest import mock

import numpy as np
from PIL import Image

import video_to_svga as V

# The app repo, so a test can pin a value that lives in the Android renderer.
REPO = Path(__file__).resolve().parent.parent / "astro-backup"


def _find_renderer() -> Path:
    """Locate AlphaVideoRenderer.kt whether the tests run beside the repo
    (`../astro-backup`) or from inside it (`tools/gift-dashboard`)."""
    rel = Path("amethyst/src/main/java/com/vitorpamplona/amethyst/ui/screen/loggedIn/nests/room/gifts/AlphaVideoRenderer.kt")
    for base in (REPO, *REPO.resolve().parents):
        candidate = base / rel
        if candidate.exists():
            return candidate
    return REPO / rel


def _opaque_block(size: int = 200) -> list[np.ndarray]:
    """A fully opaque square, as a no-removal upload produces."""
    f = np.zeros((size, size, 4), np.uint8)
    f[..., :3] = 120
    f[..., 3] = 255
    return [f.copy() for _ in range(3)]


def _cutout(size: int = 200) -> list[np.ndarray]:
    """A disc on a transparent field, as a keyed gift produces."""
    yy, xx = np.mgrid[0:size, 0:size]
    disc = (xx - size / 2) ** 2 + (yy - size / 2) ** 2 < (size * 0.35) ** 2
    f = np.zeros((size, size, 4), np.uint8)
    f[disc] = [40, 80, 240, 255]
    return [f.copy() for _ in range(3)]


class OpaqueRectangleDetection(unittest.TestCase):
    def test_opaque_block_is_detected(self):
        frames = _opaque_block()
        self.assertTrue(V._is_opaque_rectangle(frames, 0, 0, 199, 199))

    def test_cutout_is_not_detected(self):
        frames = _cutout()
        ys, xs = np.where(frames[0][..., 3] > 24)
        self.assertFalse(
            V._is_opaque_rectangle(frames, int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max()))
        )


class BlendIntoRoom(unittest.TestCase):
    def test_blends_opaque_block(self):
        frames = _opaque_block()
        self.assertTrue(V.blend_into_room(frames))

    def test_reaches_zero_alpha_at_the_outline(self):
        frames = _opaque_block()
        V.blend_into_room(frames)
        a = frames[0][..., 3].astype(np.float32)
        ys, xs = np.where(a > 12)
        x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
        for corner in ((y0, x0), (y0, x1), (y1, x0), (y1, x1)):
            self.assertLess(
                int(a[corner]),
                40,
                "a corner of an opaque clip must be near-transparent, not a hard edge",
            )

    def test_has_no_hard_border(self):
        frames = _opaque_block()
        V.blend_into_room(frames)
        a = frames[0][..., 3].astype(np.float32)
        gx = np.abs(np.diff(a, axis=1))
        gy = np.abs(np.diff(a, axis=0))
        # A hard rectangle shows a single-pixel jump of ~255. The blend must
        # ramp instead, so no step may exceed a quarter of the range.
        self.assertLess(int(max(gx.max(), gy.max())), 64)

    def test_keeps_an_opaque_core(self):
        frames = _opaque_block()
        V.blend_into_room(frames)
        a = frames[0][..., 3]
        self.assertGreater(int(a[100, 100]), 200, "the centre must stay opaque")

    def test_cutout_keeps_its_silhouette(self):
        frames = _cutout()
        before = frames[0][..., 3].copy()
        self.assertFalse(V.blend_into_room(frames), "a cutout must not be blended")
        np.testing.assert_array_equal(frames[0][..., 3], before)

    def test_ramp_is_smooth_monotonic_inward(self):
        frames = _opaque_block()
        V.blend_into_room(frames)
        a = frames[0][..., 3].astype(np.float32)
        row = a[100]  # through the centre of the block
        ys, xs = np.where(a > 12)
        x0, x1 = xs.min(), xs.max()
        profile = row[x0 : x1 + 1]
        diffs = np.diff(profile)
        # Rising edge from the left: alpha must not step backwards inside the
        # fade, which would read as a band.
        self.assertGreaterEqual(int(diffs[:20].min()), -8)


class EdgeFadeDecision(unittest.TestCase):
    """`--no-blend` must leave the clip's own edge alone. A leftover default
    fade reads as a hidden border around a scene the operator asked to keep
    hard-edged, so the ramp has to be fully off in that case."""

    def test_no_blend_disables_the_default_ramp(self):
        self.assertIsNone(V.resolve_edge_fade(no_blend=True, no_edge_fade=False, edge_fade=0.0))

    def test_default_ramp_applies_while_blending(self):
        self.assertEqual(V.resolve_edge_fade(False, False, 0.0), 0.10)

    def test_explicit_fade_wins_over_no_blend(self):
        self.assertEqual(V.resolve_edge_fade(True, False, 0.25), 0.25)

    def test_no_edge_fade_always_disables(self):
        self.assertIsNone(V.resolve_edge_fade(False, True, 0.25))


class FrameBudget(unittest.TestCase):
    """The whole clip must survive unless it is genuinely capped. A bare
    frame count used to cut a longer scene mid-way, which read as truncation."""

    def test_keeps_the_whole_clip_by_default(self):
        self.assertEqual(V.resolve_frame_budget(0, 0.0, 24.0), V.SAFETY_MAX_FRAMES)

    def test_time_cap_becomes_a_frame_count(self):
        self.assertEqual(V.resolve_frame_budget(0, 8.0, 24.0), 192)

    def test_time_cap_overrides_a_stale_frame_cap(self):
        self.assertEqual(V.resolve_frame_budget(96, 8.0, 24.0), 96)
        self.assertEqual(V.resolve_frame_budget(0, 20.0, 24.0), 480)

    def test_explicit_frame_cap_is_honoured(self):
        self.assertEqual(V.resolve_frame_budget(120, 0.0, 24.0), 120)


class ProbeStreamSelection(unittest.TestCase):
    """`probe` must read the clip's own video stream, not an attached cover
    image. A cover is often 16:9, so trusting it squashed a vertical clip
    into a landscape box — the "squashed / bordered" complaint."""

    def _probe(self, text):
        with mock.patch.object(V, "ffmpeg", return_value="ffmpeg"), \
             mock.patch.object(V.subprocess, "run") as run:
            run.return_value = mock.Mock(stderr=text)
            return V.probe("x.mp4")

    def test_ignores_an_attached_cover_image(self):
        text = (
            "  Duration: 00:00:16.81, start: 0.000000, bitrate: 1869 kb/s\n"
            "  Stream #0:0: Video: h264 (Main), yuv420p, 720x960 [SAR 1:1], 30 fps, 30 tbr\n"
            "  Stream #0:1: Audio: aac (LC), 44100 Hz, stereo\n"
            "  Stream #0:2: Video: mjpeg, yuvj420p, 1280x720 [SAR 1:1], 90k tbr (attached pic)\n"
        )
        info = self._probe(text)
        self.assertEqual(info["size"], (720, 960))
        self.assertEqual(info["fps"], 30.0)

    def test_first_video_stream_wins(self):
        text = (
            "  Stream #0:0: Video: h264, yuv420p, 720x960, 30 fps, 30 tbr\n"
            "  Stream #0:1: Video: h264, yuv420p, 1280x720, 60 fps, 60 tbr\n"
        )
        self.assertEqual(self._probe(text)["size"], (720, 960))

    def test_fps_parses_through_a_trailing_comma(self):
        text = "  Stream #0:0: Video: h264, yuv420p, 640x480, 25 fps, 25 tbr\n"
        self.assertEqual(self._probe(text)["fps"], 25.0)


class CoverFit(unittest.TestCase):
    """`cover` fills the room's screen edge to edge with no transparent
    margin, cropping the overflow — a full-screen takeover."""

    def test_cover_size_is_even_and_matches_room_aspect(self):
        w, h = V.cover_size(480, 9 / 16)
        self.assertEqual((w, h), (270, 480))
        self.assertEqual(w % 2, 0)
        self.assertEqual(h % 2, 0)

    def test_cover_size_landscape_room(self):
        self.assertEqual(V.cover_size(480, 16 / 9), (480, 270))

    def test_fit_cover_has_no_transparent_margin(self):
        src = Image.new("RGBA", (720, 960), (255, 0, 0, 255))
        out = V.fit_cover(src, 270, 480)
        self.assertEqual(out.size, (270, 480))
        alpha = np.array(out)[..., 3]
        self.assertTrue((alpha == 255).all())

    def test_fit_cover_crops_rather_than_letterboxes(self):
        # A square source into a tall box must crop left/right, not add bars.
        src = Image.new("RGBA", (400, 400), (0, 0, 255, 255))
        out = V.fit_cover(src, 200, 400)
        self.assertEqual(out.size, (200, 400))
        self.assertTrue((np.array(out)[..., 3] == 255).all())

    def test_fit_box_still_letterboxes(self):
        # The `aspect` mode must keep its transparent margin (no regression).
        src = Image.new("RGBA", (400, 400), (0, 255, 0, 255))
        out = V.fit_box(src, 200, 400)
        alpha = np.array(out)[..., 3]
        self.assertTrue((alpha == 0).any())


class OpaqueCoverageGuard(unittest.TestCase):
    """The mirror of the subject-coverage guard. An un-keyed upload keeps its
    own background, so with edge-blending off it would ship as a solid
    rectangle that hides the room — the exact black-box bug. `opaque_coverage`
    is the number that catches it before publish."""

    def test_opaque_block_reads_fully_opaque(self):
        self.assertAlmostEqual(V.opaque_coverage(_opaque_block()), 1.0, places=3)

    def test_cutout_reads_mostly_transparent(self):
        # A disc covers ~38% of the frame, so opaque coverage is well under half.
        self.assertLess(V.opaque_coverage(_cutout()), 0.5)

    def test_empty_clip_is_not_opaque(self):
        blank = np.zeros((40, 40, 4), np.uint8)
        self.assertEqual(V.opaque_coverage([blank.copy()]), 0.0)

    def test_default_threshold_flags_a_solid_clip(self):
        self.assertGreater(V.opaque_coverage(_opaque_block()), 0.97)

    def test_a_cutout_passes_the_same_threshold(self):
        self.assertLessEqual(V.opaque_coverage(_cutout()), 0.97)


class VerticalClipFill(unittest.TestCase):
    """A vertical clip is a near-full-height takeover (about 95% of the phone
    height), not a small centred box. The renderer's VERTICAL_FILL pins this;
    the dashboard's preview mirrors it with a 95% cover box."""

    def test_renderer_fills_ninety_five_percent_of_height(self):
        src = _find_renderer()
        text = src.read_text()
        self.assertIn("VERTICAL_FILL = 0.95f", text)

    def test_vertical_fill_crops_rather_than_letterboxes(self):
        # A tall clip into a taller phone box: filling the height at 95% and
        # covering the width means the horizontal overflow is cropped, so the
        # frame carries no transparent side bars.
        src = Image.new("RGBA", (360, 480), (0, 0, 0, 0))
        src.paste((255, 0, 0, 255), (0, 0, 360, 480))
        out = V.fit_cover(src, 300, 540)
        self.assertEqual(out.size, (300, 540))
        self.assertTrue((np.array(out)[..., 3] == 255).all())


class BirefnetMemoryGate(unittest.TestCase):
    """BiRefNet peaks near 7 GiB, which is enough to OOM-kill a shared box and
    take a whole conversion (and the session running it) down with it. The
    gate must refuse the run when there is not enough free RAM, so callers fall
    back to the colour key instead of crashing."""

    def test_refuses_when_memory_is_tight(self):
        with mock.patch.object(V, "_available_mb", return_value=4000):
            self.assertFalse(V.birefnet_affordable("birefnet-general-lite"))

    def test_allows_when_memory_is_plentiful(self):
        with mock.patch.object(V, "_available_mb", return_value=14000):
            self.assertTrue(V.birefnet_affordable("birefnet-general-lite"))

    def test_requires_headroom_above_the_peak(self):
        # Exactly at the peak leaves no headroom, so it must still refuse.
        with mock.patch.object(V, "_available_mb", return_value=V.BIREFNET_PEAK_MB):
            self.assertFalse(V.birefnet_affordable("birefnet-general-lite"))

    def test_available_mb_reads_meminfo(self):
        # A real read of the host: a sane box reports a positive, finite value.
        self.assertGreater(V._available_mb(), 0)


if __name__ == "__main__":
    unittest.main()
