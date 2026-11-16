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
    """
    proc = subprocess.run(
        [ffprobe_exe(), "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height", "-of", "json", str(video)],
        capture_output=True, text=True,
    )
    try:
        stream = json.loads(proc.stdout)["streams"][0]
        return {"width": int(stream["width"]), "height": int(stream["height"]) // 2}
    except Exception:
        return {"width": 0, "height": 0}


def ffprobe_exe() -> str:
    """ffprobe shipped next to imageio's ffmpeg, falling back to the PATH."""
    import imageio_ffmpeg

    ff = Path(imageio_ffmpeg.get_ffmpeg_exe())
    probe = ff.with_name(ff.name.replace("ffmpeg", "ffprobe"))
    return str(probe) if probe.exists() else "ffprobe"


def run_tool(args: list[str]) -> str:
    proc = subprocess.run(args, capture_output=True, text=True, timeout=1800)
    if proc.returncode != 0:
        raise RuntimeError((proc.stderr or proc.stdout or "tool failed").strip()[-2000:])
    return proc.stdout.strip()


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
) -> dict:
    """Run the authoring pipeline and return the published asset paths."""
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
            ]
        )
        run_tool(
            [
                PYTHON, str(TOOLS / "make_gift_sound.py"),
                "--id", gift_id, "--tier", str(tier),
                "-o", str(sound_tmp),
            ]
        )

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
        """Multipart upload → process → create or replace a gift."""
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
        suffix = Path(filename).suffix or ".mp4"
        with tempfile.TemporaryDirectory() as td:
            src = Path(td) / f"upload{suffix}"
            src.write_bytes(payload)
            assets = process_upload(
                src, gift_id, tier, bg, size, pad, blend,
                fit=fit, matte=matte, no_crop=no_crop, max_seconds=max_seconds,
            )

        state = load_state()
        draft = next((d for d in state["drafts"] if d["id"] == gift_id), None)
        if draft is None:
            draft = {"id": gift_id}
            state["drafts"].append(draft)
        draft.update(
            {
                "emoji": fields.get("emoji") or draft.get("emoji") or "🎁",
                "labelAr": fields.get("labelAr") or draft.get("labelAr") or gift_id,
                "labelEn": fields.get("labelEn") or draft.get("labelEn") or gift_id,
                "tier": tier,
                "price": int(fields.get("price") or draft.get("price") or 1),
                "scale": float(fields.get("scale") or draft.get("scale") or 1.0),
                "posX": float(fields.get("posX") or draft.get("posX") or 0.0),
                "posY": float(fields.get("posY") or draft.get("posY") or 0.0),
                "bg": bg,
                "blend": blend,
                "fit": fit,
                "matte": matte,
                "noCrop": no_crop,
                "maxSeconds": max_seconds,
                "size": size,
                "pad": pad,
                **assets,
            }
        )
        save_state(state)
        # Previews are built during upload, so attach their URLs to the draft
        # for the response that opens the player.
        draft.update(clip_preview(draft))
        save_state(state)
        # Nothing is live yet: the draft must be reviewed and published.
        return self.send_json({"ok": True, "draft": draft})

    def api_publish(self):
        """Promote a draft into the live catalog."""
        payload = json.loads(self.read_body())
        state = load_state()
        draft = next((d for d in state["drafts"] if d["id"] == payload.get("id")), None)
        if draft is None:
            return self.send_json({"error": "unknown draft"}, 404)
        if not draft.get("svga") and not draft.get("video"):
            return self.send_json({"error": "draft has no clip"}, 400)
        # Search only the live gifts: find_gift() also scans drafts, so it
        # would return the draft itself and the gift would never be added to
        # the catalog — publishing would silently drop it.
        gift = next((g for g in state["gifts"] if g["id"] == draft["id"]), None)
        if gift is None:
            gift = {"id": draft["id"]}
            state["gifts"].append(gift)
        gift.update({k: v for k, v in draft.items() if k != "preview"})
        state["drafts"] = [d for d in state["drafts"] if d["id"] != draft["id"]]
        save_state(state)
        return self.send_json({"ok": True, "gift": gift})

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
        return self.send_json({"ok": True, "gift": gift})

    def api_delete(self):
        payload = json.loads(self.read_body())
        state = load_state()
        before = len(state["gifts"])
        state["gifts"] = [g for g in state["gifts"] if g["id"] != payload.get("id")]
        if len(state["gifts"]) == before:
            return self.send_json({"error": "unknown gift"}, 404)
        save_state(state)
        return self.send_json({"ok": True})


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
  /* Checkerboard behind the clip: transparent pixels show the pattern,
     so a failed background removal is immediately obvious. The room is a
     phone-shaped stage so the operator can place the gift exactly where
     it will land in the app. */
  .roomwrap { display:flex; gap:18px; align-items:flex-start; justify-content:center; flex-wrap:wrap; }
  .room { position:relative; width:252px; height:448px; border-radius:16px; overflow:hidden;
          background:repeating-conic-gradient(#3a3550 0% 25%,#262135 0% 50%) 50%/26px 26px;
          box-shadow:inset 0 0 0 1px var(--line); }
  /* The clip keeps its own aspect ratio inside the phone-shaped room, exactly
     as the app draws it (fit-centre), so a vertical scene reads as a full
     vertical screen here instead of being stretched into a square. */
  .room video { position:absolute; inset:0; width:100%; height:100%; object-fit:contain;
                transform-origin:center; }
  .ctrls { display:flex; flex-direction:column; gap:10px; width:236px; text-align:right; }
  .ctrls label { font-size:12px; color:var(--dim); display:flex; flex-direction:column; gap:2px; }
  .ctrls input[type=range] { width:100%; }
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
  progress { width:100%; height:8px; }
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
            <option value="auto" selected>تلقائي (دلالي إن توفر، وإلا المفتاح اللوني)</option>
            <option value="birefnet">دلالي BiRefNet (الأفضل لهدف غامق)</option>
            <option value="key">مفتاح لوني/سطوع فقط</option>
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
      <progress id="bar" style="display:none"></progress>
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
        <div class="room" id="vRoom"><video id="vVid" playsinline autoplay loop muted></video></div>
        <div class="ctrls">
          <label>الحجم <b id="lScale"></b>
            <input type="range" id="cScale" min="0.4" max="1.2" step="0.05">
          </label>
          <label>الموضع الأفقي (يمين/يسار) <b id="lPosX"></b>
            <input type="range" id="cPosX" min="-0.5" max="0.5" step="0.02">
          </label>
          <label>الموضع العمودي (أسفل/أعلى) <b id="lPosY"></b>
            <input type="range" id="cPosY" min="-0.5" max="0.5" step="0.02">
          </label>
          <div class="hint">حرّك المؤشر لترى الهدية مكانها فوق الغرفة، ثم اضغط حفظ في الجدول.</div>
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
function applyPlacement(){
  const v = $('#vVid');
  const sc = Number($('#cScale').value), px = Number($('#cPosX').value), py = Number($('#cPosY').value);
  v.style.transform = `translate(${px * 252}px, ${py * 448}px) scale(${sc})`;
  $('#lScale').textContent = Math.round(sc * 100) + '%';
  $('#lPosX').textContent = px.toFixed(2);
  $('#lPosY').textContent = py.toFixed(2);
}
function openPreview(g){
  if (!g || !g.preview){ alert('لا توجد معاينة لهذه الهدية بعد.'); return; }
  previewGift = g;
  const v = $('#vVid');
  const sources = [g.preview, g.previewMp4].filter(Boolean).map(u => '/' + u);
  v.innerHTML = '';
  for (const src of sources){
    const s = document.createElement('source');
    s.src = src;
    s.type = src.includes('.webm') ? 'video/webm' : 'video/mp4';
    v.appendChild(s);
  }
  $('#vTitle').textContent = g.labelAr || g.id || '';
  $('#vMeta').textContent = [g.id, g.tier != null ? 'فئة ' + g.tier : '', g.status || ''].filter(Boolean).join(' · ');
  $('#cScale').value = g.scale ?? 1;
  $('#cPosX').value = g.posX ?? 0;
  $('#cPosY').value = g.posY ?? 0;
  applyPlacement();
  $('#viewer').classList.add('open');
  v.load();
  v.play().catch(() => {});
}
['cScale','cPosX','cPosY'].forEach(id => { $('#'+id).oninput = applyPlacement; });
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
  setStatus('✅ حُفظ الحجم والموضع.', true);
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
      if (!res.ok) alert('فشل النشر: ' + (res.error||''));
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
      await fetch('/api/gift', {method:'POST', body: JSON.stringify(body)});
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
    // Always return the button to a usable state, however the request ends.
    // A dropped connection (server restart, laptop sleep) used to leave the
    // button disabled forever, which looked like the button itself was dead.
    const done = (msg, ok) => { $('#go').disabled = false; $('#bar').style.display = 'none'; setStatus(msg, ok); };
    xhr.open('POST', '/api/upload');
    xhr.timeout = 10 * 60 * 1000;
    $('#bar').style.display = 'block';
    $('#go').disabled = true;
    setStatus('جارٍ المعالجة… (إزالة الخلفية، القص، الترميز، الصوت)', true);
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) $('#bar').value = (e.loaded/e.total)*50; };
    xhr.onload = () => {
      let res = {}; try { res = JSON.parse(xhr.responseText); } catch(e){}
      if (xhr.status === 200 && res.ok) {
        done('✅ جاهزة للمعاينة — راجعها بالأعلى ثم اضغط «نشر للتطبيق».', true);
        refresh();
        // Show the result immediately: the operator's whole reason for
        // uploading is to see whether the background came out clean.
        try { openPreview(Object.assign({}, res.draft, {status:'مسودّة'})); } catch(e) {}
      }
      else done('❌ فشل: ' + (res.error || ('HTTP ' + xhr.status)), false);
    };
    xhr.onerror = () => done('❌ انقطع الاتصال — أعد تحميل الصفحة ثم حاول مرة أخرى', false);
    xhr.ontimeout = () => done('❌ انتهت المهلة — الملف كبير جدًا أو الخادم مشغول', false);
    xhr.send(fd);
  } catch (err) {
    $('#go').disabled = false; $('#bar').style.display = 'none';
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
window.addEventListener('pageshow', () => { $('#go').disabled = false; $('#bar').style.display = 'none'; });
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
