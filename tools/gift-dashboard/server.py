#!/usr/bin/env python3
"""
Astro gift dashboard.

A self-contained operator console for the room gift catalog:

  * upload a video/GIF, and the same pipeline that authored the shipped
    gifts runs on it — background keying, crop, square fit with a
    transparent margin, SVGA encode, 256² icon and a tier-matched sound;
  * preview the processed clip *before* it goes live (animated preview
    in the browser, over a transparency checkerboard);
  * edit price, labels, tier and on-screen size for any gift, and
    publish; the app picks the change up on its next launch with no new
    APK.

Assets are content-addressed (`<sha256[:16]>.<ext>`), so republishing
never serves a stale file from a client cache and the app's URL-keyed
cache stays correct. State lives in `data/catalog.json`; the dashboard
also serves `data/published.json` to the app at `/gifts/catalog.json`.

Run:  python3 server.py --port 12001
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
ASSETS = DATA / "assets"
CLIPS = ASSETS / "clips"
VIDEOS = ASSETS / "videos"
ICONS = ASSETS / "icons"
SOUNDS = ASSETS / "sounds"
STATE = DATA / "catalog.json"
TOOLS = Path(os.environ.get("GIFT_TOOLS", "/workspace/svga_tools"))
PYTHON = os.environ.get("GIFT_PYTHON", "/workspace/venv/bin/python")
CF_TOKEN_FILE = Path(os.environ.get("CF_TOKEN_FILE", "/workspace/.cf_token"))

MAX_UPLOAD = 512 * 1024 * 1024
ID_RE = re.compile(r"^[a-z0-9_]{1,32}$")

# The room screen's width/height, matching the phone-shaped stage the app and
# the dashboard preview draw. `fit=cover` crops a clip to this shape so it
# fills the room edge to edge.
ROOM_ASPECT = 252 / 448

# Both work hosts may be pointed at the same data directory, so state writes
# are serialised across the listeners. Two concurrent uploads would otherwise
# read-modify-write catalog.json and silently drop one gift.
STATE_LOCK = threading.RLock()

# A processing run is minutes long, so the dashboard cannot hold one HTTP
# request open and still show staged progress. Each upload instead starts a
# job, returns its id at once, and the page polls `/api/job` for the stage
# the pipeline has reached.
JOBS: dict[str, dict] = {}
JOBS_LOCK = threading.Lock()


def job_start(gift_id: str) -> str:
    jid = hashlib.sha1(f"{gift_id}-{time.time()}-{os.getpid()}".encode()).hexdigest()[:16]
    with JOBS_LOCK:
        JOBS[jid] = {"id": jid, "gift": gift_id, "stage": "queued", "pct": 0,
                     "label": "في الانتظار…", "done": False, "error": None, "draft": None}
    return jid


def job_update(jid: str, **kw) -> None:
    with JOBS_LOCK:
        job = JOBS.get(jid)
        if job:
            job.update(kw)


def job_get(jid: str) -> dict | None:
    with JOBS_LOCK:
        job = JOBS.get(jid)
        return dict(job) if job else None

# Built APKs the operator can hand out without a rebuild. The newest
# universal debug build wins, so `/download/apk` always serves the app
# that carries the latest dashboard changes.
APK_GLOB = "**/build/outputs/apk/**/*universal*.apk"
APK_ROOTS = (
    Path("/workspace/astro-backup"),
    Path("/workspace"),
)


def newest_apk() -> Path | None:
    best: Path | None = None
    for root in APK_ROOTS:
        if not root.is_dir():
            continue
        for apk in root.glob(APK_GLOB):
            if best is None or apk.stat().st_mtime > best.stat().st_mtime:
                best = apk
    return best


def ensure_dirs() -> None:
    for d in (DATA, ASSETS, CLIPS, VIDEOS, ICONS, SOUNDS):
        d.mkdir(parents=True, exist_ok=True)


# ── state ──────────────────────────────────────────────────────────────


def default_state() -> dict:
    return {"version": 0, "gifts": [], "drafts": []}


def load_state() -> dict:
    if STATE.exists():
        try:
            state = json.loads(STATE.read_text())
            state.setdefault("gifts", [])
            state.setdefault("drafts", [])
            return state
        except Exception:
            pass
    return default_state()


def save_state(state: dict) -> None:
    with STATE_LOCK:
        _save_state_locked(state)


def _save_state_locked(state: dict) -> None:
    state["version"] = int(state.get("version", 0)) + 1
    # Write-then-rename: a reader (or the second listener) must never see a
    # half-written catalog.json.
    tmp = STATE.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2))
    os.replace(tmp, STATE)
    # The app fetches this copy. Kept separate so the operator file can
    # carry fields the client does not need — and, importantly, so an
    # unpublished draft is never visible to the app.
    published = {
        "version": state["version"],
        "gifts": [
            {
                "id": g["id"],
                "emoji": g.get("emoji", "🎁"),
                "labelAr": g.get("labelAr", ""),
                "labelEn": g.get("labelEn", ""),
                "tier": g.get("tier", 1),
                "price": g.get("price", 1),
                "svga": g.get("svga"),
                # The alpha video is what the app plays when present; SVGA
                # stays for older clients, exactly as the worker publishes.
                "video": g.get("video"),
                "videoWidth": g.get("videoWidth"),
                "videoHeight": g.get("videoHeight"),
                "icon": g.get("icon"),
                "sound": g.get("sound"),
                "scale": g.get("scale"),
                "posX": g.get("posX", 0),
                "posY": g.get("posY", 0),
            }
            for g in state["gifts"]
            if g.get("svga") or g.get("video")
        ],
    }
    (DATA / "published.json").write_text(json.dumps(published, ensure_ascii=False, indent=2))


def find_gift(state: dict, gift_id: str) -> dict | None:
    return next((g for g in state["gifts"] + state.get("drafts", []) if g["id"] == gift_id), None)


# ── processing pipeline ────────────────────────────────────────────────


def content_hash(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()[:16]


def read_alpha_meta(video: Path) -> dict:
    """Read the frame size of a packed clip, i.e. half its stored height.

    The packer stacks colour over alpha, so the file is twice as tall as one
    frame; the app needs the single-frame size to split the two halves.

    This reads the size from ffmpeg's own stream banner rather than ffprobe:
    the bundled imageio-ffmpeg build ships `ffmpeg` only, so a call to
    `ffprobe` fails with ENOENT and would abort an upload *after* the whole
    pipeline had already run.
    """
    import imageio_ffmpeg

    proc = subprocess.run(
        [imageio_ffmpeg.get_ffmpeg_exe(), "-hide_banner", "-i", str(video)],
        capture_output=True, text=True,
    )
    for line in (proc.stderr or "").splitlines():
        if "Video:" not in line or "attached pic" in line or "mjpeg" in line.lower():
            continue
        for tok in line.replace(",", " ").split():
            if "x" in tok:
                w, _, h = tok.partition("x")
                if w.isdigit() and h.isdigit():
                    return {"width": int(w), "height": int(h) // 2}
    return {"width": 0, "height": 0}


def extract_audio(src: Path, out_wav: Path) -> bool:
    """Replace `out_wav` with the source clip's own audio track, if it has one.

    The app plays a gift's sound through MediaPlayer from a WAV, so the
    upload's soundtrack is normalised to the bundled format (mono 22.05 kHz
    16-bit) and used in place of the synthesised chime. Returns False and
    leaves `out_wav` untouched when the source carries no audio, so the
    caller keeps the generated sound.
    """
    import imageio_ffmpeg

    probe = subprocess.run(
        [imageio_ffmpeg.get_ffmpeg_exe(), "-hide_banner", "-i", str(src)],
        capture_output=True, text=True,
    )
    if "Audio:" not in (probe.stderr or ""):
        return False
    tmp = out_wav.with_suffix(".src.wav")
    proc = subprocess.run(
        [
            imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-hide_banner", "-loglevel", "error",
            "-i", str(src), "-vn", "-ac", "1", "-ar", "22050", "-c:a", "pcm_s16le",
            str(tmp),
        ],
        capture_output=True, text=True,
    )
    if proc.returncode != 0 or not tmp.exists() or tmp.stat().st_size <= 44:
        tmp.unlink(missing_ok=True)
        return False
    tmp.replace(out_wav)
    return True


def run_tool(args: list[str], on_line=None) -> str:
    if on_line is None:
        proc = subprocess.run(args, capture_output=True, text=True, timeout=1800)
        if proc.returncode != 0:
            raise RuntimeError((proc.stderr or proc.stdout or "tool failed").strip()[-2000:])
        return proc.stdout.strip()
    # Stream the tool's output line by line so a caller can turn the stage
    # markers the converter already prints ("decoded", "matte 3/120", "crop:")
    # into real progress, instead of a bar that only guesses.
    proc = subprocess.Popen(
        args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1
    )
    lines: list[str] = []
    for line in proc.stdout:
        line = line.rstrip("\n")
        lines.append(line)
        try:
            on_line(line)
        except Exception:
            pass
    proc.wait()
    if proc.returncode != 0:
        raise RuntimeError("\n".join(lines)[-2000:] or "tool failed")
    return "\n".join(lines)


def shape_args_for(fields: dict) -> list[str]:
    """Build the converter's shape flags from the dashboard's form fields.

    Kept separate from `process_upload` so the flag wiring (in particular the
    room aspect `fit=cover` needs) is testable without running ffmpeg.
    """
    fit = fields.get("fit") or "square"
    matte = fields.get("matte") or "auto"
    args: list[str] = ["--fit", fit, "--matte", matte]
    if fit == "cover":
        args += ["--room-aspect", str(ROOM_ASPECT)]
    no_crop = str(fields.get("noCrop") or "").lower() in ("1", "true", "on", "yes")
    if no_crop:
        args.append("--no-crop")
    try:
        max_seconds = float(fields.get("maxSeconds") or 0)
    except (TypeError, ValueError):
        max_seconds = 0.0
    if max_seconds > 0:
        args += ["--max-seconds", str(max_seconds)]
    return args


def process_upload(
    src: Path,
    gift_id: str,
    tier: int,
    bg: str,
    size: int,
    pad: float,
    blend: str = "auto",
    fit: str = "square",
    matte: str = "auto",
    no_crop: bool = False,
    max_seconds: float = 0.0,
    on_stage=None,
) -> dict:
    """Run the authoring pipeline and return the published asset paths.

    `on_stage(stage, pct, label)` is called as the run advances, so a caller
    can surface real progress. The stages follow the pipeline: decode, then
    background removal, then crop/fit, then packing.
    """

    def stage(name: str, pct: float, label: str) -> None:
        if on_stage:
            try:
                on_stage(name, pct, label)
            except Exception:
                pass

    def convert_line(line: str) -> None:
        """Translate the converter's own progress lines into bar updates."""
        s = line.strip()
        if s.startswith("decoded "):
            stage("decode", 18, "تم فكّ ترميز الفيديو — جارٍ إزالة الخلفية…")
        elif s.startswith("matte ") and "/" in s:
            try:
                i, n = s.split()[1].split("/")
                # Background removal is the long pole; give it the widest
                # slice of the bar (20%..80%).
                stage("matte", 20 + 60 * (int(i) / max(1, int(n))),
                      f"إزالة الخلفية… {i}/{n}")
            except Exception:
                pass
        elif s.startswith("crop:"):
            stage("crop", 84, "القص وضبط الحدود الشفافة…")
        elif s.startswith(("blend:", "edge:", "fade:")):
            stage("blend", 88, "دمج الحواف في الغرفة…")
    # Map the dashboard's blend choice onto the converter's flags. An opaque
    # clip (uploaded without background removal) is dissolved into the room
    # with a borderless rounded fade by default, so it never reads as a
    # rectangular "screen" over the room.
    blend_args: list[str] = []
    if blend == "off":
        blend_args = ["--no-blend"]
    elif blend == "soft":
        blend_args = ["--blend-feather", "0.10", "--blend-corner", "0.07"]
    elif blend == "strong":
        blend_args = ["--blend-feather", "0.24", "--blend-corner", "0.14"]
    # "auto" keeps the converter's defaults.

    # `fit=aspect` keeps the upload's own shape, so a vertical scene stays a
    # full vertical screen instead of being cropped into a small square.
    # `fit=cover` fills the room screen edge to edge (cropping the overflow),
    # so the scene is a full-screen takeover with no transparent margin; the
    # removed background still lets the room show through. `no_crop` keeps the
    # whole frame, so a scene that fills it is not cut down to the subject's
    # bounding box. `max_seconds` caps the length at the operator's choice
    # (0 = the whole clip).
    shape_args = shape_args_for(
        {"fit": fit, "matte": matte, "noCrop": no_crop, "maxSeconds": max_seconds}
    )

    with tempfile.TemporaryDirectory() as td:
        td_path = Path(td)
        svga_tmp = td_path / f"{gift_id}.svga"
        icon_tmp = td_path / f"{gift_id}.png"
        sound_tmp = td_path / f"{gift_id}.wav"
        sheet_tmp = td_path / f"{gift_id}_sheet.png"
        webm_tmp = td_path / f"{gift_id}.webm"
        mp4_tmp = td_path / f"{gift_id}.mp4"

        run_tool(
            [
                PYTHON, str(TOOLS / "video_to_svga.py"), str(src),
                "-o", str(svga_tmp),
                "--bg", bg,
                "--size", str(size),
                "--pad", str(pad),
                *blend_args,
                *shape_args,
                "--icon", str(icon_tmp),
                "--preview", str(sheet_tmp),
                "--preview-video", str(webm_tmp),
                "--preview-mp4", str(mp4_tmp),
            ],
            on_line=convert_line,
        )
        stage("sound", 92, "تجهيز الصوت…")
        run_tool(
            [
                PYTHON, str(TOOLS / "make_gift_sound.py"),
                "--id", gift_id, "--tier", str(tier),
                "-o", str(sound_tmp),
            ]
        )
        # The uploaded clip's own soundtrack wins over the synthesised chime:
        # if the operator added a sound to the video, that is the gift's sound.
        extract_audio(src, sound_tmp)

        # Content-addressed names: a republish produces a new URL, so no
        # client serves a stale clip from its cache.
        clip_name = f"{content_hash(svga_tmp)}.svga"
        icon_name = f"{content_hash(icon_tmp)}.png"
        sound_name = f"{content_hash(sound_tmp)}.wav"
        shutil.copy2(svga_tmp, CLIPS / clip_name)
        shutil.copy2(icon_tmp, ICONS / icon_name)
        shutil.copy2(sound_tmp, SOUNDS / sound_name)

        # Pack the clip into an alpha video. This is the asset the app
        # actually plays: the colour frame and its alpha travel in one small
        # H.264 file that the phone's hardware decoder renders, so the burst
        # starts sooner and costs far less RAM than the software-decoded SVGA.
        # The SVGA is still published as the fallback for older clients.
        video_tmp = td_path / f"{gift_id}.mp4"
        stage("pack", 95, "ترميز الفيديو الشفاف…")
        run_tool([PYTHON, str(TOOLS / "alpha_pack.py"), str(svga_tmp), "-o", str(video_tmp), "--crf", "26"])
        video_name = f"{content_hash(video_tmp)}.mp4"
        shutil.copy2(video_tmp, VIDEOS / video_name)
        video_meta = read_alpha_meta(video_tmp)

        preview_dir = DATA / "previews"
        preview_dir.mkdir(exist_ok=True)
        for name in (f"{gift_id}.gif", f"{gift_id}.webm", f"{gift_id}.mp4", f"{gift_id}.png"):
            (preview_dir / name).unlink(missing_ok=True)
        shutil.copy2(sheet_tmp, preview_dir / f"{gift_id}.png")
        shutil.copy2(webm_tmp, preview_dir / f"{gift_id}.webm")
        shutil.copy2(mp4_tmp, preview_dir / f"{gift_id}.mp4")

    return {
        "svga": f"clips/{clip_name}",
        "video": f"videos/{video_name}",
        "videoWidth": video_meta["width"],
        "videoHeight": video_meta["height"],
        "icon": f"icons/{icon_name}",
        "sound": f"sounds/{sound_name}",
    }


