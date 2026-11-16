// ---- gifts panel (added) ---------------------------------------------------
// A "الهدايا" tab inside the existing moderation panel. It reads and writes the
// same R2 state the app consumes, so what you see here is exactly what ships.
// Authoring (video -> SVGA) still happens locally; this tab edits the live rows
// and uploads the finished alpha video for a gift.
sections.gifts = async () => {
  const d = await api("/api/gifts/state");
  giftPreviewState = d;
  const gifts = d.gifts || [];
  const drafts = d.drafts || [];
  const TIER_AR = { 1: "\u0639\u0627\u062F\u064A", 2: "\u0645\u0645\u064A\u0632", 3: "\u0641\u0627\u062E\u0631", 4: "\u0623\u0633\u0637\u0648\u0631\u064A" };
  const clipCell = (g) => g.video
    ? `<span class="ok" title="${esc(g.videoWidth || "?")}x${esc(g.videoHeight || "?")}">\u2713 \u0641\u064A\u062F\u064A\u0648</span>`
    : (g.svga ? '<span class="ok">\u2713 SVGA</span>' : '<span class="bad">\u2014</span>');
  const row = (g, isDraft) => `
    <tr data-id="${esc(g.id)}">
      <td>${esc(g.emoji || "\u{1F381}")}</td>
      <td><b>${esc(g.labelAr || g.id)}</b><br><span class="muted">${esc(g.id)}</span></td>
      <td>${TIER_AR[g.tier] || g.tier}</td>
      <td>${fmt(g.price || 1)}</td>
      <td>${clipCell(g)}</td>
      <td>
        ${g.video ? `<button class="act" data-pv="${esc(g.id)}">\u25B6 \u0645\u0639\u0627\u064A\u0646\u0629</button>` : ""}
        ${isDraft
          ? `<button class="act" data-pub="${esc(g.id)}">\u0646\u0634\u0631</button>
             <button class="act ghost" data-disc="${esc(g.id)}">\u062A\u062C\u0627\u0647\u0644</button>`
          : `<button class="act ghost" data-del="${esc(g.id)}">\u062D\u0630\u0641</button>`}
      </td>
    </tr>`;

  view().innerHTML = `
    <style>
      .gift-upload { display: flex; flex-wrap: wrap; gap: .5rem; align-items: center; }
      .gift-upload input { padding: .45rem .6rem; border-radius: .5rem; border: 1px solid var(--line, #333); background: var(--bg2, #1b1b1b); color: inherit; }
      .gift-upload input[type=file] { flex: 1 1 16rem; }
      .gu-prog { display: none; align-items: center; gap: .6rem; margin-top: .55rem; }
      .gu-prog.on { display: flex; }
      .gu-track { flex: 1; height: 10px; border-radius: 6px; background: var(--panel2, #1b2130); overflow: hidden; }
      .gu-fill { height: 100%; width: 0; border-radius: 6px; background: linear-gradient(90deg, #4b6bfb, #7C4DFF); transition: width .12s linear; }
      .gu-pct { font-variant-numeric: tabular-nums; font-size: 12px; color: var(--muted, #8b93a7); min-width: 3.5rem; text-align: center; }
    </style>
    <p class="lead">\u0627\u0644\u0647\u062F\u0627\u064A\u0627 \u0627\u0644\u0645\u0646\u0634\u0648\u0631\u0629 \u062A\u0623\u062A\u064A \u0645\u0646 \u0647\u0646\u0627 \u0645\u0628\u0627\u0634\u0631\u0629\u064B. \u0627\u0644\u062A\u0637\u0628\u064A\u0642 \u064A\u0642\u0631\u0623 \u0627\u0644\u0643\u062A\u0627\u0644\u0648\u062C \u0639\u0646\u062F \u0643\u0644 \u062A\u0634\u063A\u064A\u0644.</p>
    <div class="card">
      <h3>\u0631\u0627\u0628\u0637 \u0627\u0644\u0643\u062A\u0627\u0644\u0648\u062C (\u0627\u0644\u062F\u0627\u0626\u0645)</h3>
      <p class="help"><a href="/gifts/catalog.json" target="_blank">/gifts/catalog.json</a> \u2014 \u0627\u0644\u0625\u0635\u062F\u0627\u0631 ${fmt(d.version || 0)}</p>
      <p class="help"><a href="/gifts/app.apk">\u2b07\ufe0f \u062A\u0646\u0632\u064A\u0644 \u0623\u062D\u062F\u062B \u062A\u0637\u0628\u064A\u0642 (APK)</a></p>
    </div>
    <div class="card">
      <h3>\u0631\u0641\u0639 \u0645\u0634\u0647\u062F \u0647\u062F\u064A\u0629 (\u0641\u064A\u062F\u064A\u0648 \u0623\u0644\u0641\u0627)</h3>
      <p class="help">\u064A\u064F\u0631\u0641\u0639 \u0627\u0644\u0645\u0644\u0641 \u0627\u0644\u0645\u0636\u063A\u0648\u0637 (\u0645\u0646 <span dir="ltr">alpha_pack</span>) \u0645\u0628\u0627\u0634\u0631\u0629\u064B. \u062A\u064F\u0642\u0631\u0623 \u0623\u0628\u0639\u0627\u062F \u0627\u0644\u0625\u0637\u0627\u0631 \u0647\u0646\u0627 \u062A\u0644\u0642\u0627\u0626\u064A\u0627\u064B.</p>
      <div class="gift-upload">
        <input id="gu-id" placeholder="id (\u0645\u062B\u0627\u0644: rose)" />
        <input id="gu-ar" placeholder="\u0627\u0644\u0627\u0633\u0645 \u0628\u0627\u0644\u0639\u0631\u0628\u064A\u0629" />
        <input id="gu-en" placeholder="label (en)" />
        <input id="gu-emoji" placeholder="\u{1F381}" style="max-width:5rem" />
        <input id="gu-tier" type="number" min="1" max="4" value="1" style="max-width:5rem" />
        <input id="gu-price" type="number" min="1" value="1" style="max-width:6rem" />
        <input id="gu-file" type="file" accept="video/mp4,.mp4,.svga" />
        <button class="act" id="gu-go">\u2b06\ufe0f \u0631\u0641\u0639</button>
      </div>
      <div class="gu-prog" id="gu-prog">
        <div class="gu-track"><div class="gu-fill" id="gu-fill"></div></div>
        <span class="gu-pct" id="gu-pct">0%</span>
      </div>
      <p class="help" id="gu-msg"></p>
    </div>
    ${drafts.length ? `<div class="card"><h3>\u0645\u0633\u0648\u062F\u0651\u0627\u062A (\u063A\u064A\u0631 \u0645\u0646\u0634\u0648\u0631\u0629)</h3>
      <table><thead><tr><th></th><th>\u0627\u0644\u0627\u0633\u0645</th><th>\u0627\u0644\u0641\u0626\u0629</th><th>\u0627\u0644\u0633\u0639\u0631</th><th>\u0645\u0642\u0637\u0639</th><th></th></tr></thead>
      <tbody>${drafts.map((g) => row(g, true)).join("")}</tbody></table></div>` : ""}
    <div class="card">
      <h3>\u0627\u0644\u0647\u062F\u0627\u064A\u0627 \u0627\u0644\u0645\u0646\u0634\u0648\u0631\u0629 (${gifts.length})</h3>
      ${gifts.length ? `<table><thead><tr><th></th><th>\u0627\u0644\u0627\u0633\u0645</th><th>\u0627\u0644\u0641\u0626\u0629</th><th>\u0627\u0644\u0633\u0639\u0631</th><th>\u0645\u0642\u0637\u0639</th><th></th></tr></thead>
      <tbody>${gifts.map((g) => row(g, false)).join("")}</tbody></table>` : '<div class="empty">\u0644\u0627 \u0647\u062F\u0627\u064A\u0627 \u0628\u0639\u062F.</div>'}
    </div>`;

  wireGiftUpload(d);

  view().querySelectorAll("[data-del]").forEach((b) => (b.onclick = async () => {
    if (!confirm("\u062D\u0630\u0641 \u0647\u0630\u0647 \u0627\u0644\u0647\u062F\u064A\u0629\u061F")) return;
    await api("/api/gifts/delete", { method: "POST", body: JSON.stringify({ id: b.dataset.del }) });
    toast("\u062A\u0645 \u0627\u0644\u062D\u0630\u0641");
    sections.gifts();
  }));
  view().querySelectorAll("[data-pub]").forEach((b) => (b.onclick = async () => {
    await api("/api/gifts/publish", { method: "POST", body: JSON.stringify({ id: b.dataset.pub }) });
    toast("\u062A\u0645 \u0627\u0644\u0646\u0634\u0631");
    sections.gifts();
  }));
  view().querySelectorAll("[data-disc]").forEach((b) => (b.onclick = async () => {
    await api("/api/gifts/discard", { method: "POST", body: JSON.stringify({ id: b.dataset.disc }) });
    toast("\u062A\u0645 \u0627\u0644\u062A\u062C\u0627\u0647\u0644");
    sections.gifts();
  }));
  view().querySelectorAll("[data-pv]").forEach((b) => (b.onclick = () => openGiftPreview(b.dataset.pv)));
};

