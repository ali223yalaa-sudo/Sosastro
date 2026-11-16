#!/usr/bin/env python3
"""Health-check the live room-gift catalog.

    python3 tools/gift-dashboard/check_catalog.py

Reads the published catalog and state straight from the R2 bucket and
verifies, for every gift, the things that have actually broken in
production:

  * the gift has a clip at all (an alpha video, or the legacy SVGA);
  * every referenced asset exists in the bucket;
  * the frame size is present and sane (the app letterboxes to it);
  * the alpha video is not a fully-transparent (blank) clip, which
    renders nothing while still charging the sender.

It exits non-zero when anything is wrong, so it can gate a deploy. Uses
the Cloudflare API token, not the panel's ADMIN_TOKEN, because it reads
the bucket directly.

Environment: CLOUDFLARE_API_TOKEN or /workspace/.cf_token (see publish.py).
"""

from __future__ import annotations

import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

ACCOUNT = os.environ.get("CF_ACCOUNT_ID", "906ff45b1e21cc77acea88c7780c262c")
BUCKET = os.environ.get("GIFTS_BUCKET", "astro-gifts")
TOKEN_FILE = Path(os.environ.get("CF_TOKEN_FILE", "/workspace/.cf_token"))
API = "https://api.cloudflare.com/client/v4"


def token() -> str:
    t = os.environ.get("CLOUDFLARE_API_TOKEN")
    if t:
        return t.strip()
    if TOKEN_FILE.exists():
        return TOKEN_FILE.read_text().strip()
    sys.exit("no Cloudflare token (set CLOUDFLARE_API_TOKEN or write /workspace/.cf_token)")


def r2_list() -> dict[str, int]:
    """Map every object key to its size, from the bucket's list endpoint.

    One paginated listing is far cheaper than a request per asset, and it
    is the only way to ask "does this key exist" without pulling a
    multi-megabyte body (the R2 object API ignores Range and has no HEAD).
    """
    keys: dict[str, int] = {}
    cursor = ""
    while True:
        url = f"{API}/accounts/{ACCOUNT}/r2/buckets/{BUCKET}/objects?per_page=1000"
        if cursor:
            url += "&cursor=" + cursor
        req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token()}"})
        with urllib.request.urlopen(req, timeout=60) as r:
            page = json.loads(r.read())
        for o in page.get("result", []):
            keys[o["key"]] = o.get("size", 0)
        info = page.get("result_info", {})
        if not info.get("is_truncated"):
            break
        cursor = info.get("cursor", "")
        if not cursor:
            break
    return keys


def r2_get(key: str) -> bytes | None:
    url = f"{API}/accounts/{ACCOUNT}/r2/buckets/{BUCKET}/objects/{key}"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token()}"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def ffmpeg() -> str | None:
    if shutil.which("ffmpeg"):
        return "ffmpeg"
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return None


def _ffmpeg_frame(ff: str, path: Path, t: float) -> bytes | None:
    out = subprocess.run(
        [ff, "-v", "error", "-ss", f"{t:.2f}", "-i", str(path), "-frames:v", "1",
         "-f", "image2pipe", "-vcodec", "png", "-"],
        capture_output=True,
    ).stdout
    return out or None


def _duration(ff: str, path: Path) -> float:
    """Seconds from ffmpeg's `Duration: HH:MM:SS.xx` line, or 0 if absent."""
    probe = subprocess.run([ff, "-hide_banner", "-i", str(path)], capture_output=True, text=True).stderr
    for tok in probe.split():
        tok = tok.rstrip(",")
        parts = tok.split(":")
        if len(parts) == 3 and parts[2].replace(".", "", 1).isdigit():
            try:
                return int(parts[0]) * 3600 + int(parts[1]) * 60 + float(parts[2])
            except ValueError:
                continue
    return 0.0


