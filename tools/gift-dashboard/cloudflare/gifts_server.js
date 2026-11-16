// ===== Gifts module (added) =================================================
// Permanent room-gift catalog + assets, stored in the R2 bucket `astro-gifts`
// and served to the app. This is appended to the existing admin worker without
// touching any of its own code.
//
//   GET  /gifts/catalog.json          -> published catalog (the app reads this)
//   GET  /gifts/<clips|icons|sounds>/<name>
//                                     -> asset bytes from R2 (immutable, hashed names)
//   GET  /api/gifts/state             -> { version, gifts, drafts }
//   POST /api/gifts/gift              -> edit one gift's fields
//   POST /api/gifts/publish           -> promote a draft into the live catalog
//   POST /api/gifts/discard           -> drop a draft
//   POST /api/gifts/delete            -> remove a gift
//   POST /api/gifts/asset             -> multipart upload of a clip/icon/sound
//   POST /api/gifts/meta              -> replace emoji/label/price/placement wholesale
//
// All /api/gifts/* routes require the same ADMIN_TOKEN bearer as the rest of
// the panel. /gifts/* is public: the app fetches the catalog and assets with no
// credentials, exactly like the old local dashboard.

const GIFT_STATE_KEY = "state.json";
const GIFT_CATALOG_KEY = "catalog.json";
// `clips` holds the legacy SVGA authoring format; `videos` holds the
// alpha-packed H.264 the app actually plays (smaller, hardware-decoded).
const GIFT_KINDS = ["clips", "videos", "icons", "sounds"];
const GIFT_EXT = { clips: ".svga", videos: ".mp4", icons: ".png", sounds: ".wav" };
const GIFT_CT = {
  clips: "application/octet-stream",
  videos: "video/mp4",
  icons: "image/png",
  sounds: "audio/wav",
};

function giftsHeaders(extra) {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-headers": "authorization,content-type",
    "access-control-allow-methods": "GET,POST,OPTIONS",
    ...(extra || {}),
  };
}

function giftsJson(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: giftsHeaders({ "content-type": "application/json; charset=utf-8" }),
  });
}

// A published row is exactly the shape the app's RemoteGiftCatalog parses; the
// state file keeps the full draft so the panel can resume editing.
function giftsPublished(state) {
  return {
    version: Number(state.version || 0),
    gifts: (state.gifts || [])
      // A gift needs *a* clip to be playable; an alpha video on its own is
      // enough, so it must not be dropped just because no SVGA was uploaded.
      .filter((g) => g && (g.svga || g.video))
      .map((g) => ({
        id: g.id,
        emoji: g.emoji || "\u{1F381}",
        labelAr: g.labelAr || "",
        labelEn: g.labelEn || "",
        tier: g.tier || 1,
        price: g.price || 1,
        svga: g.svga || null,
        // The alpha-packed video is what the app plays when present; SVGA
        // stays published so an older client still has a clip to fall back on.
        video: g.video || null,
        videoWidth: g.videoWidth || null,
        videoHeight: g.videoHeight || null,
        icon: g.icon || null,
        sound: g.sound || null,
        scale: g.scale,
        posX: g.posX || 0,
        posY: g.posY || 0,
      })),
  };
}

async function giftsLoadState(env) {
  const obj = await env.GIFTS.get(GIFT_STATE_KEY);
  if (!obj) return { version: 0, gifts: [], drafts: [] };
  try {
    const s = JSON.parse(await obj.text());
    s.gifts = s.gifts || [];
    s.drafts = s.drafts || [];
    return s;
  } catch {
    return { version: 0, gifts: [], drafts: [] };
  }
}

async function giftsSaveState(env, state) {
  state.version = Number(state.version || 0) + 1;
  state.gifts = state.gifts || [];
  state.drafts = state.drafts || [];
  await env.GIFTS.put(GIFT_STATE_KEY, JSON.stringify(state));
  await env.GIFTS.put(GIFT_CATALOG_KEY, JSON.stringify(giftsPublished(state)));
  return state;
}

function giftsFind(state, id) {
  return (state.gifts || []).concat(state.drafts || []).find((g) => g && g.id === id);
}