// Upload a finished alpha video (or an SVGA) for a gift straight from the
// panel. The frame size is read here, in the browser, so the worker never has
// to decode anything: it only stores the bytes and the two numbers.
function wireGiftUpload(state) {
  const q = (id) => view().querySelector("#" + id);
  const go = q("gu-go");
  if (!go) return;
  const msg = (t, cls) => { const m = q("gu-msg"); m.textContent = t; m.className = "help " + (cls || ""); };
  // The bar shows bytes-in-flight; the worker then stores the object, so the
  // last slice is labelled rather than left stuck just under 100%.
  const setProg = (pct, label) => {
    const wrap = q("gu-prog");
    wrap.classList.add("on");
    q("gu-fill").style.width = Math.max(0, Math.min(100, pct)) + "%";
    q("gu-pct").textContent = label || (Math.round(pct) + "%");
  };
  const hideProg = () => { q("gu-prog").classList.remove("on"); q("gu-fill").style.width = "0%"; q("gu-pct").textContent = "0%"; };

  // Upload with progress. fetch() cannot report upload progress, so the
  // multipart POST goes through XHR, which fires upload.onprogress as the
  // browser sends the body. Resolves to the parsed JSON (or throws).
  const uploadWithProgress = (form) => new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/gifts/asset");
    xhr.setRequestHeader("authorization", "Bearer " + TOKEN);
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) setProg((e.loaded / e.total) * 100); };
    xhr.upload.onload = () => setProg(100, "\u0627\u0643\u062A\u0645\u0644 \u0627\u0644\u0631\u0641\u0639");
    xhr.onload = () => {
      let j = {};
      try { j = JSON.parse(xhr.responseText); } catch (e) { /* keep {} */ }
      if (xhr.status >= 200 && xhr.status < 300 && !j.error) resolve(j);
      else reject(new Error(j.error || ("HTTP " + xhr.status)));
    };
    xhr.onerror = () => reject(new Error("\u0627\u0646\u0642\u0637\u0639 \u0627\u0644\u0627\u062A\u0635\u0627\u0644"));
    xhr.ontimeout = () => reject(new Error("\u0627\u0646\u062A\u0647\u062A \u0627\u0644\u0645\u0647\u0644\u0629"));
    xhr.timeout = 10 * 60 * 1000;
    xhr.send(form);
  });

  const videoSize = (file) => new Promise((resolve) => {
    const v = document.createElement("video");
    v.preload = "metadata";
    v.onloadedmetadata = () => { URL.revokeObjectURL(v.src); resolve([v.videoWidth, v.videoHeight]); };
    v.onerror = () => { URL.revokeObjectURL(v.src); resolve([0, 0]); };
    v.src = URL.createObjectURL(file);
  });

  const svgaSize = (file) => new Promise((resolve) => {
    // SVGA is a zip; read the packed frame size from the first frame's header.
    const r = new FileReader();
    r.onload = () => {
      try {
        const dv = new DataView(r.result);
        for (let i = 0; i < dv.byteLength - 24; i++) {
          if (dv.getUint32(i) === 0x89504e47) {
            resolve([dv.getUint32(i + 16), dv.getUint32(i + 20)]);
            return;
          }
        }
      } catch (e) { /* fall through */ }
      resolve([0, 0]);
    };
    r.onerror = () => resolve([0, 0]);
    r.readAsArrayBuffer(file.slice(0, 1 << 16));
  });

  go.onclick = async () => {
    const id = (q("gu-id").value || "").trim().toLowerCase();
    const file = q("gu-file").files[0];
    if (!/^[a-z0-9_]{1,32}$/.test(id)) return msg("\u0627\u0644\u0645\u0639\u0631\u0651\u0641 \u063A\u064A\u0631 \u0635\u0627\u0644\u062D (a-z, 0-9, _)", "bad");
    if (!file) return msg("\u0627\u062E\u062A\u0631 \u0645\u0644\u0641\u0627\u064B \u0623\u0648\u0644\u0627\u064B", "bad");

    const isVideo = /\.mp4$/i.test(file.name) || file.type === "video/mp4";
    const kind = isVideo ? "videos" : "clips";
    msg("\u062C\u0627\u0631\u064D \u0627\u0644\u0631\u0641\u0639\u2026");
    go.disabled = true;
    setProg(0, "0%");
    try {
      const form = new FormData();
      form.append("kind", kind);
      form.append("id", id);
      form.append("file", file);
      if (isVideo) {
        // The packed file stacks colour over alpha, so its height is twice
        // the gift frame's. The app letterboxes the quad to the *frame*
        // size, so halve it here rather than making every client guess.
        const [w, h] = await videoSize(file);
        if (w > 0) form.append("width", String(w));
        if (h > 0) form.append("height", String(Math.round(h / 2)));
      } else {
        const [w, h] = await svgaSize(file);
        if (w > 0) form.append("width", String(w));
        if (h > 0) form.append("height", String(h));
      }
      await uploadWithProgress(form);
      setProg(100, "\u0627\u0643\u062A\u0645\u0644 \u0627\u0644\u0631\u0641\u0639");

      // Fill in the operator-facing fields on the same row, then publish.
      await api("/api/gifts/meta", {
        method: "POST",
        body: JSON.stringify({
          id,
          labelAr: q("gu-ar").value || id,
          labelEn: q("gu-en").value || id,
          emoji: q("gu-emoji").value || "\u{1F381}",
          tier: Number(q("gu-tier").value) || 1,
          price: Number(q("gu-price").value) || 1,
        }),
      });
      await api("/api/gifts/publish", { method: "POST", body: JSON.stringify({ id }) });
      toast("\u062A\u0645 \u0627\u0644\u0631\u0641\u0639 \u0648\u0627\u0644\u0646\u0634\u0631");
      hideProg();
      sections.gifts();
    } catch (e) {
      msg("\u0641\u0634\u0644: " + (e.message || e), "bad");
      hideProg();
      go.disabled = false;
    }
  };
}


