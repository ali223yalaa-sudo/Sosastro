/**
 * Astro Iraq News Bot — RSS only. Publishes to:
 *   - kind:1 posts (المنشورات) with real image/mp4 URLs only
 *   - kind:1063 FileHeader + kind:22 VideoShort for Home "Stories"/الاستوريز tab
 *
 * Never embeds news.google.com / youtube.com as playable video (app blocks YT
 * in Stories feed; Google News is a webpage preview, not media).
 */
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { XMLParser } from "fast-xml-parser";
import WebSocket from "ws";
import { finalizeEvent, getPublicKey, nip19 } from "nostr-tools";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = __dirname;
const CONFIG_PATH = path.join(ROOT, "config.json");
const STATE_PATH = path.join(ROOT, "state.json");
const ENV_PATH = path.join(ROOT, ".env");

const MAX_TAG_VAL = 200;
const KIND_NOTE = 1;
const KIND_FILE = 1063;
const KIND_VIDEO_SHORT = 22;

/** Optional: resolve YouTube → direct googlevideo mp4 (Stories blocks youtube.com). */
let ytdl = null;
try {
  ytdl = (await import("@distube/ytdl-core")).default;
} catch {
  ytdl = null;
}
const ytMp4Cache = new Map();


globalThis.WebSocket = WebSocket;

function loadEnvFile() {
  if (!fs.existsSync(ENV_PATH)) return;
  for (const line of fs.readFileSync(ENV_PATH, "utf8").split(/\r?\n/)) {
    const t = line.trim();
    if (!t || t.startsWith("#")) continue;
    const i = t.indexOf("=");
    if (i < 0) continue;
    const k = t.slice(0, i).trim();
    let v = t.slice(i + 1).trim();
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
      v = v.slice(1, -1);
    }
    if (!(k in process.env)) process.env[k] = v;
  }
}

function parseArgs(argv) {
  return { once: argv.includes("--once"), dryRun: argv.includes("--dry-run") };
}

function loadJson(file, fallback) {
  if (!fs.existsSync(file)) return fallback;
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

function saveJson(file, data) {
  fs.writeFileSync(file, JSON.stringify(data, null, 2) + "\n", "utf8");
}

function stripHtml(html) {
  if (!html) return "";
  return String(html)
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/\s+/g, " ")
    .trim();
}

function asArray(x) {
  if (x == null) return [];
  return Array.isArray(x) ? x : [x];
}

function decodeSecret(nsecOrHex) {
  const raw = (nsecOrHex || "").trim();
  if (!raw) throw new Error("NSEC missing — set it in .env");
  if (raw.startsWith("nsec1")) {
    const d = nip19.decode(raw);
    if (d.type !== "nsec") throw new Error("invalid nsec");
    return d.data;
  }
  if (/^[0-9a-fA-F]{64}$/.test(raw)) {
    return Uint8Array.from(Buffer.from(raw, "hex"));
  }
  throw new Error("NSEC must be nsec1... or 64-char hex");
}

