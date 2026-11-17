#!/usr/bin/env python3
"""Publish a locally-authored gift to the permanent Cloudflare storage.

The heavy authoring (video -> SVGA, background removal, sound mixing) runs
locally, where ffmpeg lives. This pushes the finished asset bytes and the
catalog row straight into the `astro-gifts` R2 bucket through the Cloudflare
API, so the gift survives this sandbox being recycled.

It talks to the Cloudflare API directly rather than the worker's /api/gifts/*
routes, so it needs the account API token — not the panel's ADMIN_TOKEN.

Usage:
    python3 publish.py push <gift-id>     # one gift/draft from local state
    python3 publish.py push-all           # every published gift (resync)
    python3 publish.py catalog            # refresh catalog.json from local state

Token: $CLOUDFLARE_API_TOKEN or /workspace/.cf_token.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

ACCOUNT_ID = "906ff45b1e21cc77acea88c7780c262c"
BUCKET = "astro-gifts"
DASH = Path(os.environ.get("GIFT_DASHBOARD") or Path(__file__).resolve().parent)
DATA = DASH / "data"
ASSETS = DATA / "assets"
TOKEN_FILE = Path(os.environ.get("CF_TOKEN_FILE", "/workspace/.cf_token"))

CTYPE = {
    ".svga": "application/octet-stream",
    ".mp4": "video/mp4",
    ".png": "image/png",
    ".wav": "audio/wav",
}


def token() -> str:
    t = os.environ.get("CLOUDFLARE_API_TOKEN") or ""
    if not t and TOKEN_FILE.exists():
        t = TOKEN_FILE.read_text().strip()
    if not t:
        sys.exit("no Cloudflare token (set CLOUDFLARE_API_TOKEN or write /workspace/.cf_token)")
    return t


def r2_put(key: str, body: bytes, ctype: str) -> None:
    url = f"https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/r2/buckets/{BUCKET}/objects/{key}"
    out = subprocess.run(
        ["curl", "-s", "-m", "300", "-X", "PUT",
         "-H", f"Authorization: Bearer {token()}",
         "-H", f"Content-Type: {ctype}",
         "--data-binary", "@-", url],
        input=body, capture_output=True,
    )
    if b'"success":true' not in out.stdout.replace(b" ", b""):
        sys.exit(f"R2 put failed for {key}: {out.stdout[:300]}")


def r2_get(key: str) -> bytes | None:
    """Read an object from R2, or None when it does not exist yet."""
    url = f"https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/r2/buckets/{BUCKET}/objects/{key}"
    out = subprocess.run(
        ["curl", "-s", "-m", "120", "-H", f"Authorization: Bearer {token()}", url],
        capture_output=True,
    )
    if out.returncode != 0 or not out.stdout:
        return None
    # A missing object answers with a JSON error body, not a file.
    if out.stdout.lstrip().startswith(b'{"success":false') or out.stdout.lstrip().startswith(b'{"errors"'):
        return None
    return out.stdout


def published_row(g: dict) -> dict:
    return {
        "id": g["id"],
        "emoji": g.get("emoji", "\U0001F381"),
        "labelAr": g.get("labelAr", ""),
        "labelEn": g.get("labelEn", ""),
        "tier": g.get("tier", 1),
        "price": g.get("price", 1),
        "svga": g.get("svga"),
        "video": g.get("video"),
        "videoWidth": g.get("videoWidth"),
        "videoHeight": g.get("videoHeight"),
        "icon": g.get("icon"),
        "sound": g.get("sound"),
        "scale": g.get("scale"),
        "posX": g.get("posX", 0),
        "posY": g.get("posY", 0),
    }


def load_state() -> dict:
    path = DATA / "catalog.json"
    if not path.exists():
        sys.exit(f"no local state at {path}")
    return json.loads(path.read_text())


def push_assets(gift: dict) -> int:
    n = 0
    for key in ("svga", "video", "icon", "sound"):
        rel = gift.get(key)
        if not rel:
            continue
        src = ASSETS / rel
        if not src.exists():
            print(f"  MISSING {rel}")
            continue
        r2_put(f"assets/{rel}", src.read_bytes(), CTYPE.get(src.suffix.lower(), "application/octet-stream"))
        n += 1
    return n


def sync_catalog(state: dict) -> None:
    rows = [published_row(g) for g in state.get("gifts", []) if g.get("svga") or g.get("video")]
    catalog = {"version": state.get("version", 0), "gifts": rows}
    r2_put("catalog.json", json.dumps(catalog, ensure_ascii=False).encode(), "application/json")
    # Keep the operator state too, so the web panel can resume editing.
    r2_put("state.json", json.dumps(state, ensure_ascii=False).encode(), "application/json")
    print(f"  catalog.json -> version {catalog['version']}, {len(rows)} gifts")


def _cloud_state() -> dict:
    cloud = {}
    raw = r2_get("state.json")
    if raw:
        try:
            cloud = json.loads(raw)
        except Exception:  # noqa: BLE001 - a corrupt cloud state must not block a save
            cloud = {}
    cloud.setdefault("gifts", [])
    cloud.setdefault("drafts", [])
    return cloud


def _cloud_put(cloud: dict) -> int:
    cloud["version"] = int(cloud.get("version", 0)) + 1
    rows = [published_row(g) for g in cloud["gifts"] if g.get("svga") or g.get("video")]
    r2_put(
        "catalog.json",
        json.dumps({"version": cloud["version"], "gifts": rows}, ensure_ascii=False).encode(),
        "application/json",
    )
    r2_put("state.json", json.dumps(cloud, ensure_ascii=False).encode(), "application/json")
    return len(rows)


def sync_gift(gift_id: str) -> None:
    """Mirror one gift's published fields into the live cloud catalog.

    A whole-state push from this dashboard would clobber gifts authored on the
    Cloudflare panel (the two catalogs are not kept in lockstep), so a metadata
    edit merges *one* row into the cloud's own state and republishes from that.
    Only the fields the app reads are written; the assets are already in R2.
    """
    state = load_state()
    gift = next((g for g in state.get("gifts", []) + state.get("drafts", []) if g.get("id") == gift_id), None)
    if not gift:
        sys.exit(f"no local gift/draft with id {gift_id}")
    row = published_row(gift)
    cloud = _cloud_state()
    target = next((g for g in cloud["gifts"] if g.get("id") == gift_id), None)
    if target is None:
        cloud["gifts"].append(row)
    else:
        target.update(row)
    cloud["drafts"] = [d for d in cloud["drafts"] if d.get("id") != gift_id]
    n = _cloud_put(cloud)
    print(f"  gift {gift_id} -> cloud version {cloud['version']}, {n} gifts")


def sync_delete(gift_id: str) -> None:
    """Remove a gift from the live cloud catalog and its draft list."""
    cloud = _cloud_state()
    before = len(cloud["gifts"]) + len(cloud["drafts"])
    cloud["gifts"] = [g for g in cloud["gifts"] if g.get("id") != gift_id]
    cloud["drafts"] = [d for d in cloud["drafts"] if d.get("id") != gift_id]
    if len(cloud["gifts"]) + len(cloud["drafts"]) == before:
        print(f"  gift {gift_id} was not in the cloud catalog")
        return
    n = _cloud_put(cloud)
    print(f"  gift {gift_id} deleted -> cloud version {cloud['version']}, {n} gifts")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["push", "push-all", "catalog", "edit", "delete"])
    ap.add_argument("gift_id", nargs="?")
    args = ap.parse_args()

    if args.cmd in ("edit", "delete"):
        if not args.gift_id:
            sys.exit(f"{args.cmd} needs a gift id")
        if args.cmd == "edit":
            sync_gift(args.gift_id)
        else:
            sync_delete(args.gift_id)
        return

    state = load_state()

    if args.cmd == "catalog":
        sync_catalog(state)
        return

    if args.cmd == "push":
        gift = next((g for g in state.get("gifts", []) + state.get("drafts", []) if g.get("id") == args.gift_id), None)
        if not gift:
            sys.exit(f"no local gift/draft with id {args.gift_id}")
        print(f"gift {gift['id']}:")
        print(f"  uploaded {push_assets(gift)} assets")
        sync_gift(gift["id"])
        print("published. the app picks it up on next launch.")
        return

    if args.cmd == "push-all":
        gifts = state.get("gifts", [])
        print(f"pushing {len(gifts)} published gifts")
        total = 0
        for g in gifts:
            print(f"gift {g['id']}:")
            total += push_assets(g)
        print(f"  uploaded {total} assets total")
        sync_catalog(state)
        print("done.")


if __name__ == "__main__":
    main()