// ---- live gift preview (added) --------------------------------------------
// Each gift's alpha video is *packed*: the packer stacks the colour frame over
// an alpha strip, so a plain <video> would show a double image. This rebuilds
// the app's own unpack on a WebGL canvas — sample RGB from the top half and
// alpha from the bottom half's green channel — then draws that canvas over a
// mock of the room, so the operator judges the real result (transparency and
// all) before it ships. The app's renderer lives in AlphaVideoRenderer.kt; the
// shader and the 95% vertical takeover below mirror it exactly.
let giftPreviewState = { gifts: [], drafts: [] };
let giftPreview = null;

function giftPreviewFind(id) {
  return (giftPreviewState.gifts || []).concat(giftPreviewState.drafts || []).find((g) => g && g.id === id) || null;
}

function closeGiftPreview() {
  if (giftPreview) {
    cancelAnimationFrame(giftPreview.raf);
    const v = giftPreview.video;
    if (v) { v.pause(); v.removeAttribute("src"); v.load(); }
  }
  document.querySelectorAll(".gift-pv").forEach((n) => n.remove());
  giftPreview = null;
}

function openGiftPreview(id) {
  const g = giftPreviewFind(id);
  if (!g || !g.video) return;
  closeGiftPreview();
  const st = Number(g.scale) || 1;
  const pv = {
    gift: g, fit: "contain", playing: true, loop: true, showRoom: true,
    scale: st, posX: Number(g.posX) || 0, posY: Number(g.posY) || 0, alpha: 1,
    inFrac: 0, outFrac: 1, raf: 0, video: null, gl: null,
  };
  giftPreview = pv;

  const ov = document.createElement("div");
  ov.className = "gift-pv";
  ov.innerHTML = `
    <div class="gp-box">
      <div class="gp-head">
        <b>${esc(g.emoji || "\u{1F381}")} ${esc(g.labelAr || g.id)}</b>
        <span class="muted">${esc(g.videoWidth || "?")}\u00D7${esc(g.videoHeight || "?")}</span>
        <button class="act ghost" data-gp-close>\u0625\u063A\u0644\u0627\u0642</button>
      </div>
      <div class="gp-body">
        <div class="gp-stagewrap"><div class="gp-stage" id="gpStage">
          <div class="gp-grid" id="gpGridLayer"></div>
        </div></div>
        <div class="gp-ctrls">
          <div class="gp-sect">\u0627\u0644\u062A\u0634\u063A\u064A\u0644 \u0648\u0627\u0644\u0641\u062D\u0635</div>
          <div class="gp-transport">
            <button data-gp="stepB" title="\u0625\u0637\u0627\u0631 \u0644\u0644\u062E\u0644\u0641">\u23EE</button>
            <button data-gp="play" title="\u062A\u0634\u063A\u064A\u0644/\u0625\u064A\u0642\u0627\u0641">\u23F8</button>
            <button data-gp="stepF" title="\u0625\u0637\u0627\u0631 \u0644\u0644\u0623\u0645\u0627\u0645">\u23ED</button>
            <button data-gp="loop" class="on" title="\u062A\u0643\u0631\u0627\u0631">\u{1F501}</button>
            <span class="gp-tc" id="gpTime">0.00 / 0.00</span>
          </div>
          <label>\u0627\u0644\u0645\u0648\u0636\u0639 \u0627\u0644\u0632\u0645\u0646\u064A <b id="gpLTime"></b>
            <input type="range" id="gpTime" min="0" max="1000" step="1" value="0"></label>
          <label>\u0633\u0631\u0639\u0629 \u0627\u0644\u062A\u0634\u063A\u064A\u0644 <b id="gpLSpeed"></b>
            <input type="range" id="gpSpeed" min="0.25" max="2" step="0.05" value="1"></label>
          <div class="gp-sect">\u0627\u0644\u0648\u0636\u0639 \u0639\u0644\u0649 \u0627\u0644\u063A\u0631\u0641\u0629</div>
          <label>\u0637\u0631\u064A\u0642\u0629 \u0627\u0644\u0645\u0644\u0621</label>
          <div class="gp-seg" id="gpSegFit">
            <button data-gp-fit="contain" class="on">\u0628\u0627\u0644\u062D\u062C\u0645 \u0627\u0644\u0623\u0635\u0644\u064A</button>
            <button data-gp-fit="fill">\u064A\u063A\u0637\u064A 95% \u0645\u0646 \u0627\u0644\u0637\u0648\u0644</button>
          </div>
          <label>\u0627\u0644\u062D\u062C\u0645 <b id="gpLScale"></b>
            <input type="range" id="gpScale" min="0.4" max="1.2" step="0.05" value="${st}"></label>
          <label>\u0627\u0644\u0645\u0648\u0636\u0639 \u0627\u0644\u0623\u0641\u0642\u064A <b id="gpLPosX"></b>
            <input type="range" id="gpPosX" min="-0.5" max="0.5" step="0.02" value="${pv.posX}"></label>
          <label>\u0627\u0644\u0645\u0648\u0636\u0639 \u0627\u0644\u0639\u0645\u0648\u062F\u064A <b id="gpLPosY"></b>
            <input type="range" id="gpPosY" min="-0.5" max="0.5" step="0.02" value="${pv.posY}"></label>
          <label>\u0627\u0644\u0634\u0641\u0627\u0641\u064A\u0629 <b id="gpLAlpha"></b>
            <input type="range" id="gpAlpha" min="0.1" max="1" step="0.05" value="1"></label>
          <label>\u0628\u062F\u0627\u064A\u0629 \u0627\u0644\u0645\u0642\u0637\u0639 <b id="gpLIn"></b>
            <input type="range" id="gpIn" min="0" max="1000" step="1" value="0"></label>
          <label>\u0646\u0647\u0627\u064A\u0629 \u0627\u0644\u0645\u0642\u0637\u0639 <b id="gpLOut"></b>
            <input type="range" id="gpOut" min="0" max="1000" step="1" value="1000"></label>
          <div class="gp-sect">\u0639\u0631\u0636</div>
          <div class="gp-seg">
            <button data-gp-room="1" class="on">\u0625\u0638\u0647\u0627\u0631 \u0627\u0644\u063A\u0631\u0641\u0629</button>
            <button data-gp-room="0">\u062E\u0644\u0641\u064A\u0629 \u0634\u0641\u0627\u0641\u0629</button>
          </div>
          <label class="gp-chk"><input type="checkbox" id="gpGrid"> \u0634\u0628\u0643\u0629 \u0627\u0644\u0645\u062D\u0627\u0630\u0627\u0629</label>
          <div class="gp-note">\u0645\u0639\u0627\u064A\u0646\u0629 \u0645\u0628\u0627\u0634\u0631\u0629 \u0628\u0646\u0641\u0633 \u0645\u0646\u0637\u0642 \u0627\u0644\u062A\u0637\u0628\u064A\u0642: \u064A\u064F\u0641\u0643 \u062A\u0631\u0643\u064A\u0628 \u0627\u0644\u0642\u0646\u0627\u0629 \u0627\u0644\u0634\u0641\u0627\u0641\u0629 \u0639\u0644\u0649 \u0627\u0644\u0634\u0627\u0634\u0629\u060C \u0641\u0645\u0627 \u062A\u0631\u0627\u0647 \u0647\u0646\u0627 \u0647\u0648 \u0645\u0627 \u064A\u0638\u0647\u0631 \u0641\u064A \u0627\u0644\u063A\u0631\u0641\u0629.</div>
        </div>
      </div>
      <div class="gp-actions">
        <button class="act" data-gp-save>\u062D\u0641\u0638 \u0627\u0644\u062D\u062C\u0645 \u0648\u0627\u0644\u0645\u0648\u0636\u0639</button>
        <button class="act ghost" data-gp-close>\u0625\u063A\u0644\u0627\u0642</button>
      </div>
    </div>
    <style>
      .gift-pv{position:fixed;inset:0;z-index:9999;background:rgba(4,6,12,.84);display:flex;align-items:center;justify-content:center;padding:14px}
      .gp-box{width:min(940px,96vw);max-height:95vh;overflow:auto;background:var(--panel,#141823);border:1px solid var(--line,#252c3d);border-radius:16px;padding:14px}
      .gp-head{display:flex;align-items:center;gap:10px;margin-bottom:10px}
      .gp-head .muted{margin-inline-start:auto}
      .gp-body{display:flex;gap:14px;flex-wrap:wrap}
      .gp-stagewrap{flex:1 1 320px}
      .gp-stage{position:relative;width:100%;max-width:340px;aspect-ratio:9/16;max-height:70vh;margin:auto;border-radius:16px;overflow:hidden;background:repeating-conic-gradient(#20242e 0% 25%,#171b24 0% 50%) 0 0/22px 22px}
      .gp-mock{position:absolute;inset:0;display:flex;flex-direction:column;background:linear-gradient(180deg,#0e1017,#0a0b10);transition:opacity .15s}
      .gp-gift{position:absolute;will-change:left,top,width,height;pointer-events:none}
      .gp-canvas,.gp-vid{width:100%;height:100%;display:block}
      .gp-bar{display:flex;align-items:center;gap:10px;padding:8px 12px;color:#dfe3ee;font-size:13px}
      .gp-top{border-bottom:1px solid #1c2130}.gp-name{font-weight:700}
      .gp-stagegrid{display:grid;grid-template-columns:repeat(5,1fr);gap:8px;padding:10px 12px}
      .gp-seat{aspect-ratio:1;border-radius:50%;display:flex;align-items:center;justify-content:center;background:rgba(124,77,255,.22);border:2px solid rgba(124,77,255,.7);font-size:16px}
      .gp-seat.occ{background:#7C4DFF}
      .gp-chat{flex:1;padding:10px 12px;display:flex;flex-direction:column;gap:6px;overflow:hidden}
      .gp-msg{align-self:flex-start;background:#1b2130;color:#cfd5e4;padding:6px 10px;border-radius:12px;font-size:12px;max-width:80%}
      .gp-msg.me{align-self:flex-end;background:#243056}
      .gp-bottom{border-top:1px solid #1c2130}
      .gp-star{color:#D7B45A}.gp-comp{flex:1;color:#8b93a7;background:#1b2130;border-radius:14px;padding:6px 12px}
      .gp-btn{width:32px;height:32px;border-radius:50%;background:#7C4DFF;display:flex;align-items:center;justify-content:center}
      .gp-grid{position:absolute;inset:0;pointer-events:none;opacity:0;transition:opacity .15s;background-image:linear-gradient(rgba(255,255,255,.16) 1px,transparent 1px),linear-gradient(90deg,rgba(255,255,255,.16) 1px,transparent 1px);background-size:10% 10%}
      .gp-ctrls{flex:1 1 260px;display:flex;flex-direction:column;gap:8px;min-width:250px}
      .gp-ctrls label{font-size:12px;color:var(--muted,#8b93a7);display:flex;flex-direction:column;gap:2px}
      .gp-ctrls input[type=range]{width:100%}
      .gp-sect{font-size:11px;font-weight:800;color:var(--acc,#4b6bfb);margin-top:4px;letter-spacing:.02em}
      .gp-transport{display:flex;align-items:center;gap:6px}
      .gp-transport button{width:34px;height:32px;border-radius:8px;background:var(--panel2,#1b2130);color:#e8ebf2;border:1px solid var(--line,#252c3d);cursor:pointer}
      .gp-tc{margin-inline-start:auto;font-size:11px;color:var(--muted,#8b93a7);font-variant-numeric:tabular-nums}
      .gp-seg{display:flex;gap:6px}
      .gp-seg button{flex:1;padding:7px 4px;font-size:11.5px;border-radius:9px;background:var(--panel2,#1b2130);color:#cbd2e0;border:1px solid var(--line,#252c3d);cursor:pointer}
      .gp-seg button.on{background:#7C4DFF;border-color:#7C4DFF;color:#fff;font-weight:700}
      .gp-chk{flex-direction:row;align-items:center;gap:6px}
      .gp-note{font-size:11px;color:var(--muted,#8b93a7);margin-top:6px;line-height:1.5}
      .gp-actions{display:flex;gap:8px;margin-top:12px}
    </style>`;
  document.body.appendChild(ov);

  const stage = ov.querySelector("#gpStage");
  const mock = document.createElement("div");
  mock.className = "gp-mock";
  mock.innerHTML = `
    <div class="gp-bar gp-top"><span>\u21A9</span><span>\u22EE</span><span class="gp-name">\u063A\u0631\u0641\u0629</span></div>
    <div class="gp-stagegrid">${Array.from({ length: 10 }, (_, i) => `<div class="gp-seat${i < 3 ? " occ" : ""}">${i < 3 ? "\u{1F464}" : "\u{1F3A4}"}</div>`).join("")}</div>
    <div class="gp-chat"><div class="gp-msg">\u0645\u0631\u062D\u0628\u064B\u0627 \u{1F44B}</div><div class="gp-msg me">\u0623\u0647\u0644\u0627\u064B</div></div>
    <div class="gp-bar gp-bottom"><span class="gp-star">\u2605</span><span class="gp-comp">\u0642\u0644 \u0634\u064A\u0626\u064B\u0627\u2026</span><span class="gp-btn">\u{1F3A4}</span><span class="gp-btn">\u{1F50A}</span></div>`;
  stage.insertBefore(mock, stage.firstChild);
  const gridLayer = ov.querySelector("#gpGridLayer");

  const layer = document.createElement("div");
  layer.className = "gp-gift";
  const canvas = document.createElement("canvas");
  canvas.className = "gp-canvas";
  layer.appendChild(canvas);
  stage.appendChild(layer);

  const video = document.createElement("video");
  video.playsInline = true; video.loop = true; video.muted = true;
  video.crossOrigin = "anonymous"; video.preload = "auto";
  video.src = "/gifts/videos/" + String(g.video).split("/").pop();
  video.style.display = "none";
  stage.appendChild(video);
  pv.video = video;

  const gl = canvas.getContext("webgl", { alpha: true, premultipliedAlpha: true });
  pv.gl = gl;
  let tex = null;
  if (gl) {
    // One full-screen quad. The fragment shader splits the packed frame: RGB
    // from the top half, alpha from the green channel of the bottom half.
    const vs = "attribute vec2 p;varying vec2 uv;void main(){uv=vec2(p.x*0.5+0.5,0.5-p.y*0.5);gl_Position=vec4(p,0.0,1.0);}";
    const fs = "precision mediump float;varying vec2 uv;uniform sampler2D tex;void main(){vec4 c=texture2D(tex,vec2(uv.x,0.5+uv.y*0.5));float a=texture2D(tex,vec2(uv.x,uv.y*0.5)).g;gl_FragColor=vec4(c.rgb*a,a);}";
    const mk = (type, src) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); return s; };
    const prog = gl.createProgram();
    gl.attachShader(prog, mk(gl.VERTEX_SHADER, vs));
    gl.attachShader(prog, mk(gl.FRAGMENT_SHADER, fs));
    gl.linkProgram(prog);
    if (gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      gl.useProgram(prog);
      const buf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, buf);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1]), gl.STATIC_DRAW);
      const loc = gl.getAttribLocation(prog, "p");
      gl.enableVertexAttribArray(loc);
      gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
      tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    } else { pv.gl = null; }
  }
  if (!pv.gl) {
    // No WebGL: fall back to the raw packed video so at least the motion is
    // visible (the alpha is not unpacked, so it will look doubled).
    canvas.style.display = "none";
    video.style.display = "block"; video.className = "gp-vid";
    layer.appendChild(video);
  }

  const q = (s) => ov.querySelector(s);
  const applyLayout = () => {
    const W = stage.clientWidth, H = stage.clientHeight;
    const vw = video.videoWidth || 0, vh = video.videoHeight || 0;
    // The packed file is two frames tall; the gift frame's aspect halves that.
    const frameAr = vw > 0 && vh > 0 ? vw / (vh / 2) : 1;
    let bw, bh;
    if (pv.fit === "fill") {
      bh = H * 0.95; bw = bh * frameAr;
    } else {
      const boxW = W * 0.95, boxH = H * 0.95;
      if (frameAr >= boxW / boxH) { bw = boxW; bh = boxW / frameAr; } else { bh = boxH; bw = boxH * frameAr; }
    }
    const w = bw * pv.scale, h = bh * pv.scale;
    layer.style.width = w + "px";
    layer.style.height = h + "px";
    layer.style.left = ((W - w) / 2 + pv.posX * W) + "px";
    layer.style.top = ((H - h) / 2 + pv.posY * H) + "px";
    layer.style.opacity = pv.alpha;
    mock.style.opacity = pv.showRoom ? "1" : "0";
  };
  const fmtT = (t) => (Number.isFinite(t) ? t.toFixed(2) : "0.00");

  const draw = () => {
    if (!giftPreview) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const cw = Math.max(1, Math.round(layer.clientWidth * dpr));
    const ch = Math.max(1, Math.round(layer.clientHeight * dpr));
    if (canvas.width !== cw || canvas.height !== ch) { canvas.width = cw; canvas.height = ch; }
    if (pv.gl) {
      pv.gl.viewport(0, 0, cw, ch);
      pv.gl.clearColor(0, 0, 0, 0);
      pv.gl.clear(pv.gl.COLOR_BUFFER_BIT);
      if (video.readyState >= 2 && tex) {
        pv.gl.bindTexture(pv.gl.TEXTURE_2D, tex);
        try { pv.gl.texImage2D(pv.gl.TEXTURE_2D, 0, pv.gl.RGBA, pv.gl.RGBA, pv.gl.UNSIGNED_BYTE, video); } catch (e) { /* not ready */ }
        pv.gl.drawArrays(pv.gl.TRIANGLES, 0, 6);
      }
    }
    const dur = video.duration || 0;
    if (dur && pv.outFrac < 1 && video.currentTime >= pv.outFrac * dur) video.currentTime = pv.inFrac * dur;
    q("#gpTime").textContent = fmtT(video.currentTime) + " / " + fmtT(dur);
    if (!q("#gpTime").dataset.drag) { q("#gpTime").value = dur ? Math.round((video.currentTime / dur) * 1000) : 0; }
    q("#gpLTime").textContent = fmtT(video.currentTime);
    pv.raf = requestAnimationFrame(draw);
  };

  const refreshLabels = () => {
    q("#gpLScale").textContent = pv.scale.toFixed(2) + "\u00D7";
    q("#gpLPosX").textContent = pv.posX.toFixed(2);
    q("#gpLPosY").textContent = pv.posY.toFixed(2);
    q("#gpLAlpha").textContent = Math.round(pv.alpha * 100) + "%";
    q("#gpLIn").textContent = fmtT(pv.inFrac * (video.duration || 0));
    q("#gpLOut").textContent = fmtT(pv.outFrac * (video.duration || 0));
    q("#gpLSpeed").textContent = (video.playbackRate || 1).toFixed(2) + "\u00D7";
    applyLayout();
  };

  const setPlaying = (on) => {
    pv.playing = on;
    q("[data-gp=play]").textContent = on ? "\u23F8" : "\u25B6";
    if (on) video.play().catch(() => {}); else video.pause();
  };

  video.addEventListener("loadedmetadata", () => { applyLayout(); refreshLabels(); video.play().catch(() => {}); });
  video.addEventListener("play", () => setPlaying(true));
  video.addEventListener("pause", () => { q("[data-gp=play]").textContent = "\u25B6"; });
  canvas.addEventListener("click", () => setPlaying(!pv.playing));

  q("[data-gp=play]").onclick = () => setPlaying(!pv.playing);
  q("[data-gp=stepB]").onclick = () => { video.pause(); video.currentTime = Math.max(0, video.currentTime - 1 / 30); };
  q("[data-gp=stepF]").onclick = () => { video.pause(); video.currentTime = Math.min(video.duration || 0, video.currentTime + 1 / 30); };
  q("[data-gp=loop]").onclick = (e) => { pv.loop = !pv.loop; video.loop = pv.loop; e.currentTarget.classList.toggle("on", pv.loop); };

  const ts = q("#gpTime");
  ts.addEventListener("input", () => { ts.dataset.drag = "1"; if (video.duration) video.currentTime = (ts.value / 1000) * video.duration; });
  ts.addEventListener("change", () => { delete ts.dataset.drag; });
  q("#gpSpeed").addEventListener("input", (e) => { video.playbackRate = Number(e.target.value); refreshLabels(); });
  q("#gpScale").addEventListener("input", (e) => { pv.scale = Number(e.target.value); refreshLabels(); });
  q("#gpPosX").addEventListener("input", (e) => { pv.posX = Number(e.target.value); refreshLabels(); });
  q("#gpPosY").addEventListener("input", (e) => { pv.posY = Number(e.target.value); refreshLabels(); });
  q("#gpAlpha").addEventListener("input", (e) => { pv.alpha = Number(e.target.value); refreshLabels(); });
  q("#gpIn").addEventListener("input", (e) => { pv.inFrac = Number(e.target.value) / 1000; if (pv.outFrac <= pv.inFrac) { pv.outFrac = Math.min(1, pv.inFrac + 0.02); q("#gpOut").value = pv.outFrac * 1000; } refreshLabels(); });
  q("#gpOut").addEventListener("input", (e) => { pv.outFrac = Number(e.target.value) / 1000; if (pv.inFrac >= pv.outFrac) { pv.inFrac = Math.max(0, pv.outFrac - 0.02); q("#gpIn").value = pv.inFrac * 1000; } refreshLabels(); });

  ov.querySelectorAll("[data-gp-fit]").forEach((b) => (b.onclick = () => {
    pv.fit = b.dataset.gpFit;
    ov.querySelectorAll("[data-gp-fit]").forEach((x) => x.classList.toggle("on", x === b));
    applyLayout();
  }));
  ov.querySelectorAll("[data-gp-room]").forEach((b) => (b.onclick = () => {
    pv.showRoom = b.dataset.gpRoom === "1";
    ov.querySelectorAll("[data-gp-room]").forEach((x) => x.classList.toggle("on", x === b));
    applyLayout();
  }));
  q("#gpGrid").onchange = (e) => { gridLayer.style.opacity = e.target.checked ? "1" : "0"; };
  q("[data-gp-save]").onclick = async () => {
    await api("/api/gifts/gift", { method: "POST", body: JSON.stringify({ id: g.id, scale: pv.scale, posX: pv.posX, posY: pv.posY }) });
    toast("\u062A\u0645 \u062D\u0641\u0638 \u0627\u0644\u062D\u062C\u0645 \u0648\u0627\u0644\u0645\u0648\u0636\u0639");
  };
  ov.querySelectorAll("[data-gp-close]").forEach((b) => (b.onclick = closeGiftPreview));
  ov.addEventListener("click", (e) => { if (e.target === ov) closeGiftPreview(); });
  document.addEventListener("keydown", function esc_(e) { if (e.key === "Escape") { closeGiftPreview(); document.removeEventListener("keydown", esc_); } });

  refreshLabels();
  draw();
}
