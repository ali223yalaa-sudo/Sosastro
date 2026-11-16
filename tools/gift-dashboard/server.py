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
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
ASSETS = DATA / "assets"
CLIPS = ASSETS / "clips"
ICONS = ASSETS / "icons"
SOUNDS = ASSETS / "sounds"
STATE = DATA / "catalog.json"
TOOLS = Path(os.environ.get("GIFT_TOOLS", "/workspace/svga_tools"))
PYTHON = os.environ.get("GIFT_PYTHON", "/workspace/venv/bin/python")

MAX_UPLOAD = 512 * 1024 * 1024
ID_RE = re.compile(r"^[a-z0-9_]{1,32}$")


def ensure_dirs() -> None:
    for d in (DATA, ASSETS, CLIPS, ICONS, SOUNDS):
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
    state["version"] = int(state.get("version", 0)) + 1
    STATE.write_text(json.dumps(state, ensure_ascii=False, indent=2))
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
                "icon": g.get("icon"),
                "sound": g.get("sound"),
                "scale": g.get("scale"),
            }
            for g in state["gifts"]
            if g.get("svga")
        ],
    }
    (DATA / "published.json").write_text(json.dumps(published, ensure_ascii=False, indent=2))


def find_gift(state: dict, gift_id: str) -> dict | None:
    return next((g for g in state["gifts"] if g["id"] == gift_id), None)


# ── processing pipeline ────────────────────────────────────────────────