def video_is_blank(path: Path, ff: str) -> bool | None:
    """True when the packed clip is fully transparent throughout.

    The file stacks colour over alpha, so a blank clip has an all-zero
    *alpha* half (the bottom half) while its colour half may hold
    anything. Several frames are sampled: a clip that simply fades in
    must not be mistaken for a dead one, so every sample has to be
    empty. Returns None when the check cannot run.
    """
    try:
        from PIL import Image
        import numpy as np
    except ImportError:
        return None

    dur = _duration(ff, path)
    times = [dur * f for f in (0.1, 0.3, 0.5, 0.7, 0.9)] if dur else [0.0]
    for t in times:
        png = _ffmpeg_frame(ff, path, t)
        if not png:
            continue
        arr = np.asarray(Image.open(io.BytesIO(png)).convert("RGB"))
        alpha = arr[arr.shape[0] // 2 :, :, :]
        if int(alpha.max()) > 0:
            return False
    return True


def svga_is_blank(path: Path, frames: int = 5) -> bool | None:
    """True when the SVGA's decoded frames are all fully transparent."""
    try:
        from PIL import Image
        import numpy as np
    except ImportError:
        return None
    import zipfile

    try:
        z = zipfile.ZipFile(path)
        names = sorted(n for n in z.namelist() if n.lower().endswith(".png"))
    except Exception:
        return None
    if not names:
        return None
    picks = names[:: max(1, len(names) // frames)][:frames] or [names[0]]
    for n in picks:
        arr = np.asarray(Image.open(io.BytesIO(z.read(n))).convert("RGBA"))
        if int(arr[:, :, 3].max()) > 0:
            return False
    return True


def main() -> int:
    prune = "--prune" in sys.argv
    apply = "--apply" in sys.argv
    raw = r2_get("catalog.json")
    if raw is None:
        print("FATAL: no catalog.json in the bucket")
        return 2
    catalog = json.loads(raw)
    gifts = catalog.get("gifts", [])
    print(f"catalog version {catalog.get('version')} — {len(gifts)} gift(s)\n")

    ff = ffmpeg()
    problems: list[str] = []
    broken: set[str] = set()
    objects = r2_list()
    print(f"bucket has {len(objects)} object(s)\n")
    tmp = Path(tempfile.mkdtemp(prefix="giftcheck-"))
    try:
        for g in gifts:
            gid = g.get("id", "?")
            issues: list[str] = []
            if not g.get("video") and not g.get("svga"):
                issues.append("no clip")
            for kind in ("video", "svga", "icon", "sound"):
                rel = g.get(kind)
                if rel and f"assets/{rel}" not in objects:
                    issues.append(f"missing asset {kind}={rel}")
            # A blank clip is worse than a missing one: it exists, so the app
            # tries to play it, and the sender pays for nothing on screen.
            if g.get("svga"):
                local = tmp / f"{gid}.svga"
                blob = r2_get(f"assets/{g['svga']}")
                if blob:
                    local.write_bytes(blob)
                    if svga_is_blank(local):
                        issues.append("svga is blank (renders nothing)")
            if g.get("video"):
                w, h = g.get("videoWidth"), g.get("videoHeight")
                if not (isinstance(w, int) and isinstance(h, int) and w > 0 and h > 0):
                    issues.append(f"bad frame size {w}x{h}")
                if ff:
                    local = tmp / f"{gid}.mp4"
                    blob = r2_get(f"assets/{g['video']}")
                    if blob:
                        local.write_bytes(blob)
                        if video_is_blank(local, ff):
                            issues.append("video is blank (renders nothing)")
            flag = "ok" if not issues else "PROBLEM"
            print(f"  [{flag:7}] {gid:8} {g.get('labelAr', ''):14} " + ("; ".join(issues) or "clean"))
            for i in issues:
                problems.append(f"{gid}: {i}")
                broken.add(gid)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print()
    if not problems:
        print("catalog is healthy")
        return 0

    print(f"{len(problems)} problem(s):")
    for p in problems:
        print("  - " + p)

    if prune:
        return prune_gifts(broken, apply)
    print("\nrun with --prune to drop the broken gift(s) (add --apply to write).")
    return 1


def prune_gifts(broken: set[str], apply: bool) -> int:
    """Drop the named gifts from the live state and republish.

    The previous state and catalog are copied to `backups/` first, so an
    accidental prune is one copy back away from being undone.
    """
    state_raw = r2_get("state.json")
    if state_raw is None:
        print("FATAL: no state.json to prune")
        return 2
    state = json.loads(state_raw)
    before = len(state.get("gifts", []))
    state["gifts"] = [g for g in state.get("gifts", []) if g.get("id") not in broken]
    state["drafts"] = [g for g in state.get("drafts", []) if g.get("id") not in broken]
    removed = before - len(state["gifts"])
    print(f"\nwould remove {removed} gift(s): {', '.join(sorted(broken))}")
    if not apply:
        print("dry run — re-run with --prune --apply to write.")
        return 1

    import time

    stamp = time.strftime("%Y%m%d-%H%M%S")
    r2_put(f"backups/state-{stamp}.json", state_raw)
    cat_raw = r2_get("catalog.json")
    if cat_raw:
        r2_put(f"backups/catalog-{stamp}.json", cat_raw)

    state["version"] = int(state.get("version", 0)) + 1
    r2_put("state.json", json.dumps(state, ensure_ascii=False).encode())
    # Republish with the same projection the worker uses, so the pruned
    # catalog is what a panel action would have produced.
    published = {
        "version": state["version"],
        "gifts": [
            {
                k: g.get(k)
                for k in ("id", "emoji", "labelAr", "labelEn", "tier", "price", "svga", "video",
                          "videoWidth", "videoHeight", "icon", "sound", "scale", "posX", "posY")
            }
            for g in state["gifts"]
            if g.get("svga") or g.get("video")
        ],
    }
    for row in published["gifts"]:
        if not row.get("emoji"):
            row["emoji"] = "\U0001F381"
        for k in ("labelAr", "labelEn"):
            if row.get(k) is None:
                row[k] = ""
        for k in ("tier", "price"):
            if row.get(k) is None:
                row[k] = 1
    r2_put("catalog.json", json.dumps(published, ensure_ascii=False).encode())
    print(f"pruned {removed} gift(s); catalog version {published['version']}")
    print(f"backup: backups/state-{stamp}.json")
    return 0


def r2_put(key: str, data: bytes) -> None:
    url = f"{API}/accounts/{ACCOUNT}/r2/buckets/{BUCKET}/objects/{key}"
    req = urllib.request.Request(url, data=data, method="PUT", headers={"Authorization": f"Bearer {token()}"})
    with urllib.request.urlopen(req, timeout=120) as r:
        if r.status not in (200, 201):
            raise RuntimeError(f"put {key} failed: {r.status}")


if __name__ == "__main__":
    sys.exit(main())
