#!/usr/bin/env python3
"""Pack a transparent clip into a ByteDance-AlphaPlayer video.

AlphaPlayer (Apache-2.0, the engine behind ByteDance Live's gift effects)
solves the one thing H.264 cannot do on its own: carry an alpha channel. It
does so by stacking the frame twice in a single video —

    +------------------+
    |   RGB (colour)   |   <- top half
    +------------------+
    |   alpha as luma  |   <- bottom half
    +------------------+

The player decodes the video with the platform's *hardware* decoder and
recombines the two halves in a fragment shader, so playback costs almost no
CPU or RAM. That is what makes it the right engine for a room gift: a 16 MB
SVGA (a ZIP of 485 full PNGs, software-decoded) becomes a ~0.8 MB MP4 that
the phone's video block decodes for free.

This tool is the authoring half. It reads the SVGA clips the dashboard
already produces — SVGA is a ZIP of PNG frames, so the source artwork and its
real alpha are recovered exactly — and writes the packed MP4 plus the small
JSON descriptor AlphaPlayer's `DataSource` expects.

Usage:
    alpha_pack.py INPUT.svga -o OUT.mp4 [--crf 26] [--preset veryslow]
    alpha_pack.py INPUT.svga --json OUT.json
    alpha_pack.py --batch DIR --out-dir DIR2
"""

from __future__ import annotations

import argparse
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

import numpy as np
from PIL import Image

import imageio_ffmpeg


def ffmpeg() -> str:
    return imageio_ffmpeg.get_ffmpeg_exe()


def read_svga_frames(path: str, max_frames: int = 0) -> tuple[list[np.ndarray], float, tuple[int, int]]:
    """Recover every frame of an SVGA as RGBA, plus its fps and viewBox.

    Mirrors the Android player: one visible sprite per frame, hidden sprites
    skipped, so a round-trip through this tool preserves what a viewer saw.
    """
    with zipfile.ZipFile(path) as z:
        spec = json.loads(z.read("movie.spec"))
        names = set(z.namelist())
        images: dict[str, Image.Image] = {}
        for key, stem in spec.get("images", {}).items():
            for cand in (f"{stem}.png", f"{stem}.jpg", stem):
                if cand in names:
                    images[key] = Image.open(io.BytesIO(z.read(cand))).convert("RGBA")
                    break
        w = int(spec["movie"]["viewBox"]["width"])
        h = int(spec["movie"]["viewBox"]["height"])
        fps = float(spec["movie"].get("fps", 24.0))
        n = int(spec["movie"]["frames"])
        if max_frames:
            n = min(n, max_frames)

        frames: list[np.ndarray] = []
        for fi in range(n):
            canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
            for sp in spec.get("sprites", []):
                key = sp.get("imageKey")
                sprites = sp.get("frames", [])
                if key not in images or fi >= len(sprites):
                    continue
                fr = sprites[fi]
                if fr.get("alpha", 0.0) <= 0.0:
                    continue
                img = images[key]
                lay = fr.get("layout") or {}
                lw, lh = lay.get("width"), lay.get("height")
                if lw and lh and (int(lw), int(lh)) != img.size:
                    img = img.resize((int(lw), int(lh)), Image.LANCZOS)
                x = int(lay.get("x", 0))
                y = int(lay.get("y", 0))
                if fr.get("alpha", 1.0) < 1.0:
                    img = img.copy()
                    a = np.array(img)
                    a[..., 3] = (a[..., 3] * fr["alpha"]).astype(np.uint8)
                    img = Image.fromarray(a, "RGBA")
                canvas.alpha_composite(img, (x, y))
            frames.append(np.array(canvas))
        return frames, fps, (w, h)


def pack_frame(fr: np.ndarray) -> np.ndarray:
    """RGB on top, alpha replicated across three channels below.

    The alpha strip is grayscale rather than a single channel so the 4:2:0
    chroma subsampling H.264 applies cannot smear the alpha edge — the same
    reason AlphaPlayer's own converter repeats it.
    """
    rgb = fr[..., :3]
    alpha = fr[..., 3:4]
    return np.concatenate([rgb, np.repeat(alpha, 3, axis=2)], axis=0)


def write_frames(frames: list[np.ndarray], out_dir: str) -> None:
    for i, fr in enumerate(frames):
        Image.fromarray(pack_frame(fr), "RGB").save(os.path.join(out_dir, f"{i:05d}.png"))