def content_hash(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()[:16]


def run_tool(args: list[str]) -> str:
    proc = subprocess.run(args, capture_output=True, text=True, timeout=1800)
    if proc.returncode != 0:
        raise RuntimeError((proc.stderr or proc.stdout or "tool failed").strip()[-2000:])
    return proc.stdout.strip()


def process_upload(
    src: Path,
    gift_id: str,
    tier: int,
    bg: str,
    size: int,
    pad: float,
) -> dict:
    """Run the authoring pipeline and return the published asset paths."""
    with tempfile.TemporaryDirectory() as td:
        td_path = Path(td)
        svga_tmp = td_path / f"{gift_id}.svga"
        icon_tmp = td_path / f"{gift_id}.png"
        sound_tmp = td_path / f"{gift_id}.wav"
        preview_tmp = td_path / f"{gift_id}_preview.png"

        run_tool(
            [
                PYTHON, str(TOOLS / "video_to_svga.py"), str(src),
                "-o", str(svga_tmp),
                "--bg", bg,
                "--size", str(size),
                "--pad", str(pad),
                "--icon", str(icon_tmp),
                "--preview", str(preview_tmp),
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

        preview_dir = DATA / "previews"
        preview_dir.mkdir(exist_ok=True)
        shutil.copy2(preview_tmp, preview_dir / f"{gift_id}.png")

    return {
        "svga": f"clips/{clip_name}",
        "icon": f"icons/{icon_name}",
        "sound": f"sounds/{sound_name}",
    }


def make_clip_preview(svga_rel: str, gift_id: str) -> str | None:
    """Render the stored clip to an animated GIF for the dashboard."""
    clip = ASSETS / svga_rel
    if not clip.exists():
        return None
    out_dir = DATA / "previews"
    out_dir.mkdir(exist_ok=True)
    out = out_dir / f"{gift_id}.gif"
    if out.exists() and out.stat().st_mtime >= clip.stat().st_mtime:
        return f"previews/{gift_id}.gif?t={int(out.stat().st_mtime)}"
    try:
        run_tool([PYTHON, str(TOOLS / "svga_to_gif.py"), str(clip), str(out)])
        return f"previews/{gift_id}.gif?t={int(time.time())}"
    except Exception:
        return None


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

    def send_file(self, path: Path, ctype: str | None = None, cache: bool = True) -> None:
        if not path.exists() or not path.is_file():
            return self.send_error(404, "not found")
        if ctype is None:
            ctype = {
                ".html": "text/html; charset=utf-8",
                ".json": "application/json; charset=utf-8",
                ".png": "image/png",
                ".gif": "image/gif",
                ".svga": "application/octet-stream",
                ".wav": "audio/wav",
            }.get(path.suffix, "application/octet-stream")
        self.send_bytes(path.read_bytes(), ctype, cache=cache)

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
                        g["preview"] = make_clip_preview(g["svga"], g["id"]) or g.get("preview")
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
        size = int(fields.get("size") or 480)
        pad = float(fields.get("pad") or 0.08)

        filename, payload = files["file"]
        suffix = Path(filename).suffix or ".mp4"
        with tempfile.TemporaryDirectory() as td:
            src = Path(td) / f"upload{suffix}"
            src.write_bytes(payload)
            assets = process_upload(src, gift_id, tier, bg, size, pad)

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
                "bg": bg,
                "size": size,
                "pad": pad,
                **assets,
            }
        )
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
        if not draft.get("svga"):
            return self.send_json({"error": "draft has no clip"}, 400)
        gift = find_gift(state, draft["id"])
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
        for key, cast in (("tier", int), ("price", int), ("scale", float)):
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
  .pill { display:inline-block; padding:2px 9px; border-radius:999px; font-size:11.5px; font-weight:700; }
  .t1{background:#2b3a55;color:#a9c8ff}.t2{background:#3a2f55;color:#d0b6ff}
  .t3{background:#4a3a1c;color:#ffd88a}.t4{background:#4d1f2a;color:#ff9db1}
  td input { padding:6px 8px; font-size:13px; }
  .status { margin-top:12px; font-size:13px; min-height:20px; }
  .ok{color:#7ee2a8}.err{color:#ff8a8a}
  progress { width:100%; height:8px; }
</style>
</head>
<body>
  <h1>لوحة تحكم الهدايا</h1>
  <div class="sub">ارفع فيديو أو صورة متحركة، وسيُعالَج تلقائيًا (إزالة الخلفية، القص، هوامش شفافة، صوت حسب الفئة)، ثم عايِن قبل النشر. التعديل يظهر في التطبيق عند التشغيل التالي دون تحديثه.</div>

  <div class="grid">
    <div class="card">
      <h2>➕ هدية جديدة</h2>
      <label>الملف (فيديو MP4/MOV أو GIF)</label>
      <input type="file" id="file" accept="video/*,image/gif,image/*">
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
            <option value="auto">تلقائي</option>
            <option value="black">أسود</option>
            <option value="green">أخضر</option>
            <option value="none">بلا إزالة</option>
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
        <div><label>الدقة</label><input id="size" type="number" value="480" min="240" max="1080"></div>
        <div><label>الهامش الشفاف</label><input id="pad" type="number" value="0.08" step="0.01" min="0" max="0.3"></div>
      </div>
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

<script>
const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));

function draftRow(g){
  return `<div class="draft" data-id="${esc(g.id)}" style="display:flex;gap:14px;align-items:center;border:1px solid var(--line);border-radius:14px;padding:12px;margin-bottom:12px">
    ${g.preview ? `<img class="thumb" style="width:96px;height:96px" src="/${esc(g.preview)}" alt="معاينة">` : ''}
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
      <td>${g.preview ? `<img class="thumb" src="/${esc(g.preview)}" alt="معاينة">` : '<span style="color:var(--dim)">—</span>'}</td>
      <td style="white-space:nowrap">
        <button class="ghost" data-act="save">حفظ</button>
        <button class="ghost" data-act="del">حذف</button>
      </td>
    </tr>`).join('');
  $('#list').innerHTML = `<table>
    <thead><tr><th>الأيقونة</th><th>الهدية</th><th>الفئة</th><th>السعر</th><th>الحجم</th><th>معاينة</th><th></th></tr></thead>
    <tbody>${rows || '<tr><td colspan="7" style="color:var(--dim)">لا توجد هدايا منشورة بعد</td></tr>'}</tbody></table>
    <div class="hint">الإصدار الحالي: <b>${st.version||0}</b> — التعديلات تظهر في التطبيق عند التشغيل التالي.</div>`;
  $('#list').querySelectorAll('tr[data-id]').forEach(tr => {
    const id = tr.dataset.id;
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
  const f = $('#file').files[0];
  if (!f) return setStatus('اختر ملفًا أولًا', false);
  const id = $('#id').value.trim().toLowerCase();
  if (!/^[a-z0-9_]{1,32}$/.test(id)) return setStatus('المعرّف يجب أن يكون a-z 0-9 _ فقط', false);
  const fd = new FormData();
  fd.append('file', f);
  fd.append('id', id);
  ['emoji','labelAr','labelEn','tier','price','bg','scale','size','pad'].forEach(k => fd.append(k, $('#'+k).value));
  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/api/upload');
  $('#bar').style.display = 'block';
  $('#go').disabled = true;
  setStatus('جارٍ المعالجة… (إزالة الخلفية، القص، الترميز، الصوت)', true);
  xhr.upload.onprogress = (e) => { if (e.lengthComputable) $('#bar').value = (e.loaded/e.total)*50; };
  xhr.onload = () => {
    $('#go').disabled = false; $('#bar').style.display='none';
    let res = {}; try { res = JSON.parse(xhr.responseText); } catch(e){}
    if (xhr.status === 200 && res.ok) { setStatus('✅ جاهزة للمعاينة — راجعها بالأعلى ثم اضغط «نشر للتطبيق».', true); refresh(); }
    else setStatus('❌ فشل: ' + (res.error || xhr.status), false);
  };
  xhr.onerror = () => { $('#go').disabled=false; $('#bar').style.display='none'; setStatus('❌ انقطع الاتصال', false); };
  xhr.send(fd);
};

$('#reset').onclick = () => { ['id','labelAr','labelEn'].forEach(k => $('#'+k).value=''); $('#file').value=''; setStatus('', true); };
function setStatus(t, ok){ const s=$('#status'); s.textContent=t; s.className='status ' + (ok?'ok':'err'); }
refresh();
</script>
</body>
</html>
"""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=12001)
    ap.add_argument("--host", default="0.0.0.0")
    args = ap.parse_args()
    ensure_dirs()
    if not STATE.exists():
        save_state(default_state())
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"gift dashboard on http://{args.host}:{args.port}  (data: {DATA})")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