function shortTag(url) {
  const s = String(url || "").trim();
  if (!s) return "";
  if (s.length <= MAX_TAG_VAL) return s;
  try {
    const u = new URL(s);
    const base = `${u.origin}${u.pathname}`;
    return base.length <= MAX_TAG_VAL ? base : base.slice(0, MAX_TAG_VAL);
  } catch {
    return s.slice(0, MAX_TAG_VAL);
  }
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function isGoogleNews(url) {
  const u = String(url || "").toLowerCase();
  return u.includes("news.google.") || u.includes("google.com/url?");
}

function isYouTube(url) {
  const u = String(url || "").toLowerCase();
  return (
    u.includes("youtube.com/watch") ||
    u.includes("youtube.com/shorts/") ||
    u.includes("youtube.com/embed/") ||
    u.includes("youtu.be/")
  );
}

function youtubeId(url) {
  try {
    const u = new URL(url);
    if (u.hostname.includes("youtu.be")) return u.pathname.slice(1).split("/")[0] || null;
    if (u.searchParams.get("v")) return u.searchParams.get("v");
    const m = u.pathname.match(/\/(?:shorts|embed|v)\/([^/?]+)/);
    return m?.[1] || null;
  } catch {
    return null;
  }
}

function youtubeThumb(url) {
  const id = youtubeId(url);
  return id ? `https://i.ytimg.com/vi/${id}/hqdefault.jpg` : null;
}

function pathExt(url) {
  try {
    const p = new URL(url).pathname.toLowerCase();
    const m = p.match(/\.([a-z0-9]{2,5})$/);
    return m ? m[1] : "";
  } catch {
    return "";
  }
}

/** Only real playable/displayable media for Astro Stories + posts. */
function classifyMedia(url, typeHint = "") {
  const raw = String(url || "").trim();
  if (!/^https?:\/\//i.test(raw)) return null;
  if (isGoogleNews(raw)) return null;

  // YouTube page URLs cannot play inside Stories (blocked). Keep thumb + watch link;
  // enrichMedia() may later replace with a direct googlevideo mp4 when ytdl works.
  if (isYouTube(raw)) {
    const thumb = youtubeThumb(raw);
    if (!thumb) return null;
    return {
      url: thumb,
      mime: "image/jpeg",
      kind: "image",
      watchUrl: raw,
      fromYoutube: true,
    };
  }

  const type = String(typeHint || "").toLowerCase();
  const ext = pathExt(raw);

  if (type.startsWith("video/") || ["mp4", "webm", "mov", "mpeg", "m4v"].includes(ext)) {
    const mime =
      type.startsWith("video/") ? type : ext === "webm" ? "video/webm" : ext === "mov" ? "video/quicktime" : "video/mp4";
    return { url: raw, mime, kind: "video", watchUrl: null };
  }

  if (
    type.startsWith("image/") ||
    ["jpg", "jpeg", "png", "gif", "webp", "avif", "jfif"].includes(ext)
  ) {
    const mime =
      type.startsWith("image/")
        ? type
        : ext === "png"
          ? "image/png"
          : ext === "gif"
            ? "image/gif"
            : ext === "webp"
              ? "image/webp"
              : "image/jpeg";
    return { url: raw, mime, kind: "image", watchUrl: null };
  }

  // PeerTube / CDN downloads often lack a file extension but declare video/mp4.
  if (/\/download\/videos\//i.test(raw) || /\/lazy-static\/.*\.(mp4|webm)/i.test(raw)) {
    return { url: raw, mime: type.startsWith("video/") ? type : "video/mp4", kind: "video", watchUrl: null };
  }

  return null;
}

async function resolveYoutubeMp4(watchUrl) {
  if (!ytdl || !watchUrl) return null;
  if (ytMp4Cache.has(watchUrl)) return ytMp4Cache.get(watchUrl);
  try {
    const info = await ytdl.getInfo(watchUrl);
    const formats = ytdl.filterFormats(info.formats, "audioandvideo");
    const best =
      formats.find((f) => f.container === "mp4" && f.hasVideo && f.hasAudio && f.url) ||
      formats.find((f) => f.url);
    if (!best?.url) {
      ytMp4Cache.set(watchUrl, null);
      return null;
    }
    // googlevideo.com is NOT in Stories blocked list (youtube.com / youtu.be are).
    if (best.url.length > 900) {
      // Relay max tag size ~1024 — skip oversized playback URLs for FileHeader tags.
      console.error(`  ytdl url too long (${best.url.length}) — skip direct mp4`);
      ytMp4Cache.set(watchUrl, null);
      return null;
    }
    const resolved = { url: best.url, mime: "video/mp4", kind: "video", watchUrl, fromYoutube: true };
    ytMp4Cache.set(watchUrl, resolved);
    return resolved;
  } catch (e) {
    console.error(`  ytdl fail: ${(e.message || e).toString().slice(0, 80)}`);
    ytMp4Cache.set(watchUrl, null);
    return null;
  }
}

function itemGuid(item, link) {
  const g = item.guid?.["#text"] ?? item.guid ?? item.id ?? link;
  return String(g).trim();
}

function itemLink(item) {
  const link = item.link?.["@_href"] ?? item.link?.["#text"] ?? item.link ?? item.id;
  if (typeof link === "string") return link.trim();
  if (Array.isArray(link)) {
    const first = link[0];
    return String(first?.["@_href"] || first || "").trim();
  }
  return "";
}

function itemTitle(item) {
  return stripHtml(item.title?.["#text"] ?? item.title ?? "");
}

function itemSummary(item) {
  const raw =
    item.description?.["#text"] ??
    item.description ??
    item.summary?.["#text"] ??
    item.summary ??
    item["media:description"] ??
    item["content:encoded"] ??
    "";
  const text = stripHtml(raw);
  if (text.length <= 400) return text;
  return text.slice(0, 397) + "…";
}

function extractUrlsFromHtml(html) {
  if (!html) return [];
  const out = [];
  const re = /(?:src|url|content)=["']?(https?:\/\/[^"'>\s]+)/gi;
  let m;
  while ((m = re.exec(String(html)))) out.push(m[1]);
  return out;
}

function collectRawMedia(item) {
  const found = [];
  for (const enc of asArray(item.enclosure)) {
    found.push({ url: enc?.["@_url"] || enc?.url, type: enc?.["@_type"] || enc?.type || "" });
  }
  for (const m of asArray(item["media:content"])) {
    found.push({ url: m?.["@_url"], type: m?.["@_type"] || "" });
  }
  for (const m of asArray(item["media:thumbnail"])) {
    found.push({ url: m?.["@_url"], type: "image/jpeg" });
  }
  const group = item["media:group"];
  if (group) {
    for (const m of asArray(group["media:content"])) {
      found.push({ url: m?.["@_url"], type: m?.["@_type"] || "" });
    }
    for (const m of asArray(group["media:thumbnail"])) {
      found.push({ url: m?.["@_url"], type: "image/jpeg" });
    }
    const ytId = group["yt:videoId"] || item["yt:videoId"];
    if (ytId) {
      found.push({ url: `https://www.youtube.com/watch?v=${ytId}`, type: "video/youtube" });
    }
  }
  const html = item["content:encoded"] || item.description?.["#text"] || item.description || "";
  for (const u of extractUrlsFromHtml(html)) {
    found.push({ url: u, type: "" });
  }
  // Atom YouTube link
  const link = itemLink(item);
  if (isYouTube(link)) found.push({ url: link, type: "video/youtube" });

  const out = [];
  const seen = new Set();
  for (const f of found) {
    const c = classifyMedia(f.url, f.type);
    if (!c) continue;
    if (seen.has(c.url)) continue;
    seen.add(c.url);
    out.push(c);
  }
  return out.slice(0, 4);
}

async function fetchOgMedia(pageUrl) {
  if (!pageUrl || isGoogleNews(pageUrl) || isYouTube(pageUrl)) return [];
  try {
    const res = await fetch(pageUrl, {
      headers: {
        "User-Agent": "AstroIraqNewsBot/1.2",
        Accept: "text/html,application/xhtml+xml",
      },
      redirect: "follow",
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) return [];
    const finalUrl = res.url || pageUrl;
    // If Google News redirected to a real article, use that.
    if (isGoogleNews(finalUrl)) return [];
    const html = await res.text();
    const out = [];
    const ogVideo =
      html.match(/property=["']og:video(?::url)?["'][^>]*content=["']([^"']+)/i) ||
      html.match(/content=["']([^"']+)["'][^>]*property=["']og:video(?::url)?["']/i);
    const ogImage =
      html.match(/property=["']og:image["'][^>]*content=["']([^"']+)/i) ||
      html.match(/content=["']([^"']+)["'][^>]*property=["']og:image["']/i);
    if (ogVideo?.[1]) {
      const c = classifyMedia(ogVideo[1], "video/mp4");
      if (c) out.push(c);
    }
    if (ogImage?.[1]) {
      const c = classifyMedia(ogImage[1], "image/jpeg");
      if (c) out.push(c);
    }
    return out;
  } catch {
    return [];
  }
}

function parseRssItems(xml) {
  const parser = new XMLParser({
    ignoreAttributes: false,
    attributeNamePrefix: "@_",
    trimValues: true,
  });
  const doc = parser.parse(xml);
  const channel = doc?.rss?.channel || doc?.feed;
  if (!channel) return [];
  const items = asArray(channel.item || channel.entry);
  return items
    .map((item) => {
      const link = itemLink(item);
      const title = itemTitle(item);
      if (!title || !link) return null;
      return {
        guid: itemGuid(item, link),
        title,
        link,
        summary: itemSummary(item),
        media: collectRawMedia(item),
      };
    })
    .filter(Boolean);
}

async function fetchFeed(feed) {
  const res = await fetch(feed.url, {
    headers: {
      "User-Agent": "AstroIraqNewsBot/1.2 (+RSS reader)",
      Accept: "application/rss+xml, application/xml, text/xml, */*",
    },
    redirect: "follow",
  });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${feed.id}`);
  const xml = await res.text();
  if (!xml.includes("<item") && !xml.includes("<entry")) {
    throw new Error(`Not an RSS/Atom feed: ${feed.id}`);
  }
  return parseRssItems(xml);
}

function regionTags() {
  return [
    ["t", "middle-east"],
    ["L", "astro.region"],
    ["l", "middle-east", "astro.region"],
  ];
}

function buildNoteContent(item, feed, hashtags, media) {
  const lines = [`📰 ${item.title}`, "", `المصدر: ${feed.name}`];
  if (item.summary) lines.push("", item.summary);

  const images = media.filter((m) => m.kind === "image");
  const videos = media.filter((m) => m.kind === "video");
  // Real media URLs only (extensions / peertube) — never Google News.
  for (const m of [...videos, ...images].slice(0, 3)) {
    lines.push("", m.url);
  }
  for (const m of media) {
    if (m.watchUrl) lines.push("", `▶ شاهد الفيديو: ${m.watchUrl}`);
  }

  // Article link as text source — OK as preview, not as "the video".
  if (item.link && !isGoogleNews(item.link)) {
    lines.push("", item.link);
  } else if (isGoogleNews(item.link)) {
    lines.push("", "(المصدر عبر Google News — بدون تضمين الرابط كوسائط)");
  }

  const tags = [...(hashtags || [])];
  if (!tags.some((t) => t.replace(/^#/, "") === "middle-east")) tags.push("middle-east");
  lines.push("", tags.map((t) => `#${t.replace(/^#/, "")}`).join(" "));
  return lines.join("\n");
}

function buildNoteTags(item, hashtags, media) {
  const tags = [["client", "astro-iraq-news-bot"], ...regionTags()];
  for (const t of hashtags || []) {
    const h = t.replace(/^#/, "");
    if (h === "middle-east") continue;
    tags.push(["t", h]);
  }
  if (item.link && !isGoogleNews(item.link)) {
    const r = shortTag(item.link);
    if (r) tags.push(["r", r]);
  }
  for (const m of media.slice(0, 3)) {
    const url = shortTag(m.url);
    if (!url) continue;
    tags.push(["imeta", `url ${url}`, `m ${m.mime}`]);
  }
  return tags;
}

function buildFileHeaderEvent(media, caption, sk, hashtags) {
  const now = Math.floor(Date.now() / 1000);
  const tags = [
    ["url", media.url],
    ["m", media.mime],
    ["alt", caption.slice(0, 200)],
    ["client", "astro-iraq-news-bot"],
    ...regionTags(),
  ];
  for (const t of hashtags || []) {
    const h = t.replace(/^#/, "");
    if (h !== "middle-east") tags.push(["t", h]);
  }
  return finalizeEvent(
    { kind: KIND_FILE, created_at: now, tags, content: caption.slice(0, 500) },
    sk,
  );
}

function buildVideoShortEvent(media, caption, sk, hashtags) {
  const now = Math.floor(Date.now() / 1000);
  const tags = [
    ["imeta", `url ${media.url}`, `m ${media.mime}`],
    ["title", caption.slice(0, 120)],
    ["client", "astro-iraq-news-bot"],
    ...regionTags(),
  ];
  for (const t of hashtags || []) {
    const h = t.replace(/^#/, "");
    if (h !== "middle-east") tags.push(["t", h]);
  }
  return finalizeEvent(
    { kind: KIND_VIDEO_SHORT, created_at: now, tags, content: caption.slice(0, 500) },
    sk,
  );
}

async function publishToRelay(relayUrl, event, timeoutMs = 10000) {
  return new Promise((resolve) => {
    let settled = false;
    const done = (ok, detail) => {
      if (settled) return;
      settled = true;
      try {
        ws.close();
      } catch {}
      resolve({ relay: relayUrl, ok, detail });
    };
    const ws = new WebSocket(relayUrl);
    const timer = setTimeout(() => done(false, "timeout"), timeoutMs);
    ws.on("open", () => ws.send(JSON.stringify(["EVENT", event])));
    ws.on("message", (data) => {
      try {
        const msg = JSON.parse(String(data));
        if (msg[0] === "OK" && msg[1] === event.id) {
          clearTimeout(timer);
          done(!!msg[2], msg[3] || "");
        }
      } catch {}
    });
    ws.on("error", (err) => {
      clearTimeout(timer);
      done(false, err.message || "ws error");
    });
    ws.on("close", () => {
      clearTimeout(timer);
      if (!settled) done(false, "closed");
    });
  });
}

async function publishEvent(relays, event, opts = {}) {
  const attempts = Math.max(1, opts.attempts ?? 3);
  const delayMs = opts.delayMs ?? 2000;
  for (let tryNo = 1; tryNo <= attempts; tryNo++) {
    const results = await Promise.all(relays.map((r) => publishToRelay(r, event)));
    const okRelays = results.filter((r) => r.ok);
    for (const r of results) {
      console.error(
        `  ${r.ok ? "OK" : "FAIL"} ${r.relay} ${r.detail || ""}${tryNo > 1 ? ` (try ${tryNo}/${attempts})` : ""}`,
      );
    }
    if (okRelays.length > 0) return true;
    if (tryNo < attempts) {
      console.error(`  retry in ${delayMs}ms…`);
      await sleep(delayMs * tryNo);
    }
  }
  return false;
}

async function enrichMedia(item) {
  let media = [...item.media];
  if (media.length === 0 || isGoogleNews(item.link)) {
    const scraped = await fetchOgMedia(item.link);
    for (const s of scraped) {
      if (!media.some((m) => m.url === s.url)) media.push(s);
    }
  }

  // Try to upgrade YouTube thumbs → direct mp4 (plays in Stories tab).
  const upgraded = [];
  for (const m of media) {
    if (m.fromYoutube && m.watchUrl) {
      const mp4 = await resolveYoutubeMp4(m.watchUrl);
      if (mp4) {
        upgraded.push(mp4);
        // Keep thumb as poster-friendly image too.
        upgraded.push(m);
        continue;
      }
    }
    upgraded.push(m);
  }
  media = upgraded;

  // Prefer videos first for Stories tab.
  media.sort((a, b) => {
    if (a.kind === b.kind) return 0;
    return a.kind === "video" ? -1 : 1;
  });
  return media.slice(0, 4);
}

async function runOnce(config, state, opts) {
  const sk = opts.dryRun ? null : decodeSecret(process.env.NSEC);
  const pk = sk ? getPublicKey(sk) : null;
  if (pk) console.error(`pubkey ${pk.slice(0, 8)}…${pk.slice(-4)}`);

  let notes = 0;
  let storiesTab = 0;
  let skipped = 0;
  let videoBudget = config.maxVideoStoriesPerRun ?? 30;
  let imageBudget = config.maxImageStoriesPerRun ?? 15;
  const relays = config.relays || [];
  const pubOpts = {
    attempts: config.publishRetries ?? 3,
    delayMs: config.retryDelayMs ?? 2000,
  };

  // Video-capable feeds first so Stories tab fills with mp4 before image budget burns.
  const feeds = [...(config.feeds || [])].sort((a, b) => {
    const score = (f) =>
      /peertube|tilvids|blender|video|youtube|yt-/i.test(f.id + f.url) ? 0 : 1;
    return score(a) - score(b);
  });

  for (const feed of feeds) {
    let items;
    try {
      items = await fetchFeed(feed);
      console.error(`[${feed.id}] fetched ${items.length}`);
    } catch (e) {
      console.error(`[${feed.id}] ERROR ${e.message}`);
      continue;
    }

    const ranked = [...items].sort((a, b) => {
      const av = a.media.some((m) => m.kind === "video") ? 2 : a.media.length ? 1 : 0;
      const bv = b.media.some((m) => m.kind === "video") ? 2 : b.media.length ? 1 : 0;
      return bv - av;
    });

    const fresh = ranked.filter((it) => !state.seen[it.guid]).slice(0, config.maxPerFeed ?? 15);

    for (const item of fresh) {
      const media = await enrichMedia(item);
      const content = buildNoteContent(item, feed, config.hashtags, media);
      const tags = buildNoteTags(item, config.hashtags, media);

      if (opts.dryRun) {
        console.log("--- DRY ---", item.title);
        console.log("media:", media);
        console.log(content.slice(0, 300));
        skipped++;
        continue;
      }

      const note = finalizeEvent(
        { kind: KIND_NOTE, created_at: Math.floor(Date.now() / 1000), tags, content },
        sk,
      );
      const okNote = await publishEvent(relays, note, pubOpts);
      if (!okNote) {
        console.error(`[${feed.id}] FAILED note: ${item.title.slice(0, 50)}`);
        continue;
      }
      notes++;
      state.seen[item.guid] = {
        at: Date.now(),
        id: note.id,
        title: item.title,
        feed: feed.id,
        storiesTab: [],
      };
      console.error(`[${feed.id}] note ${note.id.slice(0, 12)}… media=${media.length} ${item.title.slice(0, 45)}`);
      saveJson(STATE_PATH, state);

      // Home Stories / الاستوريز: publish REAL videos first (kind 22 + 1063).
      if (config.publishStories !== false && media.length) {
        const videos = media.filter((m) => m.kind === "video");
        const images = media.filter((m) => m.kind === "image");
        const queue = [];
        for (const m of videos.slice(0, 2)) {
          if (videoBudget > 0) queue.push({ m, pool: "video" });
        }
        // Only spend image budget when this item has no video.
        if (videos.length === 0) {
          for (const m of images.slice(0, 1)) {
            if (imageBudget > 0) queue.push({ m, pool: "image" });
          }
        }

        for (const { m, pool } of queue) {
          if (pool === "video" && videoBudget <= 0) continue;
          if (pool === "image" && imageBudget <= 0) continue;
          const caption = `📰 ${item.title}`.slice(0, 280);
          let ok = false;
          if (m.kind === "video") {
            const shortEv = buildVideoShortEvent(m, caption, sk, config.hashtags);
            ok = await publishEvent(relays, shortEv, pubOpts);
            if (ok) {
              state.seen[item.guid].storiesTab.push(shortEv.id);
              console.error(`[${feed.id}] stories-tab VIDEO22 ${shortEv.id.slice(0, 12)}…`);
            }
          }
          // Skip FileHeader when url tag would exceed relay limits.
          if (m.url.length <= 900) {
            const fileEv = buildFileHeaderEvent(m, caption, sk, config.hashtags);
            const okFile = await publishEvent(relays, fileEv, pubOpts);
            if (okFile) {
              ok = true;
              state.seen[item.guid].storiesTab.push(fileEv.id);
              console.error(`[${feed.id}] stories-tab file1063 ${fileEv.id.slice(0, 12)}… ${m.mime}`);
            }
          }
          if (ok) {
            storiesTab++;
            if (pool === "video") videoBudget--;
            else imageBudget--;
            saveJson(STATE_PATH, state);
          }
        }
      }
    }
  }

  const entries = Object.entries(state.seen).sort((a, b) => (b[1].at || 0) - (a[1].at || 0));
  if (entries.length > 3000) state.seen = Object.fromEntries(entries.slice(0, 3000));
  state.updatedAt = Date.now();
  saveJson(STATE_PATH, state);
  console.error(`done notes=${notes} storiesTab=${storiesTab} dry/skipped=${skipped}`);
  return notes + storiesTab;
}

async function main() {
  loadEnvFile();
  const opts = parseArgs(process.argv.slice(2));

  if (opts.once || opts.dryRun) {
    const config = loadJson(CONFIG_PATH, null);
    if (!config) throw new Error("config.json missing");
    const state = loadJson(STATE_PATH, { seen: {}, updatedAt: 0 });
    await runOnce(config, state, opts);
    return;
  }

  console.error("Iraq news bot continuous (notes + Stories-tab media)");
  for (;;) {
    try {
      loadEnvFile();
      const config = loadJson(CONFIG_PATH, null);
      if (!config) throw new Error("config.json missing");
      const state = loadJson(STATE_PATH, { seen: {}, updatedAt: 0 });
      const intervalMin = Number(process.env.INTERVAL_MINUTES) || config.intervalMinutes || 3;
      console.error(`loop — interval ${intervalMin}m`);
      await runOnce(config, state, opts);
      await sleep(intervalMin * 60 * 1000);
    } catch (e) {
      console.error("run error:", e.message || e);
      await sleep(60 * 1000);
    }
  }
}

main().catch((e) => {
  console.error(e.message || e);
  process.exit(1);
});