def push_catalog_to_cloud(gift_id: str | None = None, delete: bool = False) -> tuple[bool, str]:
    """Mirror local state into Cloudflare R2.

    The app reads its gift catalog from the Cloudflare worker, not from this
    dashboard, so a gift published only locally is invisible on the phone.
    Publishing therefore pushes the assets and catalog up as well; without a
    token the local publish still succeeds and the operator is told the cloud
    mirror was skipped.

    Three modes, so a cheap edit never re-uploads the whole library:
    - default: every asset plus the catalog, for a publish.
    - `gift_id`: merge one row into the cloud's own catalog, for an edit.
    - `delete=True`: drop one row from the cloud's catalog.
    The Cloudflare panel can author gifts this dashboard never saw, so an edit
    merges into the cloud state instead of overwriting it with ours.
    """
    if not (os.environ.get("CLOUDFLARE_API_TOKEN") or CF_TOKEN_FILE.exists()):
        return False, "لا يوجد توكن Cloudflare — لم تتم المزامنة مع السحابة"
    try:
        if delete:
            cmd = ["delete", gift_id]
        elif gift_id:
            cmd = ["edit", gift_id]
        else:
            cmd = ["push-all"]
        out = run_tool([PYTHON, str(ROOT / "publish.py"), *cmd])
        return True, (out.splitlines()[-1] if out else "تمت المزامنة مع السحابة")
    except Exception as exc:  # noqa: BLE001 - surfaced to the operator
        return False, f"تعذّرت المزامنة مع السحابة: {exc}"


