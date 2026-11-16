#!/usr/bin/env python3
"""Convert a video / GIF / animated image into a transparent SVGA v2 gift clip.

The room gift catalog plays SVGA clips full-screen (see SvgaGiftPlayer), and
SVGA carries real PNG alpha. So the job is: decode the source, key its flat
background out to alpha, clean that matte up, crop to the subject so it fills
the frame, fit it into a square viewBox without distorting it, and write the
clip in the same authorable ZIP form as the bundled `gen_*.svga` files.

Background removal is the part that decides whether the gift looks premium, so
it is a real pipeline rather than a single threshold:

  1. Detect the backdrop from the frame border (black, green, blue, white or an
     arbitrary solid colour) with a median, so a moving subject that grazes the
     edge does not skew it.
  2. Key to alpha - luma for dark backdrops (keeps glows bright), colour
     distance for coloured ones, with spill suppression on the edges.
  3. Clean the matte: drop specks, fill holes, smooth the contour.
  4. Stabilise over time with a temporal median, so the alpha does not crawl
     frame to frame.
  5. Crop to a single fixed box (the union of every frame's subject) and fit a
     transparent square, so the clip does not jitter or distort.

A source that already carries real alpha (APNG/WebP/many GIFs) is passed
through untouched - keying it would punch holes in the subject.

Usage:
  video_to_svga.py INPUT -o OUT.svga [--bg auto|black|green|blue|white|none|#RRGGBB]
                  [--matte auto|key|birefnet] [--fit square|aspect] [--no-crop]
                  [--size 480] [--fps 24] [--max-seconds 15]
                  [--icon OUT.png] [--preview SHEET.png]
                  [--preview-video OUT.webm] [--preview-mp4 OUT.mp4]
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
import zipfile

import numpy as np
from PIL import Image, ImageFilter

import imageio_ffmpeg

# Keep rembg's model cache in the workspace, not `$HOME`. The home directory
# is wiped on a sandbox restart, which silently deleted the 214 MB BiRefNet
# weights and dropped every matte back to the colour key. `/workspace`
# persists, so the model survives and is downloaded only once.
os.environ.setdefault("REMBG_HOME", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".rembg"))
os.environ["REMBG_HOME"] = os.path.abspath(os.environ["REMBG_HOME"])

try:
    from scipy import ndimage as ndi

    HAVE_SCIPY = True
except Exception:  # pragma: no cover - scipy is an optional quality boost
    HAVE_SCIPY = False


def resolve_edge_fade(no_blend: bool, no_edge_fade: bool, edge_fade: float) -> float | None:
    """Ramp width for the border fade, or None to leave the clip's edges alone.

    `--no-blend` must genuinely keep the clip's own edge: if the operator
    asked for a hard edge, a leftover default fade would still show as a
    faint border around the scene. So the default ramp applies only while
    blending is on; an explicit `--edge-fade` always wins, and
    `--no-edge-fade` always disables.
    """
    if no_edge_fade:
        return None
    if no_blend and not edge_fade:
        return None
    return edge_fade or 0.10


def resolve_frame_budget(max_frames: int, max_seconds: float, fps: float) -> int:
    """Frames to decode, honouring a time cap over a raw frame count.

    A frame cap alone truncated a longer clip at an arbitrary frame, which
    read as a cut-off scene; a time cap keeps the scene whole and only
    shortens it when it is genuinely too long. With neither, the whole clip
    is kept, bounded only by the safety ceiling.
    """
    budget = max_frames
    if max_seconds and max_seconds > 0:
        by_time = max(1, int(round(max_seconds * fps)))
        budget = by_time if budget <= 0 else min(budget, by_time)
    if budget <= 0:
        budget = SAFETY_MAX_FRAMES
    return budget


def ffmpeg() -> str:
    return imageio_ffmpeg.get_ffmpeg_exe()


# Upper bound on decoded frames when no time cap is given, so a pathological
# upload cannot exhaust memory while a normal gift still keeps its whole clip.
SAFETY_MAX_FRAMES = 2400  # 100 s at 24 fps


def probe(path: str) -> dict:
    out = subprocess.run(
        [ffmpeg(), "-hide_banner", "-i", path], capture_output=True, text=True
    ).stderr
    info = {"raw": out}
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("Duration:"):
            info["duration"] = line.split(",")[0].split("Duration:")[1].strip()
        # Only the first *real* video stream describes the clip. A file can
        # also carry an attached cover image (a `mjpeg`/"attached pic" stream,
        # typically 16:9); reading that one reports the wrong dimensions and
        # squashes a vertical clip into a landscape box.
        if "Video:" in line and "Stream" in line:
            if info.get("size") is not None:
                continue
            if "attached pic" in line or "mjpeg" in line.lower():
                continue
            info["stream"] = line
            for tok in line.replace(",", " ").split():
                if "x" in tok and tok.split("x")[0].isdigit():
                    w, h = tok.split("x")[:2]
                    if w.isdigit() and h.isdigit():
                        info["size"] = (int(w), int(h))
                        break
            toks = line.split()
            for i, tok in enumerate(toks):
                bare = tok.rstrip(",")
                if bare == "fps" and i:
                    try:
                        info["fps"] = float(toks[i - 1])
                    except ValueError:
                        pass
                elif bare.endswith("fps") and bare[:-3]:
                    try:
                        info["fps"] = float(bare[:-3])
                    except ValueError:
                        pass
    return info


def extract_frames(
    path: str,
    fps: float,
    max_frames: int,
    workdir: str,
    max_side: int = 0,
    src_size: tuple[int, int] | None = None,
) -> list[np.ndarray]:
    """Decode the source to RGBA frames at the target fps via ffmpeg.

    A 1080p source decodes to ~8 MB per RGBA frame, so a full clip held in
    memory at source resolution can reach several GB and get the process
    OOM-killed on a large upload. Since the clip is resized to `size` at the
    end anyway, the long side is capped at `max_side` during decode, which
    keeps peak memory bounded without a visible quality loss.

    `max_frames` of 0 means "every frame", which is how a whole clip is kept
    rather than truncated at an arbitrary frame count.
    """
    if path.lower().endswith(".svga"):
        return read_svga_frames(path, max_frames)
    pat = os.path.join(workdir, "f_%05d.png")
    vf = f"fps={fps}"
    if max_side and src_size:
        w, h = src_size
        long_side = max(w, h)
        if long_side > max_side:
            k = max_side / long_side
            nw = max(2, int(round(w * k)) & ~1)
            nh = max(2, int(round(h * k)) & ~1)
            vf += f",scale={nw}:{nh}"
    cmd = [
        ffmpeg(), "-y", "-hide_banner", "-loglevel", "error",
        "-i", path,
        # Force the first video stream: without this, ffmpeg's default
        # selection can pick an attached cover image (higher resolution),
        # decoding the wrong, landscape frame for a vertical clip.
        "-map", "0:v:0",
        "-vf", vf,
    ]
    if max_frames > 0:
        cmd += ["-frames:v", str(max_frames)]
    cmd += ["-pix_fmt", "rgba", pat]
    subprocess.run(cmd, check=True)
    files = sorted(f for f in os.listdir(workdir) if f.startswith("f_"))
    frames = []
    for f in files:
        with Image.open(os.path.join(workdir, f)) as im:
            frames.append(np.array(im.convert("RGBA")))
    return frames


def read_svga_frames(path: str, max_frames: int = 0) -> list[np.ndarray]:
    """Render an SVGA clip back to RGBA frames.

    An SVGA is a ZIP of PNGs plus a spec, so its frames can be recovered
    exactly — which is what lets a preview be rebuilt for a clip whose source
    video is long gone (including the bundled clips). Mirrors the Android
    player: one visible sprite per frame, hidden sprites skipped.
    """
    import io

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
        n = int(spec["movie"]["frames"])
        if max_frames:
            n = min(n, max_frames)
        out = []
        for fi in range(n):
            canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
            for sp in spec.get("sprites", []):
                key = sp.get("imageKey")
                frames = sp.get("frames", [])
                if key not in images or fi >= len(frames):
                    continue
                fr = frames[fi]
                if fr.get("alpha", 0.0) <= 0.0:
                    continue
                img = images[key]
                lay = fr.get("layout") or {}
                lw, lh = lay.get("width"), lay.get("height")
                if lw and lh and (int(lw), int(lh)) != img.size:
                    img = img.resize((int(lw), int(lh)), Image.LANCZOS)
                alpha = fr.get("alpha", 1.0)
                if alpha < 1.0:
                    img = img.copy()
                    img.putalpha(img.getchannel("A").point(lambda v: int(v * alpha)))
                tx = int((fr.get("transform") or {}).get("tx", 0))
                ty = int((fr.get("transform") or {}).get("ty", 0))
                canvas.alpha_composite(img, (tx, ty))
            out.append(np.array(canvas))
        return out


def _smoothstep(x: np.ndarray, lo: float, hi: float) -> np.ndarray:
    t = np.clip((x - lo) / max(hi - lo, 1e-6), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def has_real_alpha(frames: list[np.ndarray], min_frac: float = 0.01) -> bool:
    """True when the source already carries transparency we should keep."""
    for fr in frames[:: max(1, len(frames) // 5)]:
        a = fr[..., 3]
        if float((a < 250).mean()) >= min_frac and a.min() < 200:
            return True
    return False


def _rembg_model_present(name: str) -> bool:
    try:
        from rembg.sessions import sessions_class
    except Exception:
        return False
    cls = next((c for c in sessions_class if c.name() == name), None)
    if cls is None:
        return False
    try:
        return cls.resolve_existing(f"{name}.onnx") is not None
    except Exception:
        return False


def birefnet_available(name: str) -> bool:
    """Whether the free BiRefNet matte can run right now.

    BiRefNet (MIT, shipped in the `rembg` project's GitHub releases) is a
    semantic segmenter: it finds the subject as an object rather than by
    colour, so a dark subject on a dark backdrop survives instead of being
    keyed out with the background. It is used only when `rembg` is importable
    and the weights are already on disk, so a first run never blocks on a
    surprise download.
    """
    try:
        import rembg  # noqa: F401
    except Exception:
        return False
    return _rembg_model_present(name)


_BIREFNET_SESSIONS: dict[str, object] = {}

# One BiRefNet frame peaks near this much RSS (it always resizes to 1024x1024).
# The gate below keeps a run from pushing a shared box into swap or an OOM
# kill, which would take the whole session down mid-conversion.
BIREFNET_PEAK_MB = 7200


def _available_mb() -> float:
    """Free RAM in MiB, honouring a cgroup limit when one is set."""
    try:
        with open("/proc/meminfo") as fh:
            for line in fh:
                if line.startswith("MemAvailable:"):
                    avail = int(line.split()[1]) / 1024
                    break
            else:
                return float("inf")
    except OSError:
        return float("inf")
    for path in ("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory/memory.limit_in_bytes"):
        try:
            with open(path) as fh:
                raw = fh.read().strip()
        except OSError:
            continue
        if raw.isdigit():
            avail = min(avail, int(raw) / 1024 / 1024)
            break
    return avail


def birefnet_affordable(name: str, headroom_mb: int = 1200) -> bool:
    """Whether a BiRefNet run fits in RAM right now, with headroom to spare.

    Running it on a box that cannot hold its peak is how a conversion took the
    whole machine down, so callers fall back to the colour key when this is
    false rather than risk the kill.
    """
    return _available_mb() - BIREFNET_PEAK_MB >= headroom_mb


def _birefnet_session(name: str):
    session = _BIREFNET_SESSIONS.get(name)
    if session is None:
        import onnxruntime as ort
        from rembg.sessions import sessions_class

        # BiRefNet always resizes its input to 1024x1024, so a single frame
        # peaks around 6-7 GB of RAM with onnxruntime's defaults (its CPU
        # arena reserves the largest intermediate buffer). On a shared box
        # that can take the whole machine down, so cap threads and turn the
        # arena and memory pattern off: peak drops sharply for a modest
        # slowdown, and the weights still load only once per process.
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 2
        opts.inter_op_num_threads = 1
        opts.enable_cpu_mem_arena = False
        opts.enable_mem_pattern = False
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC

        cls = next((c for c in sessions_class if c.name() == name), None)
        if cls is None:
            raise ValueError(f"unknown rembg model: {name}")
        session = cls(name, opts)
        _BIREFNET_SESSIONS[name] = session
    return session


def matte_birefnet(
    frames: list[np.ndarray],
    model: str = "birefnet-general-lite",
) -> list[np.ndarray]:
    """Alpha for every frame from BiRefNet semantic segmentation.

    One inference per frame on the CPU, reusing a single session so the
    weights load once. The mask is resized back to the decoded frame, so the
    output keeps the source size and the later crop/fit stages are unchanged.
    """
    from rembg import remove

    session = _birefnet_session(model)
    out: list[np.ndarray] = []
    total = len(frames)
    for i, fr in enumerate(frames):
        rgb = Image.fromarray(fr[..., :3], "RGB")
        mask = remove(rgb, session=session, only_mask=True)
        m = np.asarray(mask, dtype=np.uint8)
        if m.shape[:2] != fr.shape[:2]:
            m = np.asarray(
                Image.fromarray(m).resize((fr.shape[1], fr.shape[0]), Image.LANCZOS),
                dtype=np.uint8,
            )
        out.append(np.dstack([fr[..., :3], m]))
        print(f"  matte {i + 1}/{total}", flush=True)
    return out


def _border_pixels(fr: np.ndarray, band: int = 3) -> np.ndarray:
    h, w = fr.shape[:2]
    return np.concatenate(
        [
            fr[0:band].reshape(-1, 4),
            fr[h - band : h].reshape(-1, 4),
            fr[:, 0:band].reshape(-1, 4),
            fr[:, w - band : w].reshape(-1, 4),
        ]
    )


def detect_bg(frames: list[np.ndarray]) -> tuple[str, np.ndarray]:
    """Guess the backdrop from the frame border.

    Uses the median colour (robust to a subject that touches an edge) and then
    classifies it. Returns a mode plus the sampled colour, so a caller can key
    an arbitrary solid backdrop by distance rather than guessing a name.
    """
    px = np.concatenate(
        [_border_pixels(fr)[:, :3].astype(np.float32) for fr in frames[:: max(1, len(frames) // 7)]],
        axis=0,
    )
    med = np.median(px, axis=0)
    r, g, b = float(med[0]), float(med[1]), float(med[2])
    mx, mn = max(r, g, b), min(r, g, b)
    sat = mx - mn
    if g - max(r, b) > 30:
        return "green", med
    if b - max(r, g) > 30:
        return "blue", med
    if mx < 75 and sat < 45:
        return "black", med
    if mn > 180 and sat < 35:
        return "white", med
    return "color", med


def key_black(arr: np.ndarray, lo: float, hi: float) -> np.ndarray:
    """Alpha from brightness: a bright subject on black -> soft alpha; the
    colour is un-premultiplied so glows keep their true value instead of
    going dark at the edges."""
    rgb = arr[..., :3].astype(np.float32) / 255.0
    a = rgb.max(axis=2)
    alpha = _smoothstep(a, lo, hi)
    safe = np.maximum(alpha, 1e-3)[..., None]
    rgb_un = np.clip(rgb / safe, 0.0, 1.0)
    out = np.empty_like(arr, dtype=np.uint8)
    out[..., :3] = (rgb_un * 255.0 + 0.5).astype(np.uint8)
    out[..., 3] = (alpha * 255.0 + 0.5).astype(np.uint8)
    return out


def key_chroma(
    arr: np.ndarray,
    bg: np.ndarray,
    lo: float,
    hi: float,
    despill: float = 0.85,
) -> np.ndarray:
    """Key any flat backdrop by colour distance from the sampled background.

    Handles green screens, blue screens, white cycloramas and one-off studio
    colours with the same maths. The channel the backdrop leans on is then
    pulled back toward neutral in the semi-transparent band, so edges do not
    carry a coloured fringe into the app.
    """
    rgb = arr[..., :3].astype(np.float32)
    dist = np.sqrt(((rgb - bg[None, None, :]) ** 2).sum(axis=2)) / 255.0
    alpha = _smoothstep(dist, lo, hi)
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    br, bgc, bb = float(bg[0]), float(bg[1]), float(bg[2])
    edge = (1.0 - alpha) * despill
    if bgc >= br and bgc >= bb:
        excess = np.clip(g - np.maximum(r, b), 0.0, 255.0)
        g = g - excess * edge
    elif bb >= br and bb >= bgc:
        excess = np.clip(b - np.maximum(r, g), 0.0, 255.0)
        b = b - excess * edge
    elif br >= bgc and br >= bb:
        excess = np.clip(r - np.maximum(g, b), 0.0, 255.0)
        r = r - excess * edge
    out = np.empty_like(arr, dtype=np.uint8)
    out[..., 0] = np.clip(r, 0, 255).astype(np.uint8)
    out[..., 1] = np.clip(g, 0, 255).astype(np.uint8)
    out[..., 2] = np.clip(b, 0, 255).astype(np.uint8)
    out[..., 3] = (alpha * 255.0 + 0.5).astype(np.uint8)
    return out


def clean_matte(
    frames: list[np.ndarray],
    min_area_frac: float = 3e-4,
    feather: float = 0.7,
    temporal: bool = True,
    hole_fill: bool = True,
) -> None:
    """Turn a raw alpha channel into a clean, stable matte, in place.

    Specks left by a noisy key are dropped, holes inside the subject are
    filled, the contour is smoothed, and a temporal median stops the edge from
    crawling between frames. Everything degrades gracefully without scipy.
    """
    h, w = frames[0].shape[:2]
    min_area = max(4, int(min_area_frac * h * w))
    st = np.ones((3, 3), bool)

    for fr in frames:
        a = fr[..., 3].astype(np.float32) / 255.0
        mask = a > 0.35
        if HAVE_SCIPY:
            lab, n = ndi.label(mask)
            if n:
                sizes = np.bincount(lab.ravel())
                keep = sizes >= min_area
                keep[0] = False
                mask = keep[lab]
            if hole_fill:
                mask = ndi.binary_fill_holes(mask)
            mask = ndi.binary_opening(mask, st)
            mask = ndi.binary_closing(mask, st, iterations=2)
        a = a * mask.astype(np.float32)
        if feather > 0:
            blur = Image.fromarray((a * 255).astype(np.uint8), "L").filter(
                ImageFilter.GaussianBlur(feather)
            )
            a = np.asarray(blur, dtype=np.float32) / 255.0
        fr[..., 3] = (a * 255.0 + 0.5).astype(np.uint8)

    if temporal and len(frames) >= 3:
        stack = np.stack([f[..., 3] for f in frames], axis=0).astype(np.float32)
        sm = np.empty_like(stack)
        for i in range(len(stack)):
            lo = max(0, i - 1)
            hi = min(len(stack), i + 2)
            sm[i] = np.median(stack[lo:hi], axis=0)
        for i, fr in enumerate(frames):
            fr[..., 3] = sm[i].astype(np.uint8)


def remove_static_overlays(
    frames: list[np.ndarray],
    static_tol: float = 12.0,
    max_area_frac: float = 0.25,
    min_edge: float = 10.0,
    pad: int = 4,
) -> bool:
    """Erase burnt-in text / watermarks that sit still over a moving clip.

    The discriminator is motion. The subject moves (or changes) between frames,
    while a caption or logo is painted at fixed pixels. So a pixel that stays
    put for the whole clip *and* is still opaque after keying belongs to an
    overlay, not the gift. Pixels are grouped into connected components and a
    component is dropped only if it is text-like (has interior edges) or small,
    so a large smooth shape is never wiped out. The whole component — interior
    included — goes, since a solid caption block has no edges of its own.

    Returns True when something was removed. Refuses to erase more than
    `max_area_frac` of the frame, so a mostly-still clip cannot be destroyed by
    a bad guess.
    """
    n = len(frames)
    if n < 3:
        return False
    gray = np.stack([f[..., :3].mean(axis=2) for f in frames], axis=0).astype(np.float32)
    temporal = gray.std(axis=0)  # how much each pixel moves over time
    ref = gray[n // 2]
    alpha = frames[n // 2][..., 3]

    # Only opaque, motionless pixels are candidates; the keyed-out background
    # is already transparent and must not count as "static".
    static = (temporal < static_tol) & (alpha > 0)
    if not static.any():
        return False

    gy = np.abs(np.diff(ref, axis=0, prepend=ref[:1]))
    gx = np.abs(np.diff(ref, axis=1, prepend=ref[:, :1]))
    edge = np.sqrt(gx * gx + gy * gy)

    if HAVE_SCIPY:
        grown = ndi.binary_dilation(static, np.ones((2 * pad + 1, 2 * pad + 1), bool))
        lab, k = ndi.label(grown)
        if k == 0:
            return False
        keep = np.zeros_like(static)
        total = static.size
        h, w = static.shape[:2]
        # A caption or watermark is anchored to the frame's edge; a subject
        # that simply holds still (a posed character) sits in the middle. So
        # only a component that reaches the outer band counts as an overlay,
        # which stops a still scene from being erased.
        band = max(2, int(round(min(h, w) * 0.15)))
        for idx in range(1, k + 1):
            comp = (lab == idx) & static  # measure on the true pixels, not the dilation
            area = int(comp.sum())
            if area == 0:
                continue
            if area > max_area_frac * total:
                continue
            ys, xs = np.where(comp)
            touches_edge = ys.min() < band or ys.max() >= h - band or xs.min() < band or xs.max() >= w - band
            if not touches_edge:
                continue
            edge_energy = float(edge[comp].mean())
            small = area <= 0.02 * total
            if small or edge_energy >= min_edge:
                keep |= comp
        static = keep
    if not static.any() or static.mean() > max_area_frac:
        return False

    for fr in frames:
        fr[..., 3][static] = 0
    return True


def feather_border(
    frames: list[np.ndarray],
    width_frac: float = 0.10,
    thresh: int = 24,
) -> None:
    """Fade a clip's outer edge to transparent, in place.

    A source video (a filmed scene, a screen recording) keyed to alpha
    usually keeps an opaque rectangle right up to its content edge, so the
    gift reads as a hard-edged "screen" floating over the room instead of
    part of it. Multiplying the alpha by a smooth ramp that reaches zero at
    the content's own bounding box dissolves that edge into the room.

    The ramp is safe for a clip that already fades out on its own (a soft
    glow): the values it multiplies are already near zero there, so the
    visible core is untouched. It is anchored to the union content bbox
    (not the frame), so it lands on the real edge no matter how the clip was
    padded; `width_frac` is its width as a fraction of the content's short
    side.
    """
    xs0, ys0, xs1, ys1 = 10**9, 10**9, -1, -1
    for fr in frames:
        ys, xs = np.where(fr[..., 3] > thresh)
        if len(xs) == 0:
            continue
        xs0, xs1 = min(xs0, int(xs.min())), max(xs1, int(xs.max()))
        ys0, ys1 = min(ys0, int(ys.min())), max(ys1, int(ys.max()))
    if xs1 < 0:
        return

    h, w = frames[0].shape[:2]
    cw, ch = xs1 - xs0 + 1, ys1 - ys0 + 1
    bw = max(1, int(round(min(cw, ch) * width_frac)))
    t = np.linspace(0.0, 1.0, bw, dtype=np.float32)
    ease = t * t * (3.0 - 2.0 * t)  # 0 at the edge, 1 at the inner end

    ramp = np.ones((h, w), dtype=np.float32)
    for i in range(bw):
        v = ease[i]
        # Each band takes the lower of the two sides it is near, so the
        # corners (near two edges) fade the most and the ramp stays smooth.
        ramp[ys0 + i, xs0:xs1 + 1] = np.minimum(ramp[ys0 + i, xs0:xs1 + 1], v)
        ramp[ys1 - i, xs0:xs1 + 1] = np.minimum(ramp[ys1 - i, xs0:xs1 + 1], v)
        ramp[ys0:ys1 + 1, xs0 + i] = np.minimum(ramp[ys0:ys1 + 1, xs0 + i], v)
        ramp[ys0:ys1 + 1, xs1 - i] = np.minimum(ramp[ys0:ys1 + 1, xs1 - i], v)

    for fr in frames:
        fr[..., 3] = np.clip(fr[..., 3].astype(np.float32) * ramp, 0, 255).astype(np.uint8)


def _rounded_rect_sdf(
    h: int,
    w: int,
    x0: int,
    y0: int,
    x1: int,
    y1: int,
    radius: float,
) -> np.ndarray:
    """Signed distance to a rounded rectangle: negative inside, zero on the
    outline, positive outside. Exact, and cheap enough to build once per clip
    (the box is the union bbox, so every frame shares one field)."""
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    bx, by = (x1 - x0 + 1) / 2.0, (y1 - y0 + 1) / 2.0
    r = float(min(radius, bx, by))
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    qx = np.abs(xx - cx) - bx + r
    qy = np.abs(yy - cy) - by + r
    outside = np.sqrt(np.maximum(qx, 0.0) ** 2 + np.maximum(qy, 0.0) ** 2)
    inside = np.minimum(np.maximum(qx, qy), 0.0)
    return outside + inside - r


def _is_opaque_rectangle(
    frames: list[np.ndarray],
    x0: int,
    y0: int,
    x1: int,
    y1: int,
    thresh: int = 24,
    opaque_frac: float = 0.6,
) -> bool:
    """Whether the clip is an opaque block (a filmed scene, a screen
    recording) rather than a keyed cutout.

    A cutout's own alpha is the shape, so its bbox outline runs through both
    subject and background and is mostly transparent. An opaque block fills
    its bbox edge to edge, so the outline is almost entirely opaque. That
    difference decides whether the borderless rounded blend is needed.
    """
    band = max(2, int(round(min(x1 - x0 + 1, y1 - y0 + 1) * 0.02)))
    hits = total = 0
    for fr in frames:
        a = fr[..., 3]
        edges = (
            a[y0 : y0 + band, x0 : x1 + 1],
            a[y1 - band + 1 : y1 + 1, x0 : x1 + 1],
            a[y0 : y1 + 1, x0 : x0 + band],
            a[y0 : y1 + 1, x1 - band + 1 : x1 + 1],
        )
        for e in edges:
            hits += int((e > thresh * 4).sum())
            total += e.size
    return total > 0 and hits / total >= opaque_frac


def blend_into_room(
    frames: list[np.ndarray],
    feather_frac: float = 0.16,
    corner_frac: float = 0.10,
    thresh: int = 24,
) -> bool:
    """Dissolve an opaque clip into the room so it has no visible edge.

    A clip uploaded without background removal is an opaque rectangle; a
    plain border ramp only softens it and the room still shows a rectangular
    glow around the video. Here the alpha is feathered along a rounded-
    rectangle distance field instead, so the clip reaches full transparency
    on its own outline and the corners are rounded — there is no straight
    edge left to read as a "screen", and the scene fades into the room.

    Only applies to an opaque block (see [_is_opaque_rectangle]); a keyed
    cutout keeps its own silhouette and is left to [feather_border]. The
    field is built once from the union bbox, so the fade cannot crawl.

    Returns True when applied.
    """
    xs0, ys0, xs1, ys1 = 10**9, 10**9, -1, -1
    for fr in frames:
        ys, xs = np.where(fr[..., 3] > thresh)
        if len(xs) == 0:
            continue
        xs0, xs1 = min(xs0, int(xs.min())), max(xs1, int(xs.max()))
        ys0, ys1 = min(ys0, int(ys.min())), max(ys1, int(ys.max()))
    if xs1 < 0 or not _is_opaque_rectangle(frames, xs0, ys0, xs1, ys1, thresh):
        return False

    h, w = frames[0].shape[:2]
    short = min(xs1 - xs0 + 1, ys1 - ys0 + 1)
    feather_px = max(1.0, short * feather_frac)
    radius = short * corner_frac
    sdf = _rounded_rect_sdf(h, w, xs0, ys0, xs1, ys1, radius)
    # 0 on the outline, 1 once we are feather_px inside it. smoothstep so the
    # ramp has no visible start or end band.
    t = np.clip(-sdf / feather_px, 0.0, 1.0)
    factor = (t * t * (3.0 - 2.0 * t)).astype(np.float32)

    for fr in frames:
        fr[..., 3] = np.clip(fr[..., 3].astype(np.float32) * factor, 0, 255).astype(np.uint8)
    return True


def subject_coverage(frames: list[np.ndarray], thresh: int = 12) -> float:
    """Fraction of pixels that survived the matte, averaged over the clip.

    A blind colour key on a dark subject over a dark backdrop deletes the
    subject along with the background and leaves every frame empty. This is
    the number that catches it: a clip whose subject was really isolated
    reports a healthy fraction, one that was keyed away reports ~0.
    """
    if not frames:
        return 0.0
    step = max(1, len(frames) // 12)
    vals = [float((fr[..., 3] > thresh).mean()) for fr in frames[::step]]
    return sum(vals) / len(vals)


def opaque_coverage(frames: list[np.ndarray], thresh: int = 247) -> float:
    """Fraction of pixels that are fully opaque, averaged over the clip.

    The mirror of [subject_coverage]: a clip that was never keyed (or whose
    key failed) keeps its own background, so nearly every pixel is opaque and
    the gift paints a solid rectangle over the room. This is the number that
    catches that, so an opaque upload can never ship as a black box.
    """
    if not frames:
        return 0.0
    step = max(1, len(frames) // 12)
    vals = [float((fr[..., 3] > thresh).mean()) for fr in frames[::step]]
    return sum(vals) / len(vals)


def content_bbox(frames: list[np.ndarray], thresh: int = 12, pad_frac: float = 0.04):
    """Union bounding box of every pixel that survives the key, over all
    frames, so the subject keeps one fixed crop for the whole clip."""
    x0 = y0 = 10**9
    x1 = y1 = -1
    for fr in frames:
        ys, xs = np.where(fr[..., 3] > thresh)
        if len(xs) == 0:
            continue
        x0 = min(x0, int(xs.min()))
        x1 = max(x1, int(xs.max()))
        y0 = min(y0, int(ys.min()))
        y1 = max(y1, int(ys.max()))
    h, w = frames[0].shape[:2]
    if x1 < 0:
        return 0, 0, w, h
    pad = int(round(max(x1 - x0, y1 - y0) * pad_frac))
    x0 = max(0, x0 - pad)
    y0 = max(0, y0 - pad)
    x1 = min(w - 1, x1 + pad)
    y1 = min(h - 1, y1 + pad)
    return x0, y0, x1 + 1, y1 + 1


def cover_size(size: int, room_aspect: float) -> tuple[int, int]:
    """Even width/height for a `cover` clip, matching the room's screen shape.

    The long side is `size`, so a vertical clip stays as tall as the square
    default while reaching the room's width. Both sides are even so the SVGA
    and its H.264/VP9 preview agree exactly (those encoders require it).
    """
    aspect = room_aspect if room_aspect > 0 else 9 / 16
    if aspect <= 1.0:
        w = max(2, int(round(size * aspect)) & ~1)
        h = size if size % 2 == 0 else size - 1
    else:
        h = max(2, int(round(size / aspect)) & ~1)
        w = size if size % 2 == 0 else size - 1
    return w, h


def fit_square(img: Image.Image, size: int, pad: float = 0.0) -> Image.Image:
    """Scale the crop to fit inside a transparent square, preserving aspect
    ratio (never distort), centred.

    `pad` insets the subject by that fraction of the square on each side, so a
    clip that fills its source frame does not fill the whole room - it floats
    with a transparent margin instead.
    """
    inner = max(1, round(size * (1.0 - 2.0 * pad)))
    cw, ch = img.size
    scale = inner / max(cw, ch)
    nw, nh = max(1, round(cw * scale)), max(1, round(ch * scale))
    resized = img.resize((nw, nh), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(resized, ((size - nw) // 2, (size - nh) // 2), resized)
    return canvas


def fit_box(img: Image.Image, out_w: int, out_h: int, pad: float = 0.0) -> Image.Image:
    """Scale an image to fit inside an `out_w` x `out_h` box, preserving aspect
    ratio (never distort), centred on a transparent canvas.

    With the box matching the source aspect the image lands edge to edge, so a
    vertical scene stays a full vertical screen; a mismatched aspect leaves a
    transparent letterbox rather than a stretched picture. `pad` insets the
    image by that fraction of the box on each side.
    """
    inner_w = max(1, round(out_w * (1.0 - 2.0 * pad)))
    inner_h = max(1, round(out_h * (1.0 - 2.0 * pad)))
    cw, ch = img.size
    scale = min(inner_w / cw, inner_h / ch)
    nw, nh = max(1, round(cw * scale)), max(1, round(ch * scale))
    resized = img.resize((nw, nh), Image.LANCZOS)
    canvas = Image.new("RGBA", (out_w, out_h), (0, 0, 0, 0))
    canvas.paste(resized, ((out_w - nw) // 2, (out_h - nh) // 2), resized)
    return canvas


def fit_cover(img: Image.Image, out_w: int, out_h: int, pad: float = 0.0) -> Image.Image:
    """Scale an image to *cover* an `out_w` x `out_h` box, preserving aspect
    ratio, cropping the overflow (centred).

    This is the "fill the room" mode: the scene reaches every edge of the
    screen with no transparent letterbox, so it reads as a full-screen
    takeover rather than a picture floating over the room. The parts of the
    scene that fall outside the room's aspect are cropped, which is the point
    — the room shows through only where the matte removed the background.
    `pad` is ignored here: padding would reintroduce the empty margin this
    mode exists to remove.
    """
    cw, ch = img.size
    scale = max(out_w / cw, out_h / ch)
    nw, nh = max(1, round(cw * scale)), max(1, round(ch * scale))
    resized = img.resize((nw, nh), Image.LANCZOS)
    left = (nw - out_w) // 2
    top = (nh - out_h) // 2
    return resized.crop((left, top, left + out_w, top + out_h))


def encode_png(img: Image.Image, quantize: bool, colors: int) -> bytes:
    """PNG-encode one frame, optionally through a palette.

    A full-frame RGBA PNG of a soft glow runs 250-350 KiB; a 256-colour
    palette keeps ~78 distinct alpha steps (more than a glow needs) at about
    a quarter of the size, which is what keeps a 96-frame clip in the same
    size class as the bundled SVGA clips.
    """
    if quantize:
        img = img.quantize(colors=colors, method=Image.FASTOCTREE)
    buf = tempfile.SpooledTemporaryFile(max_size=8 << 20)
    img.save(buf, format="PNG", optimize=True)
    buf.seek(0)
    data = buf.read()
    buf.close()
    return data


def build_svga(
    frames: list[np.ndarray],
    out_path: str,
    fps: float,
    size: int | tuple[int, int],
    quantize: bool,
    colors: int,
) -> None:
    """Write an SVGA v2 ZIP clip.

    SVGA has one image per sprite and no per-frame image swap, so frame-by-
    frame animation is expressed the standard way: one sprite per frame image,
    each revealed on its own frame. Two player behaviours make this cheap:

    * `SGVADrawer.requestFrameSprites` drops any sprite whose frame alpha is
      `<= 0.0`, so a hidden sprite costs nothing to draw.
    * it also drops any sprite whose frame array is shorter than the current
      index, so a sprite only needs entries up to its own frame.

    So sprite `i` carries `i` hidden entries followed by one visible entry:
    exactly one sprite is drawn per frame, and the spec is half the size of a
    full N x N table.
    """
    n = len(frames)
    keys = [f"f{i:04d}" for i in range(n)]

    vw, vh = (size, size) if isinstance(size, int) else (int(size[0]), int(size[1]))
    identity = {"a": 1.0, "b": 0.0, "c": 0.0, "d": 1.0, "tx": 0.0, "ty": 0.0}
    layout = {"x": 0, "y": 0, "width": vw, "height": vh}

    zero = {"alpha": 0.0, "layout": layout, "transform": identity}
    one = {"alpha": 1.0, "layout": layout, "transform": identity}

    sprites = []
    for i in range(n):
        f = [dict(zero) for _ in range(i)]
        f.append(one)
        sprites.append({"imageKey": keys[i], "matteKey": "", "frames": f})

    spec = {
        "ver": "2.0.0",
        "movie": {"viewBox": {"width": vw, "height": vh}, "fps": fps, "frames": n},
        "images": {k: k for k in keys},
        "sprites": sprites,
    }

    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        z.writestr("movie.spec", json.dumps(spec, separators=(",", ":")))
        for k, fr in zip(keys, frames):
            data = encode_png(Image.fromarray(fr, "RGBA"), quantize, colors)
            z.writestr(k + ".png", data)


def write_preview(frames: list[np.ndarray], out_path: str, cols: int = 8) -> None:
    """Contact sheet on a checkerboard so transparency is obvious."""
    n = len(frames)
    rows = (n + cols - 1) // cols
    h, w = frames[0].shape[:2]
    sheet = Image.new("RGBA", (cols * w, rows * h), (0, 0, 0, 0))
    for i, fr in enumerate(frames):
        sheet.paste(Image.fromarray(fr, "RGBA"), ((i % cols) * w, (i // cols) * h))
    checker = Image.new("RGBA", sheet.size, (255, 255, 255, 255))
    px = checker.load()
    for y in range(sheet.size[1]):
        for x in range(sheet.size[0]):
            if ((x // 16) + (y // 16)) % 2 == 0:
                px[x, y] = (190, 190, 190, 255)
    checker.alpha_composite(sheet)
    checker.convert("RGB").save(out_path)


def write_preview_video(
    frames: list[np.ndarray],
    out_webm: str | None,
    out_mp4: str | None,
    fps: float,
    bg_mp4: tuple[int, int, int] = (18, 16, 26),
) -> None:
    """Write a genuinely playable preview of the processed clip.

    The WebM keeps the alpha channel (VP9 / yuva420p), so a browser shows the
    gift over whatever is behind it - a checkerboard, say - which is the only
    honest way to judge whether the background really came off. The MP4 is an
    opaque fallback for players without VP9 alpha, composited on a dark stage.
    """
    n = len(frames)
    if n == 0:
        return
    # yuv420p / yuva420p chroma is subsampled by two, so both encoders reject
    # an odd width or height. The aspect-preserving fit can land on an odd
    # side (269 px, say), so pad by one transparent pixel to the next even
    # size; the clip's own pixels are untouched.
    h, w = frames[0].shape[:2]
    ew, eh = w + (w & 1), h + (h & 1)
    if (ew, eh) != (w, h):
        padded = []
        for fr in frames:
            canvas = np.zeros((eh, ew, 4), np.uint8)
            canvas[:h, :w] = fr
            padded.append(canvas)
        frames = padded
    with tempfile.TemporaryDirectory() as td:
        for i, fr in enumerate(frames):
            Image.fromarray(fr, "RGBA").save(os.path.join(td, f"f_{i:05d}.png"))

        if out_webm:
            os.makedirs(os.path.dirname(os.path.abspath(out_webm)), exist_ok=True)
            subprocess.run(
                [
                    ffmpeg(), "-y", "-hide_banner", "-loglevel", "error",
                    "-framerate", str(fps), "-i", os.path.join(td, "f_%05d.png"),
                    "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p",
                    "-auto-alt-ref", "0", "-b:v", "3M", "-crf", "30",
                    out_webm,
                ],
                check=True,
            )

        if out_mp4:
            os.makedirs(os.path.dirname(os.path.abspath(out_mp4)), exist_ok=True)
            for i, fr in enumerate(frames):
                base = Image.new("RGBA", (fr.shape[1], fr.shape[0]), (*bg_mp4, 255))
                base.alpha_composite(Image.fromarray(fr, "RGBA"))
                base.convert("RGB").save(os.path.join(td, f"o_{i:05d}.png"))
            subprocess.run(
                [
                    ffmpeg(), "-y", "-hide_banner", "-loglevel", "error",
                    "-framerate", str(fps), "-i", os.path.join(td, "o_%05d.png"),
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20",
                    "-movflags", "+faststart",
                    out_mp4,
                ],
                check=True,
            )


def pick_icon_frame(frames: list[np.ndarray]) -> int:
    """Choose the frame with the most subject coverage - usually the clearest,
    most complete read of the gift rather than a fade-in or fade-out frame."""
    best, best_cov = 0, -1.0
    for i, fr in enumerate(frames):
        cov = float((fr[..., 3] > 128).mean())
        if cov > best_cov:
            best, best_cov = i, cov
    return best


def parse_bg(spec: str, frames: list[np.ndarray]) -> tuple[str, np.ndarray | None]:
    """Resolve --bg into a keying mode plus, for a colour key, its colour."""
    if spec == "auto":
        return detect_bg(frames)
    if spec in ("black", "green", "blue", "white"):
        return spec, None
    if spec.startswith("#") and len(spec) == 7:
        rgb = np.array([int(spec[i : i + 2], 16) for i in (1, 3, 5)], dtype=np.float32)
        return "color", rgb
    return "none", None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("-o", "--output", help="output .svga (omit with --passthrough)")
    ap.add_argument("--bg", default="auto")
    ap.add_argument("--size", type=int, default=480)
    ap.add_argument("--fps", type=float, default=24.0)
    ap.add_argument(
        "--max-frames",
        type=int,
        default=0,
        help=(
            "cap the number of decoded frames (0 = no cap). The default keeps "
            "the whole clip, so a scene is never truncated at an arbitrary "
            "frame; use --max-seconds to bound a long clip by time instead."
        ),
    )
    ap.add_argument(
        "--max-seconds",
        type=float,
        default=0.0,
        help=(
            "cap the clip's length in seconds (0 = keep the whole clip). The "
            "frame cap alone truncated a longer clip at an arbitrary point, "
            "which read as a cut-off scene; a time cap keeps the scene whole "
            "and only shortens it when it is genuinely too long."
        ),
    )
    ap.add_argument(
        "--decode-max",
        type=int,
        default=720,
        help=(
            "cap the decoded long side (px) to bound peak memory; the clip "
            "is resized to --size at the end, so this only avoids decoding a "
            "4K source at full resolution. 0 disables the cap."
        ),
    )
    ap.add_argument("--key-lo", type=float, default=0.05)
    ap.add_argument("--key-hi", type=float, default=0.20)
    ap.add_argument("--chroma-lo", type=float, default=0.12)
    ap.add_argument("--chroma-hi", type=float, default=0.45)
    ap.add_argument("--no-quantize", action="store_true")
    ap.add_argument("--colors", type=int, default=256)
    ap.add_argument("--no-clean", action="store_true", help="skip matte cleanup")
    ap.add_argument("--no-temporal", action="store_true", help="skip temporal stabilisation")
    ap.add_argument(
        "--no-strip-text",
        action="store_true",
        help="keep burnt-in captions/watermarks that sit still over the clip",
    )
    ap.add_argument(
        "--no-edge-fade",
        action="store_true",
        help="keep a hard clip border (do not fade it into the room)",
    )
    ap.add_argument(
        "--no-blend",
        action="store_true",
        help=(
            "for an opaque clip, use the plain border ramp instead of the "
            "borderless rounded blend"
        ),
    )
    ap.add_argument(
        "--blend-feather",
        type=float,
        default=0.16,
        help=(
            "width of the borderless blend's fade as a fraction of the "
            "clip's short side (only for an opaque clip; 0.10-0.25 reads well)"
        ),
    )
    ap.add_argument(
        "--blend-corner",
        type=float,
        default=0.10,
        help=(
            "corner radius of the borderless blend as a fraction of the "
            "clip's short side (only for an opaque clip)"
        ),
    )
    ap.add_argument(
        "--edge-fade",
        type=float,
        default=None,
        help=(
            "border fade width as a fraction of the frame's short side "
            "(default 0.10 on a normal encode; unset on --passthrough, which "
            "reproduces a stored clip exactly)"
        ),
    )
    ap.add_argument("--min-area", type=float, default=3e-4)
    ap.add_argument("--feather", type=float, default=0.7)
    ap.add_argument(
        "--pad",
        type=float,
        default=0.0,
        help="inset the subject by this fraction of the square per side (0.05-0.15 reads well over a room)",
    )
    ap.add_argument(
        "--matte",
        default="auto",
        choices=["auto", "key", "birefnet"],
        help=(
            "subject isolation. `birefnet` is semantic segmentation: it finds "
            "the subject as an object, so a dark subject on a dark backdrop is "
            "kept instead of being keyed away with the background. `key` is the "
            "colour/luma key. `auto` uses BiRefNet when its free weights are "
            "already on disk, else the key."
        ),
    )
    ap.add_argument(
        "--matte-model",
        default="birefnet-general-lite",
        help="rembg model name for --matte birefnet",
    )
    ap.add_argument(
        "--fit",
        default="square",
        choices=["square", "aspect", "cover"],
        help=(
            "output viewBox. `aspect` keeps the source's own aspect ratio, so a "
            "vertical scene stays a full vertical screen instead of being "
            "cropped into a small square. `cover` fills the room's screen "
            "edge to edge, cropping the overflow, for a full-screen takeover."
        ),
    )
    ap.add_argument(
        "--room-aspect",
        type=float,
        default=9 / 16,
        help=(
            "the room screen's width/height used by `--fit cover` (default "
            "0.5625, a 9:16 phone). The clip is cropped to this shape so it "
            "covers the room with no transparent margin."
        ),
    )
    ap.add_argument(
        "--no-crop",
        action="store_true",
        help=(
            "keep the whole frame instead of cropping to the subject's bounding "
            "box, so a scene that fills the frame is not cut down to a strip"
        ),
    )
    ap.add_argument("--min-coverage", type=float, default=0.005)
    ap.add_argument(
        "--max-opaque",
        type=float,
        default=0.97,
        help=(
            "fail if this fraction of the frame is opaque across the whole clip. "
            "An un-keyed upload keeps its own background, so it ships as a solid "
            "rectangle that hides the room; this catches that before publish. "
            "Raise it (or pass 1.0) for a scene that is genuinely full-frame."
        ),
    )
    ap.add_argument("--icon")
    ap.add_argument("--preview")
    ap.add_argument("--preview-video", dest="preview_video")
    ap.add_argument("--preview-mp4", dest="preview_mp4")
    ap.add_argument(
        "--passthrough",
        action="store_true",
        help="keep the frames exactly as decoded (no key, crop or fit); used to preview an existing clip",
    )
    args = ap.parse_args()

    info = probe(args.input)
    print(f"source: {info.get('size')} @ {info.get('fps')}fps  {info.get('duration')}")

    # Resolve the frame budget. A time cap is the honest limit: it keeps the
    # scene whole up to that length, where a bare frame count would cut the
    # clip off mid-scene. With no cap the whole clip is kept.
    frame_cap = resolve_frame_budget(args.max_frames, args.max_seconds, args.fps)
    print(f"frame budget: {'all' if frame_cap >= SAFETY_MAX_FRAMES else frame_cap} frames at {args.fps}fps")

    with tempfile.TemporaryDirectory() as wd:
        frames = extract_frames(
            args.input, args.fps, frame_cap, wd,
            max_side=args.decode_max, src_size=info.get("size"),
        )
    if not frames:
        print("no frames decoded", file=sys.stderr)
        return 1
    print(f"decoded {len(frames)} frames at {args.fps}fps")

    mode, bg_color = parse_bg(args.bg, frames)
    if args.bg == "auto" and has_real_alpha(frames):
        mode = "none"

    # Resolve the matte. BiRefNet is semantic: it finds the subject as an
    # object, so it is the only mode that survives a dark subject on a dark
    # backdrop. `auto` prefers it when the free weights are on disk and falls
    # back to the colour/luma key, which is all a flat green screen needs.
    use_birefnet = False
    if args.matte == "birefnet":
        if not birefnet_available(args.matte_model):
            print(f"error: --matte birefnet but model {args.matte_model!r} is not on disk", file=sys.stderr)
            return 2
        if not birefnet_affordable(args.matte_model):
            print(
                "error: --matte birefnet needs about "
                f"{BIREFNET_PEAK_MB / 1024:.1f} GiB of free RAM and only "
                f"{_available_mb() / 1024:.1f} GiB is available; free memory or "
                "use --matte key",
                file=sys.stderr,
            )
            return 2
        use_birefnet = True
    elif args.matte == "auto" and mode != "none" and birefnet_available(args.matte_model):
        if birefnet_affordable(args.matte_model):
            use_birefnet = True
        else:
            print(
                "matte: skipping BiRefNet (needs ~"
                f"{BIREFNET_PEAK_MB / 1024:.1f} GiB free, "
                f"{_available_mb() / 1024:.1f} GiB available) — using the colour key"
            )

    if args.passthrough:
        # Reproducing an existing clip: keep every frame as decoded so the
        # preview shows exactly what the app will play. The edge fade is off
        # unless asked for, so a preview always matches its stored clip.
        print("passthrough: frames kept as-is")
        out_frames = frames
        if args.fit == "cover":
            out_w, out_h = cover_size(args.size, args.room_aspect)
            out_frames = [
                np.array(fit_cover(Image.fromarray(f, "RGBA"), out_w, out_h))
                for f in frames
            ]
            out_size = (out_w, out_h)
        elif args.fit == "aspect":
            fh, fw = frames[0].shape[:2]
            if fw >= fh:
                out_size = (args.size, max(2, int(round(args.size * fh / fw)) & ~1))
            else:
                out_size = (max(2, int(round(args.size * fw / fh)) & ~1), args.size)
            out_frames = [
                np.array(fit_box(Image.fromarray(f, "RGBA"), out_size[0], out_size[1], args.pad))
                for f in frames
            ]
        else:
            out_size = args.size
        fade = resolve_edge_fade(args.no_blend, args.no_edge_fade, args.edge_fade)
        if fade:
            feather_border(out_frames, width_frac=fade)
            print(f"edge-fade: border ramped {fade:.0%} to transparent")
        # Reproducing a blank clip would publish a gift that renders nothing;
        # the same guard the keyed path uses applies here too.
        cov = subject_coverage(out_frames)
        print(f"subject coverage: {cov * 100:.1f}%")
        if cov < args.min_coverage:
            print(
                "error: the clip has no visible subject "
                f"(coverage {cov * 100:.2f}% < {args.min_coverage * 100:.2f}%)",
                file=sys.stderr,
            )
            return 3
        if args.output:
            os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
            build_svga(
                out_frames, args.output, args.fps, out_size,
                quantize=not args.no_quantize, colors=args.colors,
            )
            print(f"wrote {args.output}  ({os.path.getsize(args.output)/1024:.0f} KiB)")
    else:
        if mode == "none":
            print("background key: none (source already has alpha)")
            keyed = frames
        elif use_birefnet:
            print(f"matte: BiRefNet semantic segmentation ({args.matte_model})")
            keyed = matte_birefnet(frames, args.matte_model)
        elif mode == "black":
            print("background key: black (luma)")
            keyed = [key_black(f, args.key_lo, args.key_hi) for f in frames]
        else:
            if bg_color is None:
                _, sampled = detect_bg(frames)
                if mode == "green":
                    bg_color = np.array([0.0, 255.0, 0.0], dtype=np.float32)
                elif mode == "blue":
                    bg_color = np.array([0.0, 0.0, 255.0], dtype=np.float32)
                elif mode == "white":
                    bg_color = np.array([255.0, 255.0, 255.0], dtype=np.float32)
                else:
                    bg_color = sampled
            print(f"background key: {mode} colour {bg_color.astype(int).tolist()}")
            keyed = [key_chroma(f, bg_color, args.chroma_lo, args.chroma_hi) for f in frames]

        if mode != "none" and not args.no_clean:
            clean_matte(
                keyed,
                min_area_frac=args.min_area,
                feather=args.feather,
                temporal=not args.no_temporal,
            )
            print("matte: cleaned" + ("" if HAVE_SCIPY else " (no scipy: basic pass)"))

        if not args.no_strip_text and remove_static_overlays(keyed):
            print("stripped static overlay (caption/watermark)")

        # Guard against a matte that removed the subject — and against a
        # source that was already empty (a clip whose own alpha is all zero
        # needs no keying, so `mode` is "none", but it must not be published
        # either). Either way the app would show nothing at all, so fail
        # loudly here instead of shipping an invisible gift.
        cov = subject_coverage(keyed)
        print(f"subject coverage: {cov * 100:.1f}%")
        if cov < args.min_coverage:
            print(
                "error: the clip has no visible subject "
                f"(coverage {cov * 100:.2f}% < {args.min_coverage * 100:.2f}%); "
                "the source may be blank, or the matte removed the subject — "
                "try --matte birefnet, or --bg with the real backdrop colour",
                file=sys.stderr,
            )
            return 3

        # The mirror guard: a clip that was never keyed keeps its own
        # background. With edge-blending on (the default) the converter
        # dissolves it into the room, so a hard rectangle never ships. With
        # blending off the operator asked for a clean edge, and an opaque clip
        # would then be exactly the solid rectangle that hides the room — so
        # fail loudly and point at real background removal instead.
        if args.no_blend:
            opq = opaque_coverage(keyed)
            print(f"opaque coverage: {opq * 100:.1f}%")
            if opq > args.max_opaque:
                print(
                    "error: the clip is a solid rectangle "
                    f"(opaque {opq * 100:.1f}% > {args.max_opaque * 100:.1f}%) "
                    "and edge-blending is off; the background was not removed — "
                    "use --matte birefnet, or --bg with the real backdrop colour",
                    file=sys.stderr,
                )
                return 4

        # Crop to the subject only when the matte actually produced a cutout.
        # For a full scene the subject bbox is the whole frame anyway, so
        # cropping there would only trim a genuine scene down to a strip.
        src_h, src_w = keyed[0].shape[:2]
        if args.no_crop:
            x0, y0, x1, y1 = 0, 0, src_w, src_h
            print("crop: none (whole frame kept)")
        else:
            x0, y0, x1, y1 = content_bbox(keyed)
            print(f"crop: ({x0},{y0})-({x1},{y1}) of {src_w}x{src_h}")

        cw, ch = x1 - x0, y1 - y0
        if args.fit == "cover":
            # "Fill the room": crop the scene to the room screen's shape and
            # scale it to cover, so no transparent margin remains. The matte
            # still punched the background out, so the room shows through
            # wherever the subject is not.
            out_w, out_h = cover_size(args.size, args.room_aspect)
            out_frames = []
            for fr in keyed:
                crop = Image.fromarray(fr[y0:y1, x0:x1], "RGBA")
                out_frames.append(np.array(fit_cover(crop, out_w, out_h)))
            out_size: int | tuple[int, int] = (out_w, out_h)
        elif args.fit == "aspect":
            # Keep the crop's own aspect ratio so a vertical scene stays a
            # full vertical screen; the viewBox matches it. The side is
            # rounded to an even number so the clip and its H.264/VP9 preview
            # agree exactly (those encoders need even dimensions); the shift
            # is under 0.5%, far below anything visible.
            if cw >= ch:
                out_w = args.size
                out_h = max(2, int(round(args.size * ch / cw)) & ~1)
            else:
                out_h = args.size
                out_w = max(2, int(round(args.size * cw / ch)) & ~1)
            out_frames = []
            for fr in keyed:
                crop = Image.fromarray(fr[y0:y1, x0:x1], "RGBA")
                out_frames.append(np.array(fit_box(crop, out_w, out_h, args.pad)))
            out_size = (out_w, out_h)
        else:
            out_w = out_h = args.size
            out_frames = []
            for fr in keyed:
                crop = Image.fromarray(fr[y0:y1, x0:x1], "RGBA")
                out_frames.append(np.array(fit_box(crop, out_w, out_h, args.pad)))
            out_size = args.size

        # Edge treatment. An opaque filmed scene keeps a rectangle to its
        # edge, which the app would draw as a hard-edged "screen" over the
        # room; an opaque block therefore gets the borderless rounded blend
        # (no straight edge, rounded corners), while a keyed cutout keeps its
        # own silhouette and only gets the gentle border ramp.
        fade = resolve_edge_fade(args.no_blend, args.no_edge_fade, args.edge_fade)
        if fade:
            if not args.no_blend and blend_into_room(
                out_frames,
                feather_frac=args.blend_feather,
                corner_frac=args.blend_corner,
            ):
                print(
                    f"blend: opaque clip dissolved into the room "
                    f"(feather {args.blend_feather:.0%}, corners {args.blend_corner:.0%})"
                )
            else:
                feather_border(out_frames, width_frac=fade)
                print(f"edge-fade: border ramped {fade:.0%} to transparent")

        os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
        build_svga(
            out_frames, args.output, args.fps, out_size,
            quantize=not args.no_quantize, colors=args.colors,
        )
        print(f"wrote {args.output}  ({os.path.getsize(args.output)/1024:.0f} KiB)")

    if args.icon:
        os.makedirs(os.path.dirname(os.path.abspath(args.icon)), exist_ok=True)
        icon = Image.fromarray(out_frames[pick_icon_frame(out_frames)], "RGBA")
        if icon.size != (256, 256):
            icon = icon.resize((256, 256), Image.LANCZOS)
        q = icon.quantize(colors=64, method=Image.FASTOCTREE)
        q.save(args.icon, optimize=True)
        print(f"wrote icon {args.icon}")
    if args.preview:
        write_preview(out_frames, args.preview)
        print(f"wrote preview {args.preview}")
    if args.preview_video or args.preview_mp4:
        write_preview_video(out_frames, args.preview_video, args.preview_mp4, args.fps)
        print("wrote preview video")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