// Serve a stored asset. Names are content-hashed and immutable, so a long
// cache is safe and keeps the app's per-gift cost down to one fetch.
async function giftsServeAsset(env, kind, name) {
  if (!GIFT_KINDS.includes(kind)) return new Response("not found", { status: 404 });
  if (!/^[a-zA-Z0-9._-]{1,80}$/.test(name)) return new Response("not found", { status: 404 });
  const key = "assets/" + kind + "/" + name;
  const head = await env.GIFTS.head(key);
  if (!head) return new Response("not found", { status: 404 });
  const obj = await env.GIFTS.get(key);
  return new Response(obj.body, {
    headers: giftsHeaders({
      "content-type": GIFT_CT[kind],
      "content-length": String(head.size),
      "cache-control": "public, max-age=31536000, immutable",
      etag: head.httpEtag || '"' + name + '"',
    }),
  });
}

async function giftsRoute(request, env, url) {
  const path = url.pathname;

  // The built APK lives in R2 too, so the download link keeps working after
  // the local authoring box is gone.
  if (path === "/gifts/app.apk") {
    const head = await env.GIFTS.head("app.apk");
    if (!head) return new Response("no apk built", { status: 404 });
    const obj = await env.GIFTS.get("app.apk");
    return new Response(obj.body, {
      headers: giftsHeaders({
        "content-type": "application/vnd.android.package-archive",
        "content-length": String(head.size),
        "content-disposition": 'attachment; filename="amethyst-gifts.apk"',
      }),
    });
  }

  if (path === "/gifts/catalog.json") {
    const obj = await env.GIFTS.get(GIFT_CATALOG_KEY);
    const body = obj ? await obj.text() : JSON.stringify({ version: 0, gifts: [] });
    return new Response(body, {
      headers: giftsHeaders({
        "content-type": "application/json; charset=utf-8",
        // The app polls this; a short cache keeps a publish visible quickly.
        "cache-control": "public, max-age=30",
      }),
    });
  }

  const m = /^\/gifts\/(clips|videos|icons|sounds)\/(.+)$/.exec(path);
  if (m) return giftsServeAsset(env, m[1], m[2]);

  return new Response("not found", { status: 404 });
}

// The three content-addressed asset names for a gift id, derived from the
// uploaded bytes so a replacement never overwrites the old file (and so a
// cached client copy stays valid until the catalog changes).
async function giftsShaName(bytes, ext) {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  const hex = Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("")
    .slice(0, 16);
  return hex + ext;
}