def _run_upload_job(
    jid: str,
    src: Path,
    work: Path,
    gift_id: str,
    tier: int,
    bg: str,
    size: int,
    pad: float,
    blend: str,
    fit: str,
    matte: str,
    no_crop: bool,
    max_seconds: float,
    meta: dict,
) -> None:
    """Run the authoring pipeline for one upload, reporting progress to JOBS."""
    def on_stage(stage: str, pct: float, label: str) -> None:
        job_update(jid, stage=stage, pct=round(pct, 1), label=label)

    try:
        job_update(jid, stage="decode", pct=5, label="فكّ ترميز الفيديو…")
        assets = process_upload(
            src, gift_id, tier, bg, size, pad, blend,
            fit=fit, matte=matte, no_crop=no_crop, max_seconds=max_seconds,
            on_stage=on_stage,
        )
        job_update(jid, stage="save", pct=97, label="حفظ الهدية…")
        with STATE_LOCK:
            state = load_state()
            draft = next((d for d in state["drafts"] if d["id"] == gift_id), None)
            if draft is None:
                draft = {"id": gift_id}
                state["drafts"].append(draft)
            draft.update(
                {
                    "emoji": meta.get("emoji") or draft.get("emoji") or "🎁",
                    "labelAr": meta.get("labelAr") or draft.get("labelAr") or gift_id,
                    "labelEn": meta.get("labelEn") or draft.get("labelEn") or gift_id,
                    "tier": tier,
                    "price": int(meta.get("price") or draft.get("price") or 1),
                    "scale": float(draft.get("scale") or 1.0),
                    "posX": float(draft.get("posX") or 0.0),
                    "posY": float(draft.get("posY") or 0.0),
                    "bg": bg, "blend": blend, "fit": fit, "matte": matte,
                    "noCrop": no_crop, "maxSeconds": max_seconds,
                    "size": size, "pad": pad,
                    **assets,
                }
            )
            draft.update(clip_preview(draft))
            save_state(state)
        job_update(jid, stage="done", pct=100, label="اكتملت المعالجة", done=True, draft=draft)
    except Exception as exc:  # noqa: BLE001 - the message is shown to the operator
        traceback.print_exc()
        job_update(jid, stage="error", done=True, error=(str(exc) or "فشل غير معروف"))
    finally:
        shutil.rmtree(work, ignore_errors=True)


def build_preview_from_clip(gift: dict) -> None:
    """Render the playable preview for a gift whose clip exists but whose
    preview does not.

    Older gifts were authored before previews existed (or by the CLI), so this
    rebuilds them straight from the stored SVGA — the clip is a ZIP of PNG
    frames, so no source video is needed. Failure is non-fatal: a gift without
    a preview is still publishable.
    """
    gift_id = gift.get("id")
    clip = ASSETS / (gift.get("svga") or "")
    if not gift_id or not clip.exists():
        return
    out = DATA / "previews"
    webm, mp4 = out / f"{gift_id}.webm", out / f"{gift_id}.mp4"
    if webm.exists() and mp4.exists():
        return
    out.mkdir(parents=True, exist_ok=True)
    try:
        run_tool(
            [
                PYTHON, str(TOOLS / "video_to_svga.py"), str(clip), "--passthrough",
                "--preview-video", str(webm), "--preview-mp4", str(mp4),
            ]
        )
    except Exception:
        traceback.print_exc()


def clip_preview(gift: dict) -> dict:
    """Locate the playable preview of a stored clip.

    Returns the WebM (alpha, so the browser shows the real transparency) and
    an opaque MP4 fallback. Both are built once during upload, so this is a
    pure lookup — a state poll must never block on an encode. The icon doubles
    as the poster, since it is already a small, content-addressed frame.
    """
    gift_id = gift["id"]
    out = DATA / "previews"
    webm, mp4 = out / f"{gift_id}.webm", out / f"{gift_id}.mp4"
    res: dict = {}
    if webm.exists():
        res["preview"] = f"previews/{gift_id}.webm?t={int(webm.stat().st_mtime)}"
    if mp4.exists():
        res["previewMp4"] = f"previews/{gift_id}.mp4?t={int(mp4.stat().st_mtime)}"
    if gift.get("icon"):
        res["poster"] = f"gifts/{gift['icon']}"
    return res


# ── HTTP ───────────────────────────────────────────────────────────────


class Handler(BaseHTTPRequestHandler):
    server_version = "AstroGiftDashboard/1.0"

    def log_message(self, fmt, *args):  # quieter console
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def send_bytes(self, data: bytes, ctype: str, code: int = 200, cache: bool = False) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "public, max-age=31536000" if cache else "no-store")
        self.end_headers()
        self.wfile.write(data)

    def send_json(self, obj, code: int = 200) -> None:
        self.send_bytes(json.dumps(obj, ensure_ascii=False).encode(), "application/json; charset=utf-8", code)

    def send_file(
        self,
        path: Path,
        ctype: str | None = None,
        cache: bool = True,
        download_name: str | None = None,
    ) -> None:
        if not path.exists() or not path.is_file():
            return self.send_error(404, "not found")
        if ctype is None:
            ctype = {
                ".html": "text/html; charset=utf-8",
                ".json": "application/json; charset=utf-8",
                ".png": "image/png",
                ".gif": "image/gif",
                ".webm": "video/webm",
                ".mp4": "video/mp4",
                ".svga": "application/octet-stream",
                ".wav": "audio/wav",
            }.get(path.suffix, "application/octet-stream")
        data = path.read_bytes()
        # Video elements issue Range requests to seek; honouring them is what
        # lets the operator scrub the preview instead of watching it whole.
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes="):
            total = len(data)
            try:
                spec = rng[len("bytes=") :].split(",")[0].strip()
                start_s, _, end_s = spec.partition("-")
                start = int(start_s) if start_s else 0
                end = int(end_s) if end_s else total - 1
            except ValueError:
                start, end = 0, total - 1
            start = max(0, min(start, total - 1))
            end = max(start, min(end, total - 1))
            chunk = data[start : end + 1]
            self.send_response(206)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(chunk)))
            self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Cache-Control", "public, max-age=31536000" if cache else "no-store")
            self.end_headers()
            self._write_body(chunk)
            return
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "public, max-age=31536000" if cache else "no-store")
        if download_name:
            self.send_header("Content-Disposition", f'attachment; filename="{download_name}"')
        self.end_headers()
        self._write_body(data)

    def _write_body(self, data: bytes) -> None:
        # A browser that picks one <source> cancels the other, and the abort
        # surfaces here. It is not an error: swallow it so it does not become
        # a 500 plus a traceback for every preview opened.
        try:
            self.wfile.write(data)
        except ConnectionError:  # covers BrokenPipe + ConnectionReset
            pass

    def read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_UPLOAD:
            raise ValueError("bad body")
        return self.rfile.read(length)

    # -- routing

    def do_GET(self):  # noqa: N802
        p = urlparse(self.path).path
        try:
            if p in ("/", "/index.html"):
                return self.send_bytes(INDEX_HTML.encode(), "text/html; charset=utf-8")
            if p == "/api/state":
                state = load_state()
                for g in state["gifts"] + state["drafts"]:
                    if g.get("svga"):
                        g.update(clip_preview(g))
                return self.send_json(state)
            if p == "/api/job":
                return self.api_job()
            if p == "/gifts/catalog.json":
                return self.send_file(DATA / "published.json", "application/json; charset=utf-8", cache=False)
            if p.startswith("/gifts/"):
                # Relative asset paths in the catalog resolve against
                # `/gifts/`, so the clip/icon/sound are served here too.
                return self.send_file(ASSETS / p[len("/gifts/"):])
            if p.startswith("/assets/"):
                return self.send_file(ASSETS / p[len("/assets/"):])
            if p.startswith("/previews/"):
                return self.send_file(DATA / p[len("/"):], cache=False)
            if p in ("/download/apk", "/app.apk"):
                apk = newest_apk()
                if apk is None:
                    return self.send_error(404, "no apk built")
                return self.send_file(
                    apk,
                    "application/vnd.android.package-archive",
                    cache=False,
                    download_name="amethyst-gifts.apk",
                )
            return self.send_error(404, "not found")
        except Exception as exc:
            traceback.print_exc()
            return self.send_json({"error": str(exc)}, 500)

    def do_POST(self):  # noqa: N802
        p = urlparse(self.path).path
        try:
            if p == "/api/upload":
                return self.api_upload()
            if p == "/api/publish":
                return self.api_publish()
            if p == "/api/discard":
                return self.api_discard()
            if p == "/api/gift":
                return self.api_gift()
            if p == "/api/delete":
                return self.api_delete()
            return self.send_error(404, "not found")
        except Exception as exc:
            traceback.print_exc()
            return self.send_json({"error": str(exc)}, 500)

    # -- endpoints

    def api_upload(self):
        """Multipart upload → start a background job → return its id at once.

        The pipeline takes minutes, so holding the request open would leave
        the page with nothing to show but a fake sweep. The bytes are saved
        here, then a worker thread runs the run and reports each stage into
        JOBS, which `/api/job` exposes for the progress bar.
        """
        body = self.read_body()
        fields, files = parse_multipart(self.headers.get("Content-Type", ""), body)
        if "file" not in files:
            return self.send_json({"error": "no file"}, 400)
        gift_id = (fields.get("id") or "").strip().lower()
        if not ID_RE.match(gift_id):
            return self.send_json({"error": "id must be a-z, 0-9, _ (max 32)"}, 400)
        tier = int(fields.get("tier") or 1)
        bg = fields.get("bg") or "auto"
        blend = fields.get("blend") or "auto"
        fit = fields.get("fit") or "square"
        matte = fields.get("matte") or "auto"
        no_crop = (fields.get("noCrop") or "").lower() in ("1", "true", "on", "yes")
        max_seconds = float(fields.get("maxSeconds") or 0)
        size = int(fields.get("size") or 480)
        pad = float(fields.get("pad") or 0.08)

        filename, payload = files["file"]
        # The upload's temp dir must outlive this request, so the thread owns
        # a private copy rather than the request-scoped temporary file.
        work = Path(tempfile.mkdtemp(prefix="gift-upload-"))
        src = work / f"upload{Path(filename).suffix or '.mp4'}"
        src.write_bytes(payload)
        meta = {
            "emoji": fields.get("emoji"),
            "labelAr": fields.get("labelAr"),
            "labelEn": fields.get("labelEn"),
            "price": fields.get("price"),
        }

        jid = job_start(gift_id)
        threading.Thread(
            target=_run_upload_job,
            args=(jid, src, work, gift_id, tier, bg, size, pad, blend, fit, matte, no_crop, max_seconds, meta),
            daemon=True,
        ).start()
        return self.send_json({"ok": True, "job": jid})

    def api_job(self):
        """Progress of a background upload job, plus its draft when finished."""
        q = urlparse(self.path).query
        jid = ""
        for part in q.split("&"):
            if part.startswith("id="):
                jid = part[3:]
        job = job_get(jid)
        if job is None:
            return self.send_json({"error": "unknown job"}, 404)
        return self.send_json(job)

    def api_publish(self):
        """Promote a draft into the live catalog."""
        payload = json.loads(self.read_body())
        state = load_state()
        live = next((g for g in state["gifts"] if g["id"] == payload.get("id")), None)
        draft = next((d for d in state["drafts"] if d["id"] == payload.get("id")), None)
        # Re-uploading an asset for an already-live gift runs the same
        # upload-then-publish sequence as a new one; a missing draft here means
        # "already published", not an error.
        if draft is None:
            if live is not None:
                synced, note = push_catalog_to_cloud()
                return self.send_json({"ok": True, "gift": live, "synced": synced, "note": note})
            return self.send_json({"error": "unknown draft"}, 404)
        if not draft.get("svga") and not draft.get("video"):
            return self.send_json({"error": "draft has no clip"}, 400)
        # Search only the live gifts: find_gift() also scans drafts, so it
        # would return the draft itself and the gift would never be added to
        # the catalog — publishing would silently drop it.
        gift = live
        if gift is None:
            gift = {"id": draft["id"]}
            state["gifts"].append(gift)
        gift.update({k: v for k, v in draft.items() if k != "preview"})
        state["drafts"] = [d for d in state["drafts"] if d["id"] != draft["id"]]
        save_state(state)
        # The app reads its catalog from Cloudflare, so publishing locally is
        # not enough: mirror the gift and catalog up or it never reaches the
        # phone. A missing token degrades to a local-only publish with a note.
        synced, note = push_catalog_to_cloud()
        return self.send_json({"ok": True, "gift": gift, "synced": synced, "note": note})

    def api_discard(self):
        payload = json.loads(self.read_body())
        state = load_state()
        before = len(state["drafts"])
        state["drafts"] = [d for d in state["drafts"] if d["id"] != payload.get("id")]
        if len(state["drafts"]) == before:
            return self.send_json({"error": "unknown draft"}, 404)
        save_state(state)
        return self.send_json({"ok": True})

    def api_gift(self):
        """Edit fields of an existing gift in place."""
        payload = json.loads(self.read_body())
        state = load_state()
        gift = find_gift(state, payload.get("id"))
        if gift is None:
            return self.send_json({"error": "unknown gift"}, 404)
        for key in ("emoji", "labelAr", "labelEn"):
            if key in payload:
                gift[key] = payload[key]
        for key, cast in (("tier", int), ("price", int), ("scale", float), ("posX", float), ("posY", float)):
            if payload.get(key) is not None:
                gift[key] = cast(payload[key])
        save_state(state)
        # The app reads its catalog from Cloudflare, so a local-only edit is
        # invisible on the phone — the operator would save, see the row change
        # here, and still get the old placement in the room. Mirror the edit up
        # exactly as publishing does, and report whether it actually reached
        # the app instead of claiming a success the phone never sees.
        synced, note = push_catalog_to_cloud(gift_id=gift["id"])
        return self.send_json({"ok": True, "gift": gift, "synced": synced, "note": note})

    def api_delete(self):
        payload = json.loads(self.read_body())
        state = load_state()
        before = len(state["gifts"])
        state["gifts"] = [g for g in state["gifts"] if g["id"] != payload.get("id")]
        if len(state["gifts"]) == before:
            return self.send_json({"error": "unknown gift"}, 404)
        save_state(state)
        # A delete that stays local leaves the gift live in the app's catalog.
        synced, note = push_catalog_to_cloud(gift_id=payload.get("id"), delete=True)
        return self.send_json({"ok": True, "synced": synced, "note": note})


