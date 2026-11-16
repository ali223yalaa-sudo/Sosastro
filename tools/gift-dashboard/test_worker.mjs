// Regression tests for the Cloudflare worker's gifts module.
//
//   node tools/gift-dashboard/test_worker.mjs
//
// The module is a plain script with no exports, so it is evaluated in a VM
// context with the Web platform globals the Worker runtime provides, and the
// handlers are driven directly against a fake R2 bucket — the real code path,
// no HTTP server and no mocks of the module itself.
//
// The publish path is the one worth pinning: it must accept a gift whose only
// clip is an alpha video (the whole point of the cinematic-gift work), must
// keep the frame size the renderer letterboxes to, and must not 404 when an
// already-published gift's asset is replaced.

import { readFileSync } from "node:fs";
import vm from "node:vm";

const SRC = readFileSync(new URL("./cloudflare/gifts_server.js", import.meta.url), "utf8");

function loadWorker() {
  const ctx = vm.createContext({
    Response,
    Request,
    Headers,
    FormData,
    File,
    Blob,
    TextDecoder,
    TextEncoder,
    URL,
    JSON,
    console,
    crypto,
  });
  vm.runInContext(
    SRC +
      "\n;globalThis.__g = { giftsRoute, giftsApi, giftsLoadState, giftsSaveState, giftsPublished };" +
      "\n;globalThis.__k = { GIFT_STATE_KEY, GIFT_CATALOG_KEY };",
    ctx,
  );
  return { api: ctx.__g.giftsApi, route: ctx.__g.giftsRoute, keys: ctx.__k };
}

function fakeEnv() {
  const store = new Map();
  const enc = new TextEncoder();
  const bytes = (v) => (typeof v === "string" ? enc.encode(v) : v);
  const GIFTS = {
    async get(key, opts) {
      if (!store.has(key)) return null;
      let body = store.get(key);
      // Mirror R2's range option so the worker's slicing is exercised.
      if (opts && opts.range) {
        body = body.slice(opts.range.offset, opts.range.offset + opts.range.length);
      }
      return {
        body,
        size: body.length,
        httpEtag: '"etag"',
        text: async () => new TextDecoder().decode(body),
      };
    },
    async head(key) {
      return store.has(key) ? { size: store.get(key).length, httpEtag: '"etag"' } : null;
    },
    async put(key, value) {
      store.set(key, bytes(value));
    },
  };
  return { GIFTS, store };
}

const W = loadWorker();
const env = fakeEnv();
const at = (path) => new URL("https://x" + path);

