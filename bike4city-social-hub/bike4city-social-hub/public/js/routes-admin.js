import { auth, db } from "/js/firebase.js";
import { requireAuth } from "/js/guard.js";

import { signOut } from "https://www.gstatic.com/firebasejs/10.12.5/firebase-auth.js";
import {
  collection,
  query,
  orderBy,
  limit,
  getDocs,
  deleteDoc,
  doc,
  addDoc,
  serverTimestamp,
} from "https://www.gstatic.com/firebasejs/10.12.5/firebase-firestore.js";

const $ = (id) => document.getElementById(id);

function esc(s) {
  return String(s ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function fmtNum(n, d = 1) {
  const x = Number(n);
  if (!Number.isFinite(x)) return "–";
  return x.toFixed(d);
}

function fmtDate(ts) {
  try {
    const d = ts?.toDate ? ts.toDate() : (ts instanceof Date ? ts : null);
    if (!d) return "";
    return d.toLocaleString("it-IT");
  } catch {
    return "";
  }
}

function pickAscent(data) {
  if (data?.ascentM != null) return data.ascentM;
  if (data?.ascent != null) return data.ascent;
  return null;
}

function pickGpx(data) {
  return data?.gpxText || data?.gpx || "";
}

async function copyToClipboard(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    // fallback
    try {
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.style.position = "fixed";
      ta.style.left = "-9999px";
      document.body.appendChild(ta);
      ta.select();
      document.execCommand("copy");
      ta.remove();
      return true;
    } catch {
      return false;
    }
  }
}

let allRoutes = []; // { id, data }

function renderList(filterText = "") {
  const list = $("list");
  if (!list) return;

  const q = (filterText || "").trim().toLowerCase();
  const rows = allRoutes
    .filter((r) => {
      if (!q) return true;
      const t = String(r.data?.title || "").toLowerCase();
      return t.includes(q);
    })
    .map(({ id, data }) => {
      const title = data?.title || "(senza titolo)";
      const km = data?.distanceKm;
      const up = pickAscent(data);
      const diff = data?.difficulty || "";
      const type = data?.routeType || "";
      const by = data?.createdByName || data?.createdByEmail || data?.createdByUid || "";
      const updated = fmtDate(data?.updatedAt || data?.createdAt);

      const tags = [
        km != null ? `${fmtNum(km, 2)} km` : null,
        up != null ? `${fmtNum(up, 0)} m+` : null,
        diff ? `🎚️ ${esc(diff)}` : null,
        type ? `🏷️ ${esc(type)}` : null,
      ].filter(Boolean);

      return `
        <div class="list-item" data-id="${esc(id)}">
          <div class="list-main">
            <div class="list-title">${esc(title)}</div>
            <div class="list-meta">
              ${esc(by)}${updated ? " · " + esc(updated) : ""}
              ${tags.length ? " · " + tags.map(t => `<span class=\"pill pill-soft\">${t}</span>`).join(" ") : ""}
            </div>
          </div>

          <div class="list-actions" style="display:flex; gap:8px; flex-wrap:wrap; justify-content:flex-end;">
            <button class="btn btn-sm" data-act="open">🗺️ Apri nel planner</button>
            <button class="btn btn-sm" data-act="copy">📋 GPX</button>
            <button class="btn btn-sm" data-act="dl">⬇️ Scarica GPX</button>
            <button class="btn btn-sm" data-act="dup">🧬 Duplica</button>
            <button class="btn btn-sm btn-danger" data-act="del">🗑️ Elimina</button>
          </div>

          <div class="list-msg muted" style="margin-top:8px;"></div>
        </div>
      `;
    });

  $("count").textContent = String(rows.length);

  if (!rows.length) {
    list.innerHTML = `<div class="empty">Nessun percorso trovato.</div>`;
    return;
  }

  list.innerHTML = rows.join("");

  list.querySelectorAll(".list-item").forEach((el) => {
    const id = el.getAttribute("data-id");
    const msg = el.querySelector(".list-msg");
    const getData = () => allRoutes.find((r) => r.id === id)?.data || null;

    el.querySelectorAll("button[data-act]").forEach((b) => {
      b.addEventListener("click", async (e) => {
        e.preventDefault();
        const act = b.getAttribute("data-act");
        if (!act) return;

        const data = getData();
        if (!data) return;

        if (msg) msg.textContent = "";


        if (act === "open") {
  location.href = `/admin/hub-percorsi.html?open=routes_admin:${id}`;
  return;
}
        if (act === "dl") {
          const gpx = pickGpx(data);
          if (!gpx) {
            if (msg) msg.textContent = "Questo percorso non ha un GPX salvato.";
            return;
          }
          const safe = String(data.title || "traccia").replaceAll(/[^a-z0-9]+/gi, "_").replaceAll(/^_+|_+$/g, "");
          const blob = new Blob([gpx], { type: "application/gpx+xml" });
          const a = document.createElement("a");
          a.href = URL.createObjectURL(blob);
          a.download = `${safe || "traccia"}.gpx`;
          a.click();
          URL.revokeObjectURL(a.href);
          return;
        }

        if (act === "copy") {
          const gpx = pickGpx(data);
          if (!gpx) {
            if (msg) msg.textContent = "Questo percorso non ha un GPX salvato.";
            return;
          }
          const ok = await copyToClipboard(gpx);
          if (msg) msg.textContent = ok ? "GPX copiato negli appunti ✅" : "Impossibile copiare."
          return;
        }

        if (act === "dup") {
          b.disabled = true;
          try {
            const payload = {
              ...data,
              title: (data.title || "(senza titolo)") + " (copia)",
              createdAt: serverTimestamp(),
              updatedAt: serverTimestamp(),
            };
            // non duplicare eventuali id di collegamento
            delete payload.sourceAdminRouteId;
            delete payload.id;
            await addDoc(collection(db, "routes_admin"), payload);
            if (msg) msg.textContent = "Duplicato creato ✅";
            await load();
          } catch (err) {
            if (msg) msg.textContent = "Errore duplicazione: " + (err?.message || "unknown");
          } finally {
            b.disabled = false;
          }
          return;
        }

        if (act === "del") {
          const ok = confirm("Eliminare definitivamente questa traccia?\n\n" + (data.title || ""));
          if (!ok) return;
          b.disabled = true;
          try {
            await deleteDoc(doc(db, "routes_admin", id));
            if (msg) msg.textContent = "Eliminata ✅";
            await load();
          } catch (err) {
            if (msg) msg.textContent = "Errore eliminazione: " + (err?.message || "unknown");
          } finally {
            b.disabled = false;
          }
          return;
        }
      });
    });
  });
}

async function load() {
  const list = $("list");
  if (list) list.textContent = "Caricamento…";

  const q = query(collection(db, "routes_admin"), orderBy("updatedAt", "desc"), limit(200));
  const snap = await getDocs(q);
  allRoutes = snap.docs.map((d) => ({ id: d.id, data: d.data() || {} }));
  renderList($("q")?.value || "");
}

// --- boot ---
await requireAuth({ target: "admin", requireRole: "admin" });

$("logoutBtn")?.addEventListener("click", async (e) => {
  e.preventDefault();
  try { await signOut(auth); } catch {}
  location.href = "/index.html";
});

$("btnReload")?.addEventListener("click", () => load());

$("q")?.addEventListener("input", (e) => {
  renderList(e.target?.value || "");
});

const who = $("whoami");
if (who && auth.currentUser) {
  who.textContent = `Loggato come ${auth.currentUser.email || auth.currentUser.uid}`;
}

await load();
