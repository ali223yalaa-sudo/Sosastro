#!/usr/bin/env python3
"""Regression tests for the gift dashboard server.

Run:  /workspace/venv/bin/python tools/gift-dashboard/test_server.py

The tests drive the real HTTP handler in-process on a scratch data
directory — no mocks — so a change to the state or publish logic is
exercised exactly as the operator's browser would.

The publish path is the one that regressed: it used `find_gift()`,
which also searches drafts, so it matched the draft itself, skipped the
"add to catalog" branch, and deleted the draft — the gift vanished and
publish still answered 200. `test_publish_adds_new_gift` and
`test_publish_updates_existing_gift` pin that down.
"""

from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load_server(data_dir: Path):
    """Import server.py bound to a scratch data directory."""
    spec = importlib.util.spec_from_file_location("gift_server", HERE / "server.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    mod.DATA = data_dir
    mod.ASSETS = data_dir / "assets"
    mod.CLIPS = mod.ASSETS / "clips"
    mod.ICONS = mod.ASSETS / "icons"
    mod.SOUNDS = mod.ASSETS / "sounds"
    mod.STATE = data_dir / "catalog.json"
    mod.ensure_dirs()
    return mod


class DashboardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="giftdash-test-"))
        self.srv = load_server(self.tmp)
        self.srv.save_state(self.srv.default_state())
        self.httpd = self.srv.ThreadingHTTPServer(("127.0.0.1", 0), self.srv.Handler)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    # -- helpers

    def post(self, path: str, payload: dict) -> tuple[int, dict]:
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}{path}",
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def get(self, path: str) -> tuple[int, dict]:
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}{path}") as r:
            return r.status, json.loads(r.read())

    def add_draft(self, gift_id: str, **over) -> None:
        state = self.srv.load_state()
        draft = {"id": gift_id, "svga": f"clips/{gift_id}.svga", "icon": f"icons/{gift_id}.png"}
        draft.update(over)
        state["drafts"].append(draft)
        self.srv.save_state(state)

    # -- tests

    def test_publish_adds_new_gift(self):
        """A published draft must land in the live catalog, not vanish."""
        self.add_draft("newgift", scale=0.8)
        code, body = self.post("/api/publish", {"id": "newgift"})
        self.assertEqual(code, 200)
        self.assertTrue(body.get("ok"), body)

        state = self.srv.load_state()
        self.assertEqual([g["id"] for g in state["gifts"]], ["newgift"])
        self.assertEqual(state["drafts"], [])

        _, cat = self.get("/gifts/catalog.json")
        self.assertIn("newgift", [g["id"] for g in cat["gifts"]])

    def test_publish_updates_existing_gift(self):
        """Republishing an id that is already live updates it in place."""
        self.add_draft("dupgift", price=1)
        self.post("/api/publish", {"id": "dupgift"})
        self.add_draft("dupgift", price=42)
        code, body = self.post("/api/publish", {"id": "dupgift"})
        self.assertEqual(code, 200)
        self.assertTrue(body.get("ok"), body)

        state = self.srv.load_state()
        self.assertEqual([g["id"] for g in state["gifts"]], ["dupgift"])
        self.assertEqual(state["gifts"][0]["price"], 42)

    def test_publish_unknown_draft_is_404(self):
        code, body = self.post("/api/publish", {"id": "nope"})
        self.assertEqual(code, 404)
        self.assertIn("error", body)

    def test_publish_without_clip_is_400(self):
        self.add_draft("nocli")
        state = self.srv.load_state()
        state["drafts"][0].pop("svga")
        self.srv.save_state(state)
        code, body = self.post("/api/publish", {"id": "nocli"})
        self.assertEqual(code, 400)
        self.assertIn("error", body)

    def test_video_only_draft_publishes_with_its_dimensions(self):
        """A gift whose only clip is an alpha video must publish, and its
        frame size must reach the app's catalog (the app plays the video and
        needs the size to letterbox it)."""
        self.add_draft("vidon", svga=None, video="videos/abc.mp4", videoWidth=480, videoHeight=480)
        code, _ = self.post("/api/publish", {"id": "vidon"})
        self.assertEqual(code, 200)
        _, cat = self.get("/gifts/catalog.json")
        row = next(g for g in cat["gifts"] if g["id"] == "vidon")
        self.assertEqual("videos/abc.mp4", row["video"])
        self.assertEqual(480, row["videoWidth"])
        self.assertEqual(480, row["videoHeight"])

    def test_draft_is_not_served_before_publish(self):
        """An unpublished draft must never reach the app's catalog."""
        self.add_draft("secret")
        _, cat = self.get("/gifts/catalog.json")
        self.assertNotIn("secret", [g["id"] for g in cat["gifts"]])

    def test_gift_edit_saves_placement(self):
        self.add_draft("placed")
        self.post("/api/publish", {"id": "placed"})
        code, body = self.post(
            "/api/gift", {"id": "placed", "scale": 0.75, "posX": 0.2, "posY": -0.3}
        )
        self.assertEqual(code, 200)
        self.assertTrue(body.get("ok"), body)
        _, cat = self.get("/gifts/catalog.json")
        g = next(x for x in cat["gifts"] if x["id"] == "placed")
        self.assertEqual((g["scale"], g["posX"], g["posY"]), (0.75, 0.2, -0.3))

    def test_delete_removes_gift(self):
        self.add_draft("goner")
        self.post("/api/publish", {"id": "goner"})
        code, body = self.post("/api/delete", {"id": "goner"})
        self.assertEqual(code, 200)
        self.assertTrue(body.get("ok"), body)
        _, cat = self.get("/gifts/catalog.json")
        self.assertNotIn("goner", [g["id"] for g in cat["gifts"]])

    def test_relative_asset_paths_are_served_under_gifts(self):
        """The catalog writes relative paths; the app resolves them against
        the catalog URL, so the same paths must be reachable under /gifts/."""
        self.add_draft("relgift", svga="clips/relgift.svga", icon="icons/relgift.png")
        self.post("/api/publish", {"id": "relgift"})
        _, cat = self.get("/gifts/catalog.json")
        g = next(x for x in cat["gifts"] if x["id"] == "relgift")
        self.assertEqual(g["svga"], "clips/relgift.svga")
        self.assertFalse(g["svga"].startswith("/"))

    def test_cover_fit_passes_the_room_aspect(self):
        """`fit=cover` must tell the converter the room's shape, or the clip
        is cropped to the wrong box and leaves a transparent margin."""
        args = self.srv.shape_args_for({"fit": "cover"})
        self.assertIn("--fit", args)
        self.assertIn("cover", args)
        self.assertIn("--room-aspect", args)
        self.assertIn(str(self.srv.ROOM_ASPECT), args)

    def test_aspect_fit_has_no_room_aspect(self):
        args = self.srv.shape_args_for({"fit": "aspect"})
        self.assertIn("aspect", args)
        self.assertNotIn("--room-aspect", args)

    def test_shape_args_carry_no_crop_and_time_cap(self):
        args = self.srv.shape_args_for({"fit": "aspect", "noCrop": "1", "maxSeconds": "6"})
        self.assertIn("--no-crop", args)
        self.assertIn("--max-seconds", args)
        self.assertIn("6.0", args)


if __name__ == "__main__":
    unittest.main(verbosity=2)
