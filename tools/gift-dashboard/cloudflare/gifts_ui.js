// ---- gifts panel (added) ---------------------------------------------------
// A "الهدايا" tab inside the existing moderation panel. It reads and writes the
// same R2 state the app consumes, so what you see here is exactly what ships.
// Authoring (video -> SVGA) still happens locally; this tab edits the live rows
// and uploads the finished alpha video for a gift.
sections.gifts = async () => {
  const d = await api("/api/gifts/state");
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
};

// Upload a finished alpha video (or an SVGA) for a gift straight from the
// panel. The frame size is read here, in the browser, so the worker never has
// to decode anything: it only stores the bytes and the two numbers.
function wireGiftUpload(state) {
  const q = (id) => view().querySelector("#" + id);
  const go = q("gu-go");
  if (!go) return;
  const msg = (t, cls) => { const m = q("gu-msg"); m.textContent = t; m.className = "help " + (cls || ""); };

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
      const res = await fetch("/api/gifts/asset", {
        method: "POST",
        headers: { authorization: "Bearer " + TOKEN },
        body: form,
      });
      const j = await res.json();
      if (!res.ok || j.error) throw new Error(j.error || ("HTTP " + res.status));

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
      sections.gifts();
    } catch (e) {
      msg("\u0641\u0634\u0644: " + (e.message || e), "bad");
      go.disabled = false;
    }
  };
}