def encode(frames_dir: str, out_mp4: str, fps: float, crf: int, preset: str) -> None:
    cmd = [
        ffmpeg(), "-y", "-hide_banner", "-loglevel", "error",
        "-framerate", f"{fps:g}",
        "-i", os.path.join(frames_dir, "%05d.png"),
        "-c:v", "libx264",
        "-pix_fmt", "yuv420p",
        "-preset", preset,
        "-crf", str(crf),
        # Profile high / level 4.0 is decodable by every phone from ~2012 on,
        # which is the compatibility floor a gift must clear.
        "-profile:v", "high", "-level", "4.0",
        "-movflags", "+faststart",
        out_mp4,
    ]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"ffmpeg failed: {r.stderr[:500]}")


def pack_one(
    src_svga: str,
    out_mp4: str,
    crf: int = 26,
    preset: str = "veryslow",
    scale: int = 0,
) -> dict:
    frames, fps, (w, h) = read_svga_frames(src_svga)
    # A clip whose every pixel is transparent packs into an all-black video
    # that the app then draws as *nothing* — a gift the viewer pays for and
    # never sees. Fail loudly, exactly like video_to_svga's coverage guard,
    # so a blank source is caught at authoring time, not in the room.
    if not frames or max(int(fr[:, :, 3].max()) for fr in frames) == 0:
        sys.exit(f"{os.path.basename(src_svga)}: fully transparent (nothing to pack)")
    if scale and scale != w:
        ratio = scale / w
        nw, nh = scale, int(round(h * ratio))
        resized = []
        for fr in frames:
            img = Image.fromarray(fr, "RGBA").resize((nw, nh), Image.LANCZOS)
            resized.append(np.array(img))
        frames, w, h = resized, nw, nh

    with tempfile.TemporaryDirectory() as td:
        write_frames(frames, td)
        encode(td, out_mp4, fps, crf, preset)

    return {
        "video": os.path.basename(out_mp4),
        "width": w,
        "height": h,
        "fps": fps,
        "frames": len(frames),
        "durationMs": int(round(len(frames) / fps * 1000)),
        "bytes": os.path.getsize(out_mp4),
        # AlphaPlayer reads the two stacked halves from one video; the
        # descriptor records the split so the client never guesses.
        "alpha": {"layout": "vertical-rgb-alpha", "sourceHeight": h},
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("input", nargs="?")
    ap.add_argument("-o", "--output")
    ap.add_argument("--json")
    ap.add_argument("--crf", type=int, default=26)
    ap.add_argument("--preset", default="veryslow")
    ap.add_argument("--scale", type=int, default=0)
    ap.add_argument("--batch")
    ap.add_argument("--out-dir")
    args = ap.parse_args()

    if args.batch:
        out_dir = args.out_dir or os.path.join(args.batch, "alpha")
        os.makedirs(out_dir, exist_ok=True)
        manifest = {}
        skipped = {}
        for name in sorted(os.listdir(args.batch)):
            if not name.endswith(".svga"):
                continue
            stem = name[:-5]
            out_mp4 = os.path.join(out_dir, f"{stem}.mp4")
            try:
                info = pack_one(os.path.join(args.batch, name), out_mp4, args.crf, args.preset, args.scale)
            except SystemExit as e:
                # One blank clip must not abandon the rest of the batch.
                skipped[stem] = str(e)
                print(f"{stem}: SKIPPED ({e})", file=sys.stderr)
                continue
            manifest[stem] = info
            print(f"{stem}: {info['bytes'] / 1048576:.2f} MB  {info['width']}x{info['height']} {info['durationMs']} ms")
        with open(os.path.join(out_dir, "manifest.json"), "w") as f:
            json.dump({"packed": manifest, "skipped": skipped}, f, indent=2)
        if skipped:
            print(f"{len(skipped)} clip(s) skipped: {', '.join(skipped)}", file=sys.stderr)
        return 0

    if not args.input or not args.output:
        sys.exit("need INPUT and -o OUTPUT (or --batch DIR)")

    info = pack_one(args.input, args.output, args.crf, args.preset, args.scale)
    print(f"{os.path.basename(args.output)}: {info['bytes'] / 1048576:.2f} MB  "
          f"{info['width']}x{info['height']} {info['frames']}f {info['durationMs']} ms")
    if args.json:
        with open(args.json, "w") as f:
            json.dump(info, f, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
