#!/usr/bin/env python3
"""Seed the astro-gifts R2 bucket from the local dashboard's data.

Writes, all through the Cloudflare R2 API (no worker auth needed):
  assets/<clips|icons|sounds>/<name>   the gift asset bytes
  state.json                            { version, gifts, drafts }
  catalog.json                          the published catalog the app fetches

Asset names in the local catalog are already content-hashed, so the same name
maps straight onto the R2 key the worker serves at /gifts/<kind>/<name>.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ACCOUNT_ID = "906ff45b1e21cc77acea88c7780c262c"
BUCKET = "astro-gifts"
DASH = Path("/workspace/gift_dashboard")
DATA = DASH / "data"
ASSETS = DATA / "assets"
TOKEN_FILE = Path("/workspace/.cf_token")


def token() -> str:
    t = TOKEN_FILE.read_text().strip()
    if not t:
        sys.exit("no token")
    return t


def r2_put(key: str, body: bytes, ctype: str) -> None:
    url = f"https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/r2/buckets/{BUCKET}/objects/{key}"
    out = subprocess.run(
        ["curl", "-s", "-m", "180", "-X", "PUT",
         "-H", f"Authorization: Bearer {token()}",
         "-H", f"Content-Type: {ctype}",
         "--data-binary", "@-", url],
        input=body, capture_output=True,
    )
    if b'"success":true' not in out.stdout.replace(b" ", b""):
        sys.exit(f"R2 put failed for {key}: {out.stdout[:300]}")


def main() -> None:
    catalog = json.loads((DATA / "published.json").read_text())
    gifts = catalog.get("gifts", [])
    print(f"catalog version {catalog.get('version')} with {len(gifts)} gifts")

    # Full operator state (gifts + drafts) so the panel can resume editing.
    raw_state = DATA / "catalog.json"
    if raw_state.exists():
        state = json.loads(raw_state.read_text())
    else:
        state = {"version": catalog.get("version", 0), "gifts": gifts, "drafts": []}

    uploaded = 0
    seen = set()
    for g in gifts:
        for key in ("svga", "icon", "sound"):
            rel = g.get(key)
            if not rel or rel in seen:
                continue
            seen.add(rel)
            src = ASSETS / rel
            if not src.exists():
                print(f"  MISSING {rel}")
                continue
            ext = src.suffix.lower()
            ctype = {".svga": "application/octet-stream", ".png": "image/png", ".wav": "audio/wav"}.get(ext, "application/octet-stream")
            r2_put(f"assets/{rel}", src.read_bytes(), ctype)
            uploaded += 1
            print(f"  + assets/{rel} ({src.stat().st_size} bytes)")

    r2_put("state.json", json.dumps(state, ensure_ascii=False).encode(), "application/json")
    r2_put("catalog.json", json.dumps(catalog, ensure_ascii=False).encode(), "application/json")
    print(f"uploaded {uploaded} assets + state.json + catalog.json")


if __name__ == "__main__":
    main()