async function giftsApi(path, request, env, url) {
  const method = request.method;

  if (path === "/api/gifts/state" && method === "GET") {
    return giftsJson(await giftsLoadState(env));
  }

  if (path === "/api/gifts/gift" && method === "POST") {
    const payload = await request.json().catch(() => ({}));
    const state = await giftsLoadState(env);
    const gift = giftsFind(state, payload.id);
    if (!gift) return giftsJson({ error: "unknown gift" }, 404);
    for (const k of ["emoji", "labelAr", "labelEn"]) {
      if (typeof payload[k] === "string") gift[k] = payload[k];
    }
    for (const k of ["tier", "price"]) {
      if (payload[k] !== undefined && payload[k] !== null) gift[k] = Math.trunc(Number(payload[k]));
    }
    for (const k of ["scale", "posX", "posY"]) {
      if (payload[k] !== undefined && payload[k] !== null) gift[k] = Number(payload[k]);
    }
    await giftsSaveState(env, state);
    return giftsJson({ ok: true, gift });
  }

  if (path === "/api/gifts/delete" && method === "POST") {
    const payload = await request.json().catch(() => ({}));
    const state = await giftsLoadState(env);
    const before = state.gifts.length;
    state.gifts = state.gifts.filter((g) => g.id !== payload.id);
    state.drafts = state.drafts.filter((g) => g.id !== payload.id);
    if (state.gifts.length === before && state.drafts.length === before) {
      return giftsJson({ error: "unknown gift" }, 404);
    }
    await giftsSaveState(env, state);
    return giftsJson({ ok: true });
  }

  if (path === "/api/gifts/publish" && method === "POST") {
    const payload = await request.json().catch(() => ({}));
    const state = await giftsLoadState(env);
    const draft = state.drafts.find((d) => d.id === payload.id);
    if (!draft) return giftsJson({ error: "unknown draft" }, 404);
    // A gift needs a clip to play: the alpha video when it has one, else
    // the legacy SVGA. Either is enough to publish.
    if (!draft.video && !draft.svga) return giftsJson({ error: "draft has no clip" }, 400);
    let gift = state.gifts.find((g) => g.id === draft.id);
    if (!gift) {
      gift = { id: draft.id };
      state.gifts.push(gift);
    }
    for (const [k, v] of Object.entries(draft)) {
      if (k !== "preview") gift[k] = v;
    }
    state.drafts = state.drafts.filter((d) => d.id !== draft.id);
    await giftsSaveState(env, state);
    return giftsJson({ ok: true, gift });
  }

  if (path === "/api/gifts/discard" && method === "POST") {
    const payload = await request.json().catch(() => ({}));
    const state = await giftsLoadState(env);
    const before = state.drafts.length;
    state.drafts = state.drafts.filter((d) => d.id !== payload.id);
    if (state.drafts.length === before) return giftsJson({ error: "unknown draft" }, 404);
    await giftsSaveState(env, state);
    return giftsJson({ ok: true });
  }

  if (path === "/api/gifts/meta" && method === "POST") {
    const payload = await request.json().catch(() => ({}));
    const state = await giftsLoadState(env);
    const id = String(payload.id || "").toLowerCase();
    if (!/^[a-z0-9_]{1,32}$/.test(id)) {
      return giftsJson({ error: "id must be a-z, 0-9, _ (max 32)" }, 400);
    }
    let gift = giftsFind(state, id);
    if (!gift) {
      gift = { id };
      state.drafts.push(gift);
    }
    const fields = ["emoji", "labelAr", "labelEn", "svga", "icon", "sound"];
    for (const k of fields) if (typeof payload[k] === "string") gift[k] = payload[k];
    for (const k of ["tier", "price"]) if (payload[k] !== undefined) gift[k] = Math.trunc(Number(payload[k]));
    for (const k of ["scale", "posX", "posY"]) if (payload[k] !== undefined) gift[k] = Number(payload[k]);
    await giftsSaveState(env, state);
    return giftsJson({ ok: true, gift });
  }

  if (path === "/api/gifts/asset" && method === "POST") {
    let form;
    try {
      form = await request.formData();
    } catch {
      return giftsJson({ error: "expected multipart form-data" }, 400);
    }
    const kind = String(form.get("kind") || "");
    if (!GIFT_KINDS.includes(kind)) return giftsJson({ error: "bad kind" }, 400);
    const file = form.get("file");
    if (!file || typeof file === "string") return giftsJson({ error: "no file" }, 400);
    const bytes = new Uint8Array(await file.arrayBuffer());
    if (bytes.length === 0) return giftsJson({ error: "empty file" }, 400);
    const name = await giftsShaName(bytes, GIFT_EXT[kind]);
    await env.GIFTS.put("assets/" + kind + "/" + name, bytes, {
      httpMetadata: { contentType: GIFT_CT[kind] },
    });
    const rel = kind + "/" + name;
    const id = String(form.get("id") || "").toLowerCase();
    const state = await giftsLoadState(env);
    let gift = null;
    if (id && /^[a-z0-9_]{1,32}$/.test(id)) {
      gift = giftsFind(state, id);
      if (!gift) {
        gift = { id };
        state.drafts.push(gift);
      }
      if (kind === "clips") {
        gift.svga = rel;
      } else if (kind === "videos") {
        gift.video = rel;
        // The frame size drives the renderer's colour/alpha split, so it is
        // recorded alongside the file rather than guessed on the client.
        const w = Number(form.get("width"));
        const h = Number(form.get("height"));
        if (Number.isFinite(w) && w > 0) gift.videoWidth = Math.trunc(w);
        if (Number.isFinite(h) && h > 0) gift.videoHeight = Math.trunc(h);
      } else if (kind === "icons") {
        gift.icon = rel;
      } else {
        gift.sound = rel;
      }
      await giftsSaveState(env, state);
    }
    return giftsJson({ ok: true, path: rel, gift });
  }

  if (path === "/api/gifts/import" && method === "POST") {
    // Bulk import from the local dashboard: one call carries the whole state
    // (gifts + drafts) so a fresh worker can be seeded in a single request.
    const payload = await request.json().catch(() => null);
    if (!payload || !Array.isArray(payload.gifts)) {
      return giftsJson({ error: "expected { gifts, drafts }" }, 400);
    }
    const state = { version: 0, gifts: payload.gifts, drafts: payload.drafts || [] };
    await giftsSaveState(env, state);
    return giftsJson({ ok: true, version: state.version, gifts: state.gifts.length });
  }

  return giftsJson({ error: "unknown gifts endpoint" }, 404);
}