const post = (path, body) =>
  new Request("https://x" + path, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

const get = (path) => new Request("https://x" + path, { method: "GET" });

const jsonOf = async (res) => JSON.parse(await res.text());

/** Seed the store with the given gifts/drafts (resets it, like a fresh worker). */
async function seed(gifts, drafts) {
  await W.api("/api/gifts/import", post("/api/gifts/import", { gifts, drafts }), env, at("/api/gifts/import"));
}

const catalog = async () => jsonOf(await W.route(get("/gifts/catalog.json"), env, at("/gifts/catalog.json")));

async function upload(kind, id, bytesArr, name, type, dims = {}) {
  const form = new FormData();
  form.append("kind", kind);
  form.append("id", id);
  if (dims.width) form.append("width", String(dims.width));
  if (dims.height) form.append("height", String(dims.height));
  form.append("file", new File([new Uint8Array(bytesArr)], name, { type }));
  return W.api("/api/gifts/asset", new Request("https://x/api/gifts/asset", { method: "POST", body: form }), env, at("/api/gifts/asset"));
}

let passed = 0;
const failures = [];
function check(name, cond, detail) {
  if (cond) passed++;
  else failures.push(name + (detail ? " — " + detail : ""));
}

// -- publish: a video-only draft must reach the catalog with its frame size
{
  await seed([], [{ id: "vid", tier: 3, price: 100, video: "videos/abc.mp4", videoWidth: 480, videoHeight: 480 }]);
  const pub = await W.api("/api/gifts/publish", post("/api/gifts/publish", { id: "vid" }), env, at("/api/gifts/publish"));
  check("video-only draft publishes", pub.status === 200, "status " + pub.status);

  const row = (await catalog()).gifts.find((g) => g.id === "vid");
  check("video-only gift is in the catalog", !!row);
  check("catalog carries the frame width", row && row.videoWidth === 480, row && String(row.videoWidth));
  check("catalog carries the frame height", row && row.videoHeight === 480, row && String(row.videoHeight));
  check("published row has no svga", row && !row.svga, row && String(row.svga));
}

// -- publish: a draft with no clip at all is rejected
{
  await seed([], [{ id: "empty", tier: 1, price: 1 }]);
  const res = await W.api("/api/gifts/publish", post("/api/gifts/publish", { id: "empty" }), env, at("/api/gifts/publish"));
  check("draft without a clip is 400", res.status === 400, "status " + res.status);
}

// -- publish: unknown draft is 404
{
  const res = await W.api("/api/gifts/publish", post("/api/gifts/publish", { id: "nope" }), env, at("/api/gifts/publish"));
  check("unknown draft is 404", res.status === 404, "status " + res.status);
}

// -- asset upload then serve, with the right content type and recorded size
{
  await seed([], [{ id: "up1", tier: 1, price: 1 }]);
  const res = await upload("videos", "up1", [0, 1, 2, 3, 4, 5], "a.mp4", "video/mp4", { width: 300, height: 150 });
  const j = await jsonOf(res);
  check("asset upload succeeds", res.status === 200, "status " + res.status);
  check("asset path is under videos/", j.path && j.path.startsWith("videos/"), j.path);
  check("draft records the halved height", j.gift && j.gift.videoHeight === 150, j.gift && String(j.gift.videoHeight));

  const served = await W.route(get("/gifts/" + j.path), env, at("/gifts/" + j.path));
  check("uploaded asset serves 200", served.status === 200, "status " + served.status);
  check("uploaded video serves as video/mp4", served.headers.get("content-type") === "video/mp4", served.headers.get("content-type"));
}

// -- re-uploading an asset for an already-published gift must still publish
{
  await seed([], [{ id: "rep", tier: 2, price: 50, video: "videos/first.mp4", videoWidth: 480, videoHeight: 480 }]);
  await W.api("/api/gifts/publish", post("/api/gifts/publish", { id: "rep" }), env, at("/api/gifts/publish"));
  await upload("videos", "rep", [9, 9, 9, 9], "b.mp4", "video/mp4", { width: 480, height: 240 });
  const rep = await W.api("/api/gifts/publish", post("/api/gifts/publish", { id: "rep" }), env, at("/api/gifts/publish"));
  check("re-publish of a live gift succeeds", rep.status === 200, "status " + rep.status);

  const row = (await catalog()).gifts.find((g) => g.id === "rep");
  check("replaced clip is reflected in the catalog", row && row.video !== "videos/first.mp4", row && row.video);
  check("replaced frame height is reflected", row && row.videoHeight === 240, row && String(row.videoHeight));
}

// -- asset serving rejects path traversal
{
  const res = await W.route(get("/gifts/videos/..%2F..%2Fstate.json"), env, at("/gifts/videos/..%2F..%2Fstate.json"));
  check("traversal path is not served", res.status === 404, "status " + res.status);
}

// -- range requests: the app resumes the big APK and seeks into clips
{
  const form = new FormData();
  form.append("kind", "videos");
  form.append("file", new File([new Uint8Array([0, 1, 2, 3, 4, 5, 6, 7, 8, 9])], "r.mp4", { type: "video/mp4" }));
  const up = await jsonOf(await W.api("/api/gifts/asset", new Request("https://x/api/gifts/asset", { method: "POST", body: form }), env, at("/api/gifts/asset")));
  const path = "/gifts/" + up.path;

  const full = await W.route(get(path), env, at(path));
  check("full asset advertises ranges", full.headers.get("accept-ranges") === "bytes", full.headers.get("accept-ranges"));
  check("full asset is 200", full.status === 200, "status " + full.status);

  const part = await W.route(
    new Request("https://x" + path, { headers: { range: "bytes=2-5" } }),
    env,
    at(path),
  );
  check("range request is 206", part.status === 206, "status " + part.status);
  check("range reports content-range", part.headers.get("content-range") === "bytes 2-5/10", part.headers.get("content-range"));
  check("range reports the slice length", part.headers.get("content-length") === "4", part.headers.get("content-length"));

  const suffix = await W.route(new Request("https://x" + path, { headers: { range: "bytes=-3" } }), env, at(path));
  check("suffix range is 206", suffix.status === 206, "status " + suffix.status);
  check("suffix range covers the tail", suffix.headers.get("content-range") === "bytes 7-9/10", suffix.headers.get("content-range"));
}

// -- delete removes a published gift from the catalog
{
  await seed([{ id: "gone", tier: 1, price: 1, video: "videos/g.mp4", videoWidth: 480, videoHeight: 480 }], []);
  await W.api("/api/gifts/delete", post("/api/gifts/delete", { id: "gone" }), env, at("/api/gifts/delete"));
  check("deleted gift leaves the catalog", !(await catalog()).gifts.some((g) => g.id === "gone"));
}

// -- a bad kind is rejected
{
  const res = await upload("evil", "x", [1, 2, 3], "x.mp4", "video/mp4");
  check("unknown asset kind is 400", res.status === 400, "status " + res.status);
}

// -- the APK download supports ranges too, so it can resume
{
  env.store.set("app.apk", new TextEncoder().encode("x".repeat(1000)));
  const part = await W.route(new Request("https://x/gifts/app.apk", { headers: { range: "bytes=0-99" } }), env, at("/gifts/app.apk"));
  check("apk range is 206", part.status === 206, "status " + part.status);
  check("apk range covers 100 bytes", part.headers.get("content-range") === "bytes 0-99/1000", part.headers.get("content-range"));
  check("apk is not cached immutably", (part.headers.get("cache-control") || "").includes("no-cache"), part.headers.get("cache-control"));
}

if (failures.length) {
  console.error("FAILED " + failures.length + " / " + (passed + failures.length));
  for (const f of failures) console.error("  ✗ " + f);
  process.exit(1);
}
console.log("worker tests OK (" + passed + " checks)");