def parse_multipart(content_type: str, body: bytes) -> tuple[dict, dict]:
    """Minimal multipart/form-data parser (one file + a few fields)."""
    m = re.search(r"boundary=([^;]+)", content_type)
    if not m:
        raise ValueError("not multipart")
    boundary = m.group(1).strip().strip('"').encode()
    fields: dict[str, str] = {}
    files: dict[str, tuple[str, bytes]] = {}
    for part in body.split(b"--" + boundary):
        if not part.strip() or part.strip() == b"--":
            continue
        head, sep, data = part.partition(b"\r\n\r\n")
        if not sep:
            continue
        data = data.rstrip(b"\r\n")
        header = head.decode("utf-8", "replace")
        name_m = re.search(r'name="([^"]*)"', header)
        if not name_m:
            continue
        file_m = re.search(r'filename="([^"]*)"', header)
        if file_m:
            files[name_m.group(1)] = (file_m.group(1), data)
        else:
            fields[name_m.group(1)] = data.decode("utf-8", "replace")
    return fields, files


# ── dashboard UI ───────────────────────────────────────────────────────

INDEX_HTML = r"""<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>لوحة تحكم الهدايا — Astro</title>
<style>
  :root { color-scheme: dark; --bg:#0c0a12; --card:#171423; --line:#2a2438;
          --txt:#ece9f5; --dim:#9d97b0; --acc:#f0a537; --acc2:#b287f5; }
  * { box-sizing:border-box; }
  body { margin:0; font-family:system-ui,-apple-system,"Segoe UI",Tahoma,sans-serif;
         background:radial-gradient(1200px 600px at 50% -10%,#241a3d,#0c0a12 60%);
         color:var(--txt); padding:24px; }
  h1 { font-size:22px; margin:0 0 4px; }
  .sub { color:var(--dim); font-size:13.5px; margin-bottom:22px; }
  .grid { display:grid; grid-template-columns:minmax(340px,420px) 1fr; gap:20px; align-items:start; }
  @media (max-width:900px){ .grid{ grid-template-columns:1fr; } }
  .card { background:var(--card); border:1px solid var(--line); border-radius:18px; padding:20px; }
  .card h2 { font-size:15px; margin:0 0 14px; }
  label { display:block; font-size:12.5px; color:var(--dim); margin:10px 0 4px; }
  input,select { width:100%; padding:10px 12px; border-radius:10px; border:1px solid var(--line);
                 background:#100e19; color:var(--txt); font-size:14px; }
  input[type=file]{ padding:8px; }
  .row { display:flex; gap:10px; }
  .row > div { flex:1; }
  button { border:0; border-radius:999px; padding:11px 20px; font-weight:800; font-size:14px;
           cursor:pointer; background:linear-gradient(90deg,var(--acc),var(--acc2)); color:#14101f; }
  button.ghost { background:transparent; border:1px solid var(--line); color:var(--txt); font-weight:600; }
  button:disabled { opacity:.5; cursor:default; }
  .actions { margin-top:16px; display:flex; gap:10px; align-items:center; }
  .hint { font-size:12px; color:var(--dim); margin-top:8px; line-height:1.6; }
  table { width:100%; border-collapse:collapse; font-size:13.5px; }
  th,td { text-align:right; padding:9px 8px; border-bottom:1px solid var(--line); vertical-align:middle; }
  th { color:var(--dim); font-weight:600; font-size:12px; }
  .thumb { width:44px; height:44px; border-radius:10px; object-fit:contain;
           background:linear-gradient(135deg,#241d33,#161222); padding:3px; }
  .thumb.prev { cursor:zoom-in; }
  .thumb.prev:hover { outline:2px solid var(--acc); }
  .modal { position:fixed; inset:0; z-index:50; display:none; align-items:center; justify-content:center;
           padding:24px; background:rgba(6,5,10,.86); }
  .modal.open { display:flex; }
  .modal .box { background:var(--card); border:1px solid var(--line); border-radius:18px;
                padding:16px; text-align:center; max-width:min(680px,94vw); }
  .modal .box h3 { margin:0 0 4px; font-size:15px; }
  .modal .box .meta { color:var(--dim); font-size:12px; margin-bottom:12px; }
  /* The preview mirrors the app's room: flat black, a 5x2 mic-seat stage,
     a header pill and the bottom bar, so the operator judges the gift in
     the exact place it lands. The gift video is a transparent layer over
     the whole room, scaled and moved exactly like the app's overlay. */
  .roomwrap { display:flex; gap:18px; align-items:flex-start; justify-content:center; flex-wrap:wrap; }
  .room { position:relative; width:300px; height:540px; border-radius:18px; overflow:hidden;
          background:#000; box-shadow:inset 0 0 0 1px var(--line), 0 10px 34px rgba(0,0,0,.55);
          display:flex; flex-direction:column; }
  /* "Transparent backdrop": hide the room chrome and show the checkerboard
     so a failed background removal (a solid or haloed clip) is obvious. */
  .room.noro { background:repeating-conic-gradient(#3a3550 0% 25%,#262135 0% 50%) 50%/26px 26px; }
  .room.noro .rm-head, .room.noro .rm-aud, .room.noro .rm-stage,
  .room.noro .rm-chat, .room.noro .rm-bar { visibility:hidden; }
  .rm-head { display:flex; align-items:center; gap:8px; padding:7px 10px; }
  .rm-ic { width:26px; height:26px; border-radius:50%; display:grid; place-items:center;
           color:#fff; font-size:15px; }
  .rm-title { margin-inline-start:auto; display:flex; align-items:center; gap:6px;
              background:#1A1A1A; border-radius:22px; padding:4px 6px 4px 12px; max-width:190px; }
  .rm-title b { font-size:11.5px; font-weight:600; color:#fff; white-space:nowrap;
                overflow:hidden; text-overflow:ellipsis; }
  .rm-title .th { width:26px; height:26px; border-radius:9px; background:#2B2B2B; flex:none; }
  .rm-aud { display:flex; align-items:center; gap:6px; padding:4px 10px; }
  .rm-count { height:30px; border-radius:999px; background:#7C4DFF; color:#fff; font-size:10px;
              font-weight:700; display:flex; align-items:center; gap:4px; padding:0 10px; }
  .rm-av { width:26px; height:26px; border-radius:50%;
           background:linear-gradient(135deg,#3a3550,#5a4a7a);
           box-shadow:inset 0 0 0 1px rgba(255,255,255,.08); }
  .rm-stage { display:grid; grid-template-columns:repeat(5,1fr); gap:8px 6px;
              padding:6px 10px; justify-items:center; }
  .rm-seat { width:46px; height:46px; border-radius:50%; background:rgba(124,77,255,.28);
             border:2px solid rgba(124,77,255,.85); display:grid; place-items:center;
             position:relative; color:#fff; }
  .rm-seat .mic { font-size:19px; opacity:.85; }
  .rm-seat.occ { border-color:rgba(124,77,255,.95);
                 background:linear-gradient(135deg,#5a3aa8,#8a6ae0); }
  .rm-seat .moff { position:absolute; right:-1px; bottom:-1px; width:15px; height:15px;
                   border-radius:50%; background:rgba(20,20,20,.85); display:grid;
                   place-items:center; font-size:9px; }
  .rm-chat { flex:1; padding:8px 10px; display:flex; flex-direction:column; gap:6px; overflow:hidden; }
  .rm-msg { background:rgba(255,255,255,.05); border-radius:10px; padding:5px 8px;
            font-size:10px; color:#c9c9d6; max-width:78%; }
  .rm-bar { display:flex; align-items:center; gap:8px; padding:8px 10px; background:#000; }
  .rm-star { font-size:22px; color:#FFD54F; }
  .rm-comp { flex:1; height:30px; border-radius:16px; background:#1A1A1A; color:#8a8a95;
             font-size:10.5px; display:flex; align-items:center; padding:0 12px; }
  .rm-btn { width:34px; height:34px; border-radius:50%; background:#7C4DFF; color:#fff;
            display:grid; place-items:center; font-size:15px; flex:none; }
  /* The gift layer: transparent, scaled and moved exactly like the app's
     overlay (letterboxed contain, then uniform scale + offset). It never
     captures the pointer, so the sliders underneath stay usable. */
  .room video { position:absolute; inset:0; width:100%; height:100%; object-fit:contain;
                transform-origin:center; pointer-events:none; z-index:5; }
  .room video.fill { object-fit:cover; inset:auto; top:2.5%; left:0; width:100%; height:95%; }
  .ctrls { display:flex; flex-direction:column; gap:8px; width:250px; text-align:right; }
  .ctrls label { font-size:12px; color:var(--dim); display:flex; flex-direction:column; gap:2px; }
  .ctrls input[type=range] { width:100%; }
  .ctrls .chk { flex-direction:row; align-items:center; gap:6px; }
  .ctrls .seg { display:flex; gap:6px; }
  .ctrls .seg button { flex:1; padding:6px 4px; font-size:11.5px; border-radius:9px;
                       background:var(--card); color:var(--txt); border:1px solid var(--line); cursor:pointer; }
  .ctrls .seg button.on { background:#7C4DFF; border-color:#7C4DFF; color:#fff; font-weight:700; }
  .ctrls .sect { font-size:11px; font-weight:800; color:var(--acc); margin-top:4px;
                 border-top:1px solid var(--line); padding-top:8px; }
  .transport { display:flex; align-items:center; gap:6px; }
  .transport button { width:32px; height:30px; border-radius:8px; background:var(--card);
                      color:var(--txt); border:1px solid var(--line); cursor:pointer; font-size:13px; }
  .transport button:hover { border-color:var(--acc); }
  .transport .tc { margin-inline-start:auto; font-size:11px; color:var(--dim); font-variant-numeric:tabular-nums; }
  .modal .actions { justify-content:center; margin-top:14px; }
  .pill { display:inline-block; padding:2px 9px; border-radius:999px; font-size:11.5px; font-weight:700; }
  .t1{background:#2b3a55;color:#a9c8ff}.t2{background:#3a2f55;color:#d0b6ff}
  .t3{background:#4a3a1c;color:#ffd88a}.t4{background:#4d1f2a;color:#ff9db1}
  td input { padding:6px 8px; font-size:13px; }
  .prev { cursor:pointer; }
  .status { margin-top:12px; font-size:13px; min-height:20px; }
  .status.err { color:#ff8f8f; font-weight:700; }
  .status.ok { color:#8fe3a0; font-weight:700; }
  .fname { margin:6px 0 4px; font-size:12.5px; color:var(--dim); }
  .fname.has { color:#8fe3a0; font-weight:700; }
  .ok{color:#7ee2a8}.err{color:#ff8a8a}
  .prog { display:none; align-items:center; gap:10px; margin-top:12px; }
  .prog.on { display:flex; }
  .ptrack { flex:1; height:10px; border-radius:6px; background:#1b2130; overflow:hidden; }
  .pfill { height:100%; width:0; border-radius:6px; background:linear-gradient(90deg,#4b6bfb,#7C4DFF); transition:width .12s linear; }
  .plabel { font-variant-numeric:tabular-nums; font-size:12px; color:var(--dim); min-width:5rem; text-align:center; white-space:nowrap; }
  .prog.busy { flex-wrap:wrap; }
  .prog.busy .plabel { flex:1 1 100%; order:-1; text-align:start; min-width:0; white-space:normal; }
  .prog.busy .ptrack { flex:1 1 100%; }
  .prog.busy .pfill { width:100% !important; background:linear-gradient(90deg,#7C4DFF,#4b6bfb,#7C4DFF); background-size:200% 100%; animation:pslide 1s linear infinite; }
  @keyframes pslide { to { background-position:-200% 0; } }
</style>
</head>
<body>
  <h1>لوحة تحكم الهدايا</h1>
  <div class="sub">ارفع فيديو أو صورة متحركة، وسيُعالَج تلقائيًا (إزالة الخلفية، القص، هوامش شفافة، صوت حسب الفئة)، ثم عايِن قبل النشر. التعديل يظهر في التطبيق عند التشغيل التالي دون تحديثه.</div>
  <div style="margin:10px 0 18px">
    <a href="/download/apk" style="display:inline-block;background:#2f6fed;color:#fff;padding:10px 18px;border-radius:10px;text-decoration:none;font-weight:700">
      ⬇️ تنزيل أحدث تطبيق (APK)
    </a>
    <span class="sub" style="margin-inline-start:10px">أحدث نسخة مبنية تتضمّن كل تعديلات اللوحة</span>
  </div>

  <div class="grid">
    <div class="card">
      <h2>➕ هدية جديدة</h2>
      <label>الملف (فيديو MP4/MOV أو GIF)</label>
      <input type="file" id="file" accept="video/*,image/gif,image/*">
      <div id="fname" class="fname">لم يُختر ملف بعد</div>
      <div class="row">
        <div>
          <label>المعرّف (a-z 0-9 _)</label>
          <input id="id" placeholder="mylion">
        </div>
        <div>
          <label>الرمز التعبيري</label>
          <input id="emoji" value="🦁" maxlength="4">
        </div>
      </div>
      <div class="row">
        <div><label>الاسم بالعربية</label><input id="labelAr" placeholder="أسد الوميض"></div>
        <div><label>الاسم بالإنجليزية</label><input id="labelEn" placeholder="Flash Lion"></div>
      </div>
      <div class="row">
        <div>
          <label>الفئة</label>
          <select id="tier">
            <option value="1">1 — عادي</option>
            <option value="2">2 — مميز</option>
            <option value="3" selected>3 — فاخر</option>
            <option value="4">4 — أسطوري</option>
          </select>
        </div>
        <div><label>السعر (نجوم)</label><input id="price" type="number" value="1000" min="0"></div>
      </div>
      <div class="row">
        <div>
          <label>الخلفية</label>
          <select id="bg">
            <option value="auto">تلقائي (يكتشف الخلفية)</option>
            <option value="black">خلفية سوداء</option>
            <option value="green">خلفية خضراء</option>
            <option value="blue">خلفية زرقاء</option>
            <option value="white">خلفية بيضاء</option>
            <option value="none">بلا إزالة (المصدر شفاف)</option>
          </select>
        </div>
        <div>
          <label>الحجم على الشاشة</label>
          <select id="scale">
            <option value="0.7">70%</option>
            <option value="0.8">80%</option>
            <option value="0.9" selected>90%</option>
            <option value="1.0">100%</option>
          </select>
        </div>
      </div>
      <div class="row">
        <div>
          <label>دمج الحواف في الغرفة</label>
          <select id="blend">
            <option value="off" selected>إيقاف — حدّ نظيف بلا أي تلاشٍ (المشهد كما هو)</option>
            <option value="auto">تلقائي — يتلاشى المشهد عند حدوده بزوايا مستديرة</option>
            <option value="soft">خفيف — تلاشٍ رقيق</option>
            <option value="strong">قوي — تلاشٍ أوسع وزوايا أكثر استدارة</option>
          </select>
        </div>
        <div>
          <label>الدقة</label>
          <input id="size" type="number" value="480" min="240" max="1080">
        </div>
      </div>
      <div class="row">
        <div><label>الهامش الشفاف</label><input id="pad" type="number" value="0.08" step="0.01" min="0" max="0.3"></div>
        <div>
          <label>شكل المشهد</label>
          <select id="fit">
            <option value="cover">ملء الغرفة بالكامل (يغطي الشاشة، تُقصّ الأطراف)</option>
            <option value="aspect" selected>كامل بنسبة الفيديو (عمودي يبقى عمودياً)</option>
            <option value="square">مربّع 1:1</option>
          </select>
        </div>
      </div>
      <div class="row">
        <div>
          <label>طريقة فصل الهدف</label>
          <select id="matte">
            <option value="key" selected>مفتاح لوني/سطوع فقط — سريع جدًا (الأفضل لخلفية سادة)</option>
            <option value="auto">تلقائي (دلالي إن توفر، وإلا المفتاح اللوني)</option>
            <option value="birefnet">دلالي BiRefNet (الأفضل لهدف غامق أو خلفية معقّدة، أبطأ)</option>
          </select>
        </div>
        <div>
          <label>مدة الفيديو الكاملة</label>
          <select id="maxSeconds">
            <option value="0" selected>كامل المدة (بلا قصّ)</option>
            <option value="6">6 ثوانٍ</option>
            <option value="10">10 ثوانٍ</option>
            <option value="15">15 ثانية</option>
            <option value="20">20 ثانية</option>
          </select>
        </div>
      </div>
      <div class="row">
        <div>
          <label><input id="noCrop" type="checkbox" checked> لا تقصّ المشهد على الهدف فقط</label>
        </div>
      </div>
      <div class="hint">«إيقاف» يعرض المشهد كما هو بلا أي تلاشٍ عند الحدود — اختره إن كنت ترى إطاراً حول المشهد. «تلقائي» يجعل المشهد الكامل يتلاشى عند حدوده بزوايا مستديرة (للمشاهد ذات الخلفية الحقيقية). اختر «كامل بنسبة الفيديو» + «لا تقصّ» ليظهر المشهد كاملاً بنسبته الحقيقية وبكامل مدته.</div>
      <div class="actions">
        <button id="go">معالجة ومعاينة</button>
        <button class="ghost" id="reset">تفريغ</button>
      </div>
      <div class="prog" id="prog">
        <div class="ptrack"><div class="pfill" id="pfill"></div></div>
        <span class="plabel" id="plabel">0%</span>
      </div>
      <div class="status" id="status"></div>
      <div class="hint">المعالجة قد تستغرق من 20 ثانية إلى دقيقتين حسب طول المقطع. لا تغلق الصفحة.</div>
    </div>

    <div class="card">
      <h2>🎬 الهدايا المنشورة</h2>
      <div id="drafts"></div>
      <div id="list">جارٍ التحميل…</div>
    </div>
  </div>

  <div class="modal" id="viewer">
    <div class="box">
      <h3 id="vTitle"></h3>
      <div class="meta" id="vMeta"></div>
      <div class="roomwrap">
        <div class="room" id="vRoom">
          <div class="rm-head">
            <div class="rm-ic">↩</div>
            <div class="rm-ic">⋮</div>
            <div class="rm-title"><b id="rmName">الغرفة</b><span class="th"></span></div>
          </div>
          <div class="rm-aud">
            <span class="rm-count">👤 <span id="rmCount">12</span></span>
            <span class="rm-av"></span><span class="rm-av"></span><span class="rm-av"></span>
            <span class="rm-av"></span><span class="rm-av"></span>
          </div>
          <div class="rm-stage" id="rmStage"></div>
          <div class="rm-chat">
            <div class="rm-msg">مرحبًا بالجميع 👋</div>
            <div class="rm-msg" style="align-self:flex-end">أهلًا بك!</div>
            <div class="rm-msg">أرسلوا هدية لتظهر هنا</div>
          </div>
          <div class="rm-bar">
            <span class="rm-star">★</span>
            <span class="rm-comp">قل شيئًا…</span>
            <span class="rm-btn">🎤</span>
            <span class="rm-btn">🔊</span>
          </div>
          <video id="vVid" playsinline autoplay loop muted></video>
        </div>
        <div class="ctrls">
          <div class="sect">التشغيل والفحص</div>
          <div class="transport">
            <button id="tStepB" title="إطار للخلف">⏮</button>
            <button id="tPlay" title="تشغيل/إيقاف">⏸</button>
            <button id="tStepF" title="إطار للأمام">⏭</button>
            <button id="tLoop" title="تكرار" class="on">🔁</button>
            <span class="tc" id="tTime">0.00 / 0.00</span>
          </div>
          <label>الموضع الزمني <b id="lTime"></b>
            <input type="range" id="cTime" min="0" max="1000" step="1" value="0">
          </label>
          <label>سرعة التشغيل <b id="lSpeed"></b>
            <input type="range" id="cSpeed" min="0.25" max="2" step="0.05" value="1">
          </label>
          <div class="sect">الوضع على الغرفة</div>
          <label>طريقة الملء</label>
          <div class="seg" id="segFit">
            <button data-fit="contain" class="on">بالحجم الأصلي</button>
            <button data-fit="fill">يغطي 95% من الطول</button>
          </div>
          <label>الحجم <b id="lScale"></b>
            <input type="range" id="cScale" min="0.4" max="1.2" step="0.05">
          </label>
          <label>الموضع الأفقي (يمين/يسار) <b id="lPosX"></b>
            <input type="range" id="cPosX" min="-0.5" max="0.5" step="0.02">
          </label>
          <label>الموضع العمودي (أسفل/أعلى) <b id="lPosY"></b>
            <input type="range" id="cPosY" min="-0.5" max="0.5" step="0.02">
          </label>
          <label>الشفافية <b id="lAlpha"></b>
            <input type="range" id="cAlpha" min="0.1" max="1" step="0.05" value="1">
          </label>
          <label>تكبير/تصغير (بكسل) <b id="lZoom"></b>
            <input type="range" id="cZoom" min="0.5" max="2" step="0.02" value="1">
          </label>
          <div class="sect">عرض</div>
          <div class="seg">
            <button id="tRoom" class="on">إظهار الغرفة</button>
            <button id="tBg">خلفية شفافة</button>
          </div>
          <label class="chk"><input type="checkbox" id="cPlay" checked> تشغيل الصوت في المعاينة</label>
          <label class="chk"><input type="checkbox" id="cGrid"> شبكة المحاذاة</label>
          <div class="hint">شغّل وأوقف، تنقّل إطارًا بإطار، غيّر السرعة، واضبط الحجم والموضع والشفافية — ثم اضغط حفظ في الجدول ليثبت الحجم والموضع في التطبيق.</div>
        </div>
      </div>
      <div class="actions">
        <button id="vSave">حفظ الحجم والموضع</button>
        <button class="ghost" id="vClose">إغلاق</button>
      </div>
    </div>
  </div>

<script>
const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));

// Big playable preview. The WebM carries alpha, so the checkerboard behind
// the player shows through wherever the background was keyed out — the only
// honest way to judge whether the removal actually worked. The MP4 is an
// opaque fallback for browsers without VP9 alpha support.
//
// The sliders mirror the app's scale + position fields, so the operator can
// place the gift over a phone-shaped room and see the result before saving.
let previewGift = null;
// Advanced preview state. `fit` mirrors the app's renderer: `contain`
// letterboxes a clip (its own shape), `fill` makes a vertical clip cover 95%
// of the phone height. `zoom` is a pure preview magnifier (in CSS px) that
// lets the operator inspect edges for a halo without touching the saved
// scale. `alpha` fades the whole clip to check it sits over the room.
let pvFit = 'contain';
function buildRoom(){
  const stage = $('#rmStage');
  if (!stage || stage.childElementCount) return;
  for (let i = 0; i < 10; i++){
    const seat = document.createElement('div');
    seat.className = 'rm-seat' + (i < 3 ? ' occ' : '');
    seat.innerHTML = i < 3
      ? '<span class="mic">' + ['🦁','🐯','🐧'][i] + '</span>' + (i === 1 ? '<span class="moff">🔇</span>' : '')
      : '<span class="mic">🎙</span>';
    stage.appendChild(seat);
  }
  const grid = document.createElement('div');
  grid.id = 'rmGrid';
  grid.style.cssText = 'position:absolute;inset:0;z-index:6;pointer-events:none;display:none;' +
    'background:linear-gradient(rgba(255,255,255,.18) 1px,transparent 1px) 0 0/100% 33.33%,' +
    'linear-gradient(90deg,rgba(255,255,255,.18) 1px,transparent 1px) 0 0/33.33% 100%';
  $('#vRoom').appendChild(grid);
}
function applyView(){
  const v = $('#vVid');
  const sc = Number($('#cScale').value), px = Number($('#cPosX').value), py = Number($('#cPosY').value);
  const alpha = Number($('#cAlpha').value), zoom = Number($('#cZoom').value);
  v.style.transform = `translate(${px * 300}px, ${py * 540}px) scale(${sc * zoom})`;
  v.style.opacity = alpha;
  v.classList.toggle('fill', pvFit === 'fill');
  $('#lScale').textContent = Math.round(sc * 100) + '%';
  $('#lPosX').textContent = px.toFixed(2);
  $('#lPosY').textContent = py.toFixed(2);
  $('#lAlpha').textContent = Math.round(alpha * 100) + '%';
  $('#lZoom').textContent = Math.round(zoom * 100) + '%';
}
function syncTime(){
  const v = $('#vVid');
  const d = v.duration || 0;
  if (!d) return;
  $('#cTime').value = Math.round((v.currentTime / d) * 1000);
  $('#lTime').textContent = v.currentTime.toFixed(2) + 's';
  $('#tTime').textContent = v.currentTime.toFixed(2) + ' / ' + d.toFixed(2);
}
function openPreview(g){
  if (!g || !g.preview){ alert('لا توجد معاينة لهذه الهدية بعد.'); return; }
  previewGift = g;
  buildRoom();
  const v = $('#vVid');
  const sources = [g.preview, g.previewMp4].filter(Boolean).map(u => '/' + u);
  v.innerHTML = '';
  for (const src of sources){
    const s = document.createElement('source');
    s.src = src;
    s.type = src.includes('.webm') ? 'video/webm' : 'video/mp4';
    v.appendChild(s);
  }
  // A vertical clip is meant to fill the phone screen; show that by default.
  const vertical = (g.videoHeight || 0) > (g.videoWidth || 0);
  pvFit = vertical ? 'fill' : 'contain';
  document.querySelectorAll('#segFit button').forEach(b => b.classList.toggle('on', b.dataset.fit === pvFit));
  v.muted = !$('#cPlay').checked;
  v.loop = $('#tLoop').classList.contains('on');
  v.playbackRate = Number($('#cSpeed').value);
  $('#vTitle').textContent = g.labelAr || g.id || '';
  $('#vMeta').textContent = [g.id, g.tier != null ? 'فئة ' + g.tier : '', g.status || ''].filter(Boolean).join(' · ');
  $('#cScale').value = g.scale ?? 1;
  $('#cPosX').value = g.posX ?? 0;
  $('#cPosY').value = g.posY ?? 0;
  $('#cAlpha').value = 1;
  $('#cZoom').value = 1;
  $('#tPlay').textContent = '⏸';
  applyView();
  $('#viewer').classList.add('open');
  v.load();
  v.play().catch(() => {});
}
['cScale','cPosX','cPosY','cAlpha','cZoom'].forEach(id => { $('#'+id).oninput = applyView; });
$('#cPlay').onchange = () => { $('#vVid').muted = !$('#cPlay').checked; };
$('#cGrid').onchange = () => { $('#rmGrid').style.display = $('#cGrid').checked ? 'block' : 'none'; };
$('#cSpeed').oninput = () => {
  const s = Number($('#cSpeed').value);
  $('#vVid').playbackRate = s;
  $('#lSpeed').textContent = s.toFixed(2) + '×';
};
$('#cTime').oninput = () => {
  const v = $('#vVid');
  if (v.duration) v.currentTime = (Number($('#cTime').value) / 1000) * v.duration;
};
$('#vVid').addEventListener('timeupdate', syncTime);
$('#vVid').addEventListener('loadedmetadata', syncTime);
$('#tPlay').onclick = () => {
  const v = $('#vVid');
  if (v.paused){ v.play().catch(() => {}); $('#tPlay').textContent = '⏸'; }
  else { v.pause(); $('#tPlay').textContent = '▶'; }
};
function stepFrame(dir){
  const v = $('#vVid');
  v.pause(); $('#tPlay').textContent = '▶';
  // Assume a 30 fps authored clip; a step is one frame at that rate.
  v.currentTime = Math.max(0, Math.min(v.duration || 0, v.currentTime + dir / 30));
  syncTime();
}
$('#tStepF').onclick = () => stepFrame(1);
$('#tStepB').onclick = () => stepFrame(-1);
$('#tLoop').onclick = () => {
  const on = $('#tLoop').classList.toggle('on');
  $('#vVid').loop = on;
};
document.querySelectorAll('#segFit button').forEach(b => {
  b.onclick = () => {
    pvFit = b.dataset.fit;
    document.querySelectorAll('#segFit button').forEach(x => x.classList.toggle('on', x === b));
    applyView();
  };
});
$('#tRoom').onclick = () => {
  const on = $('#tRoom').classList.toggle('on');
  $('#tBg').classList.toggle('on', !on);
  $('#vRoom').classList.toggle('noro', !on);
};
$('#tBg').onclick = () => {
  const on = $('#tBg').classList.toggle('on');
  $('#tRoom').classList.toggle('on', !on);
  $('#vRoom').classList.toggle('noro', on);
};
$('#vSave').onclick = async () => {
  if (!previewGift) return;
  const body = {
    id: previewGift.id,
    scale: Number($('#cScale').value),
    posX: Number($('#cPosX').value),
    posY: Number($('#cPosY').value),
  };
  const res = await (await fetch('/api/gift', {method:'POST', body: JSON.stringify(body)})).json();
  if (!res.ok) { alert('فشل الحفظ: ' + (res.error||'')); return; }
  previewGift.scale = body.scale; previewGift.posX = body.posX; previewGift.posY = body.posY;
  setStatus(res.synced
    ? '✅ حُفظ الحجم والموضع — يظهر في التطبيق عند التشغيل التالي.'
    : '⚠️ حُفظ محليًا فقط — ' + (res.note||'لم تصل السحابة'), res.synced);
  refresh();
};
function closePreview(){ $('#viewer').classList.remove('open'); const v=$('#vVid'); v.pause(); v.innerHTML=''; }
$('#vClose').onclick = closePreview;
$('#viewer').onclick = (e) => { if (e.target.id === 'viewer') closePreview(); };
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closePreview(); });

function draftRow(g){
  return `<div class="draft" data-id="${esc(g.id)}" style="display:flex;gap:14px;align-items:center;border:1px solid var(--line);border-radius:14px;padding:12px;margin-bottom:12px">
    ${g.poster ? `<img class="thumb prev" style="width:96px;height:96px" src="/${esc(g.poster)}" alt="معاينة" data-prev="${esc(g.id)}">` : ''}
    <div style="flex:1">
      <div style="font-weight:800">${esc(g.labelAr||g.id)} <span class="pill t${g.tier}">فئة ${g.tier}</span></div>
      <div style="color:var(--dim);font-size:12px;margin-top:4px">${esc(g.id)} · ${g.price} نجمة · حجم ${g.scale} — <b>مسودّة، لم تُنشر بعد</b></div>
    </div>
    <div style="display:flex;gap:8px">
      <button data-act="pub">نشر للتطبيق</button>
      <button class="ghost" data-act="dis">إلغاء</button>
    </div>
  </div>`;
}

async function refresh(){
  const r = await fetch('/api/state');
  const st = await r.json();
  const drafts = (st.drafts||[]);
  $('#drafts').innerHTML = drafts.length
    ? `<h3 style="font-size:13.5px;color:var(--dim);margin:4px 0 10px">بانتظار النشر (${drafts.length})</h3>` + drafts.map(draftRow).join('')
    : '';
  $('#drafts').querySelectorAll('.draft').forEach(d => {
    const id = d.dataset.id;
    const g = drafts.find(x => x.id === id);
    const t = d.querySelector('[data-prev]');
    if (t) t.onclick = () => openPreview(g);
    d.querySelector('[data-act=pub]').onclick = async () => {
      const res = await (await fetch('/api/publish', {method:'POST', body: JSON.stringify({id})})).json();
      if (!res.ok) { alert('فشل النشر: ' + (res.error||'')); return; }
      // Say plainly whether the gift actually reached the app. The app reads
      // the catalog from Cloudflare, so a local-only publish would look
      // successful here yet never show up on the phone.
      setStatus(res.synced
        ? '✅ نُشر وتمت المزامنة مع السحابة — يظهر في التطبيق عند التشغيل التالي.'
        : '⚠️ نُشر محليًا فقط — ' + (res.note||''), res.synced);
      refresh();
    };
    d.querySelector('[data-act=dis]').onclick = async () => {
      await fetch('/api/discard', {method:'POST', body: JSON.stringify({id})});
      refresh();
    };
  });
  const rows = (st.gifts||[]).map(g => `
    <tr data-id="${esc(g.id)}">
      <td><img class="thumb" src="/assets/${esc(g.icon||'')}" onerror="this.style.visibility='hidden'"></td>
      <td><div style="font-weight:700">${esc(g.labelAr||g.id)}</div><div style="color:var(--dim);font-size:11.5px">${esc(g.id)}</div></td>
      <td><span class="pill t${g.tier}">${g.tier}</span></td>
      <td><input type="number" value="${g.price??1}" min="0" style="width:84px" data-k="price"></td>
      <td><input type="number" value="${g.scale??1}" min="0.4" max="1.2" step="0.05" style="width:74px" data-k="scale"></td>
      <td style="white-space:nowrap">س<input type="number" value="${g.posX??0}" min="-0.5" max="0.5" step="0.02" style="width:66px" data-k="posX"> ص<input type="number" value="${g.posY??0}" min="-0.5" max="0.5" step="0.02" style="width:66px" data-k="posY"></td>
      <td>${g.poster ? `<img class="thumb prev" src="/${esc(g.poster)}" alt="معاينة" data-prev="${esc(g.id)}">` : '<span style="color:var(--dim)">—</span>'}</td>
      <td style="white-space:nowrap">
        <button class="ghost" data-act="save">حفظ</button>
        <button class="ghost" data-act="del">حذف</button>
      </td>
    </tr>`).join('');
  $('#list').innerHTML = `<table>
    <thead><tr><th>الأيقونة</th><th>الهدية</th><th>الفئة</th><th>السعر</th><th>الحجم</th><th>الموضع</th><th>معاينة</th><th></th></tr></thead>
    <tbody>${rows || '<tr><td colspan="8" style="color:var(--dim)">لا توجد هدايا منشورة بعد</td></tr>'}</tbody></table>
    <div class="hint">الإصدار الحالي: <b>${st.version||0}</b> — التعديلات تظهر في التطبيق عند التشغيل التالي.</div>`;
  $('#list').querySelectorAll('tr[data-id]').forEach(tr => {
    const id = tr.dataset.id;
    const gg = (st.gifts || []).find(x => x.id === id);
    const tp = tr.querySelector('[data-prev]');
    if (tp) tp.onclick = () => openPreview(gg);
    tr.querySelector('[data-act=save]').onclick = async () => {
      const body = { id };
      tr.querySelectorAll('input[data-k]').forEach(i => body[i.dataset.k] = Number(i.value));
      const res = await (await fetch('/api/gift', {method:'POST', body: JSON.stringify(body)})).json();
      if (!res.ok) { alert('فشل الحفظ: ' + (res.error||'')); return; }
      setStatus(res.synced
        ? '✅ حُفظت التعديلات — تظهر في التطبيق عند التشغيل التالي.'
        : '⚠️ حُفظت محليًا فقط — ' + (res.note||'لم تصل السحابة'), res.synced);
      refresh();
    };
    tr.querySelector('[data-act=del]').onclick = async () => {
      if (!confirm('حذف هذه الهدية؟')) return;
      await fetch('/api/delete', {method:'POST', body: JSON.stringify({id})});
      refresh();
    };
  });
}

$('#go').onclick = () => {
  try {
    const f = $('#file').files[0];
    // Always do something visible: if no file is chosen yet, open the picker
    // instead of leaving the operator staring at a button that "did nothing".
    if (!f) { $('#file').click(); return setStatus('⚠️ اختر ملف الفيديو أولًا ثم اضغط «معالجة ومعاينة»', false); }
    const id = $('#id').value.trim().toLowerCase();
    if (!/^[a-z0-9_]{1,32}$/.test(id)) { $('#id').focus(); return setStatus('⚠️ اكتب المعرّف بحروف إنجليزية صغيرة وأرقام و _ فقط (مثال: bird)', false); }
    const fd = new FormData();
    fd.append('file', f);
    fd.append('id', id);
    // A missing field (a stale page from before a form change) must not abort
    // the upload with a TypeError; send whatever the form actually has.
    ['emoji','labelAr','labelEn','tier','price','bg','blend','scale','size','pad','fit','matte','maxSeconds']
      .forEach(k => { const el = $('#'+k); if (el) fd.append(k, el.value); });
    const nc = $('#noCrop');
    fd.append('noCrop', nc && nc.checked ? '1' : '0');
    const xhr = new XMLHttpRequest();
    // One bar, two honest phases. The byte transfer fills 0..100 on its own;
    // once the server has the file it runs minutes of work, so the page polls
    // /api/job and drives the same bar from the *real* stage the pipeline is
    // in (decode → background removal → crop → encode), not a fake sweep.
    const setProg = (pct, label) => {
      const p = $('#prog'); p.classList.add('on'); p.classList.remove('busy');
      $('#pfill').style.width = Math.max(0, Math.min(100, pct)) + '%';
      $('#plabel').textContent = label || (Math.round(pct) + '%');
    };
    const hideProg = () => { $('#prog').classList.remove('on', 'busy'); $('#pfill').style.width = '0%'; $('#plabel').textContent = '0%'; };
    // Always return the button to a usable state, however the request ends.
    // A dropped connection (server restart, laptop sleep) used to leave the
    // button disabled forever, which looked like the button itself was dead.
    const done = (msg, ok) => { $('#go').disabled = false; hideProg(); setStatus(msg, ok); };
    const pollJob = (jid) => new Promise((resolve, reject) => {
      const tick = () => {
        const j = new XMLHttpRequest();
        j.open('GET', '/api/job?id=' + encodeURIComponent(jid));
        j.onload = () => {
          let s = {}; try { s = JSON.parse(j.responseText); } catch(e){}
          if (j.status !== 200) return reject(new Error(s.error || ('HTTP ' + j.status)));
          if (s.error) return reject(new Error(s.error));
          // Upload owns 0..50 of the bar; the pipeline owns 50..100, so the
          // operator sees one continuous fill across the whole operation.
          const pct = 50 + (Number(s.pct || 0) / 2);
          setProg(pct, s.label || 'جارٍ المعالجة…');
          setStatus((s.label || 'جارٍ المعالجة…'), true);
          if (s.done) return s.error ? reject(new Error(s.error)) : resolve(s);
          setTimeout(tick, 700);
        };
        j.onerror = () => reject(new Error('انقطع الاتصال أثناء المعالجة'));
        j.send();
      };
      tick();
    });
    xhr.open('POST', '/api/upload');
    xhr.timeout = 10 * 60 * 1000;
    $('#go').disabled = true;
    setProg(0, '0%');
    setStatus('جارٍ الرفع…', true);
    // Bytes fill the first half, leaving the second half for the pipeline.
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) setProg((e.loaded/e.total)*50, 'رفع ' + Math.round((e.loaded/e.total)*100) + '%'); };
    xhr.upload.onload = () => { setProg(50, 'اكتمل الرفع — جارٍ إزالة الخلفية والقص والترميز…'); };
    xhr.onload = async () => {
      let res = {}; try { res = JSON.parse(xhr.responseText); } catch(e){}
      if (xhr.status !== 200 || !res.ok || !res.job) {
        return done('❌ فشل: ' + (res.error || ('HTTP ' + xhr.status)), false);
      }
      try {
        const job = await pollJob(res.job);
        done('✅ جاهزة للمعاينة — راجعها بالأعلى ثم اضغط «نشر للتطبيق».', true);
        refresh();
        // Show the result immediately: the operator's whole reason for
        // uploading is to see whether the background came out clean.
        try { openPreview(Object.assign({}, job.draft, {status:'مسودّة'})); } catch(e) {}
      } catch (err) {
        done('❌ فشل: ' + (err && err.message ? err.message : err), false);
      }
    };
    xhr.onerror = () => done('❌ انقطع الاتصال — أعد تحميل الصفحة ثم حاول مرة أخرى', false);
    xhr.ontimeout = () => done('❌ انتهت المهلة — الملف كبير جدًا أو الخادم مشغول', false);
    xhr.send(fd);
  } catch (err) {
    $('#go').disabled = false; hideProg();
    setStatus('❌ خطأ: ' + (err && err.message ? err.message : err) + ' — حدّث الصفحة بـ Ctrl+Shift+R', false);
  }
};

$('#reset').onclick = () => { ['id','labelAr','labelEn'].forEach(k => $('#'+k).value=''); $('#file').value=''; setFname(); setStatus('', true); };
function setStatus(t, ok){
  const s=$('#status'); s.textContent=t; s.className='status ' + (ok?'ok':'err');
  // A message the operator never sees is the same as no message: bring it
  // into view on every update, since the button sits above a long form.
  if (t) s.scrollIntoView({behavior:'smooth', block:'center'});
}
function setFname(){
  const f = $('#file').files[0], el = $('#fname');
  if (f) { el.textContent = '✔ الملف: ' + f.name + '  (' + (f.size/1048576).toFixed(1) + ' م.ب)'; el.className = 'fname has'; }
  else { el.textContent = 'لم يُختر ملف بعد'; el.className = 'fname'; }
}
$('#file').onchange = setFname;
// Surface a real script error instead of failing silently, so a broken page
// reports itself rather than looking like a dead button. Resource-load
// failures (a missing icon) are not script errors and must not be reported.
window.addEventListener('error', (e) => {
  if (e && e.target && e.target !== window && e.target.tagName) return;
  setStatus('❌ خطأ في الصفحة: ' + ((e && e.message) || 'unknown'), false);
});
// A restored page (back/forward cache) can come back with the button still
// disabled from an upload that was in flight, which reads as a dead button.
window.addEventListener('pageshow', () => { $('#go').disabled = false; $('#prog').classList.remove('on','busy'); });
refresh();
</script>
</body>
</html>
"""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=12001, help="primary port")
    ap.add_argument(
        "--also-port",
        type=int,
        action="append",
        default=[],
        help="extra port to serve the same dashboard on (repeatable)",
    )
    ap.add_argument("--host", default="0.0.0.0")
    args = ap.parse_args()
    ensure_dirs()
    if not STATE.exists():
        save_state(default_state())
    # Backfill playable previews for gifts authored before previews existed.
    for gift in load_state().get("gifts", []):
        build_preview_from_clip(gift)

    ports = [args.port, *[p for p in args.also_port if p != args.port]]
    servers = []
    for port in ports:
        try:
            servers.append(ThreadingHTTPServer((args.host, port), Handler))
            print(f"gift dashboard on http://{args.host}:{port}  (data: {DATA})")
        except OSError as exc:
            # A dead port (e.g. one work host is down) must not take the
            # whole dashboard with it: keep serving the others.
            print(f"port {port} unavailable: {exc}", file=sys.stderr)
    if not servers:
        return 1
    for srv in servers[1:]:
        threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        servers[0].serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        for srv in servers:
            srv.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
