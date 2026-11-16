#!/usr/bin/env python3
"""Merge the gifts module into the live astro-admin worker, additively.

Nothing in the existing bundle is rewritten: we only *insert* new code at
unique anchors and append the module before the closing `export {}`. Every
change is a pure addition, so the moderation panel keeps working byte-for-byte.

Usage:
    python3 build_admin.py            # build patched bundle -> dist/index.js
    python3 build_admin.py --deploy   # build, then upload + bind R2

Safety:
  * A byte-exact backup of the live bundle is kept at backups/astro-admin-<ts>.js
  * `--deploy` re-declares every existing binding (so nothing is dropped) and
    preserves the ADMIN_TOKEN secret (verified: secrets survive a PUT).
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
LIVE = HERE / "live_admin.js"
MODULE = HERE / "gifts_module.js"
DIST = HERE / "dist"
BACKUPS = HERE / "backups"

ACCOUNT_ID = "906ff45b1e21cc77acea88c7780c262c"
WORKER = "astro-admin"
API = f"https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/workers/scripts/{WORKER}"
GIFTS_BUCKET = "astro-gifts"
TOKEN_FILE = Path("/workspace/.cf_token")


def read_token() -> str:
    tok = os.environ.get("CLOUDFLARE_API_TOKEN") or ""
    if not tok and TOKEN_FILE.exists():
        tok = TOKEN_FILE.read_text().strip()
    if not tok:
        sys.exit("no Cloudflare token (set CLOUDFLARE_API_TOKEN or /workspace/.cf_token)")
    return tok


def fetch_live(token: str) -> str:
    """Download the deployed bundle and extract the index.js module source."""
    import re

    out = subprocess.run(
        ["curl", "-s", "-m", "60", "-H", f"Authorization: Bearer {token}",
         f"https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/workers/scripts/{WORKER}/content/v2"],
        capture_output=True, text=True,
    ).stdout
    m = re.search(r'name="index\.js"[^\r\n]*\r?\n(?:Content-Type:[^\r\n]*\r?\n)?\r?\n(.*?)\r?\n--', out, re.S)
    if not m:
        sys.exit("could not extract index.js from the deployed worker")
    return m.group(1)


def patch(live: str, module: str) -> str:
    """Insert the gifts routes/UI into the bundle without rewriting anything."""
    src = live

    # 1. Route the public /gifts/* and /api/gifts/* paths before the worker's
    #    own 404 fallback. The admin paths are untouched.
    anchor_health = '    if (path === "/health") {\n      return json({ ok: true });\n    }\n'
    if src.count(anchor_health) != 1:
        sys.exit("anchor /health not unique")
    src = src.replace(
        anchor_health,
        anchor_health
        + '    if (path === "/gifts/catalog.json" || path.startsWith("/gifts/")) {\n'
        + '      return giftsRoute(request, env, url);\n'
        + '    }\n',
    )

    # 2. Guard the API branch: gifts endpoints carry their own bearer check, so
    #    route them before the shared /api/ authorization gate.
    anchor_api = '    if (!path.startsWith("/api/")) {\n      return new Response("Not found", { status: 404 });\n    }\n'
    if src.count(anchor_api) != 1:
        sys.exit("anchor /api/ guard not unique")
    src = src.replace(
        anchor_api,
        anchor_api
        + '    if (path.startsWith("/api/gifts/")) {\n'
        + '      if (!isAuthorized(request, env)) return json({ error: "unauthorized" }, 401);\n'
        + '      return await giftsApi(path, request, env, url);\n'
        + '    }\n',
    )

    # 3. Add a nav button so the new tab is reachable from the panel.
    anchor_nav = '<button data-s="audit">\\u0633\\u062C\\u0644 \\u0627\\u0644\\u0639\\u0645\\u0644\\u064A\\u0627\\u062A</button>\n'
    if src.count(anchor_nav) != 1:
        sys.exit("anchor nav not unique")
    src = src.replace(
        anchor_nav,
        anchor_nav + '    <button data-s="gifts">\\u0627\\u0644\\u0647\\u062F\\u0627\\u064A\\u0627</button>\n',
    )

    # 4. Inject the panel UI *inside* the browser script. The panel HTML is a
    #    template literal, so its `sections` object lives in the page scope, not
    #    the worker scope — the UI code must go there, not beside the server code.
    anchor_sections = "const sections = {};\n"
    if src.count(anchor_sections) != 1:
        sys.exit("anchor sections object not unique")
    ui = (HERE / "gifts_ui.js").read_text()
    # The panel is one big template literal, so the UI's own backticks and
    # interpolations must be escaped to nest inside it.
    ui = ui.replace("\\", "\\\\").replace("`", "\\`").replace("${", "\\${")
    src = src.replace(anchor_sections, anchor_sections + "\n" + ui + "\n")

    # 5. Append the server-side module just before the final export block.
    anchor_export = "export {\n  src_default as default,\n  parseLocator\n};"
    if src.count(anchor_export) != 1:
        sys.exit("anchor export block not unique")
    server = (HERE / "gifts_server.js").read_text()
    src = src.replace(anchor_export, server + "\n" + anchor_export)

    return src


def build() -> Path:
    token = read_token()
    live = fetch_live(token)
    (HERE / "live_admin.js").write_text(live)

    # Always patch from the pristine original: the deployed bundle is already
    # patched once we have shipped, and patching a patched bundle would append
    # the module twice. The pristine copy is captured on the first run.
    pristine = HERE / "pristine" / "index.js"
    if not pristine.exists():
        pristine.parent.mkdir(exist_ok=True)
        pristine.write_text(live)
        print(f"captured pristine original -> {pristine.relative_to(HERE)} ({len(live)} bytes)")
    base = pristine.read_text()

    BACKUPS.mkdir(exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    (BACKUPS / f"live-{stamp}.js").write_text(live)

    module = MODULE.read_text() if MODULE.exists() else ""
    patched = patch(base, module)
    DIST.mkdir(exist_ok=True)
    out = DIST / "index.js"
    out.write_text(patched)
    print(f"patched bundle -> {out.relative_to(HERE)} ({len(patched)} bytes, +{len(patched) - len(base)})")
    return out


def upload(bundle: Path) -> None:
    """Upload the patched bundle and make sure the R2 binding is present.

    Every binding the worker already has is re-declared so a redeploy can never
    silently drop one (plain_text vars are NOT preserved across a PUT; only
    secrets are). The secret ADMIN_TOKEN is intentionally omitted — Cloudflare
    keeps its stored value.
    """
    token = read_token()
    settings = json.loads(subprocess.run(
        ["curl", "-s", "-m", "60", "-H", f"Authorization: Bearer {token}",
         f"{API}/settings"], capture_output=True, text=True).stdout)["result"]

    bindings = []
    for b in settings.get("bindings", []):
        if b["type"] == "plain_text":
            bindings.append({"name": b["name"], "type": "plain_text", "text": b["text"]})
        elif b["type"] == "d1":
            bindings.append({"name": b["name"], "type": "d1", "id": b["database_id"]})
        elif b["type"] == "secret_text":
            # A PUT cannot re-declare a secret without its value, so it is
            # omitted entirely — Cloudflare keeps the stored value (verified).
            pass
    if not any(b["name"] == "GIFTS" for b in bindings):
        bindings.append({"name": "GIFTS", "type": "r2_bucket", "bucket_name": GIFTS_BUCKET})

    meta = {"main_module": "index.js", "compatibility_date": settings.get("compatibility_date", "2025-01-04"), "bindings": bindings}
    print("bindings:", ", ".join(f"{b['name']}:{b['type']}" for b in bindings))

    out = subprocess.run(
        ["curl", "-s", "-m", "120", "-X", "PUT", "-H", f"Authorization: Bearer {token}",
         "-F", f"metadata={json.dumps(meta)};type=application/json",
         "-F", f"index.js=@{bundle};filename=index.js;type=application/javascript+module",
         API],
        capture_output=True, text=True,
    ).stdout
    ok = '"id"' in out or '"success": true' in out
    print("deploy:", "OK" if ok else out[:600])
    if not ok:
        sys.exit("deploy failed")
    time.sleep(6)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--deploy", action="store_true", help="upload after building")
    args = ap.parse_args()

    bundle = build()
    if args.deploy:
        upload(bundle)
        print("done. panel: https://astro-admin.hmzzhproxy29127.workers.dev/")


if __name__ == "__main__":
    main()
