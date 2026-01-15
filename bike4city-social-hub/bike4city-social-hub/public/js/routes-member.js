import { auth, db } from "/js/firebase.js";
import { requireAuth } from "/js/guard.js";
import { signOut } from "https://www.gstatic.com/firebasejs/10.12.5/firebase-auth.js";
import {
  collection, query, where, orderBy, limit, getDocs
} from "https://www.gstatic.com/firebasejs/10.12.5/firebase-firestore.js";

const $ = (id) => document.getElementById(id);

function escapeHtml(s) {
  return (String(s ?? ""))
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function setListHtml(html) {
  const el = $("list");
  if (el) el.innerHTML = html;
}

function renderEmpty(msg) {
  setListHtml(`<div class="empty">${escapeHtml(msg || "Nessun elemento")}</div>`);
}

function renderError(msg) {
  setListHtml(`<div class="empty">❗ ${escapeHtml(msg || "Errore")}</div>`);
}

function renderItems(docs) {
  const el = $("list");
  el.innerHTML = "";

  docs.forEach((d) => {
    const r = d.data() || {};
    const item = document.createElement("div");
    item.className = "list-item";

    const main = document.createElement("div");
    main.className = "list-main";

    const title = document.createElement("div");
    title.className = "list-title";
    title.textContent = String(r.title || "Senza titolo");

    const meta = document.createElement("div");
    meta.className = "muted";
    meta.textContent = `${Number(r.distanceKm || 0).toFixed(2)} km · ${Math.round(r.ascentM || 0)} m · ${String(r.status || "—")}`;

    const by = document.createElement("div");
    by.className = "muted";
    by.textContent = `di ${r.createdByName || r.createdByEmail || r.createdByUid || "—"}`;

    main.appendChild(title);
    main.appendChild(meta);
    main.appendChild(by);

    const actions = document.createElement("div");
    actions.className = "list-actions";


    const btnOpen = document.createElement("button");
    btnOpen.className = "btn btn-sm";
    btnOpen.type = "button";
    btnOpen.textContent = "🗺️ Apri nel planner";
    btnOpen.addEventListener("click", () => {
      location.href = `/soci/planner-socio.html?open=routes_member:${d.id}`;
    });

    const btnDl = document.createElement("button");
    btnDl.className = "btn btn-sm";
    btnDl.type = "button";
    btnDl.textContent = "⬇️ Scarica GPX";
    btnDl.addEventListener("click", () => {
      const gpx = String(r.gpxText || "");
      if (!gpx) return alert("Nessun GPX salvato per questa traccia.");
      const safe = String(r.title || "traccia").replaceAll(/[^a-z0-9]+/gi, "_").replaceAll(/^_+|_+$/g, "");
      const blob = new Blob([gpx], { type: "application/gpx+xml" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = `${safe || "traccia"}.gpx`;
      a.click();
      URL.revokeObjectURL(a.href);
    });

    const btnCopy = document.createElement("button");
    btnCopy.className = "btn btn-sm";
    btnCopy.type = "button";
    btnCopy.textContent = "📋 Copia GPX";
    btnCopy.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(String(r.gpxText || ""));
        btnCopy.textContent = "Copiato ✅";
        setTimeout(() => (btnCopy.textContent = "📋 Copia GPX"), 900);
      } catch (e) {
        alert("Impossibile copiare negli appunti");
      }
    });

    actions.appendChild(btnOpen);
    actions.appendChild(btnCopy);
    actions.appendChild(btnDl);
    item.appendChild(main);
    item.appendChild(actions);
    el.appendChild(item);
  });
}

async function logout() {
  try { await signOut(auth); } catch (e) {}
  location.href = "/index.html";
}

async function safeQueryRun(q) {
  try {
    return await getDocs(q);
  } catch (e) {
    const msg = String(e?.message || "");
    const isIndexErr = msg.toLowerCase().includes("requires an index");
    if (!isIndexErr) throw e;

    // Fallback: senza where, filtro lato client (evita blocco infinito su "Caricamento…")
    const qFallback = query(collection(db, "routes_member"), orderBy("updatedAt", "desc"), limit(200));
    const snap2 = await getDocs(qFallback);
    snap2.__fallback = true;
    return snap2;
  }
}

let session = null;
let mode = "suggested"; // suggested | public | mine
let allDocsFallback = null;

function applyClientFilter(docs) {
  const qText = ($("q")?.value || "").trim().toLowerCase();

  let items = docs.map(d => ({ id: d.id, data: d.data(), _doc: d }))
    .filter(x => !!x.data);

  if (allDocsFallback) {
    // se siamo in fallback, applichiamo filtro per mode lato client
    if (mode === "mine") items = items.filter(x => x.data.ownerUid === session.user.uid);
    if (mode === "suggested") items = items.filter(x => x.data.status === "suggested");
    if (mode === "public") items = items.filter(x => x.data.status === "public");
  }

  if (qText) items = items.filter(x => String(x.data.title || "").toLowerCase().includes(qText));

  return items.map(x => x._doc);
}

async function load() {
  setListHtml("Caricamento…");

  const who = $("whoami");
  if (who) who.textContent = session?.user?.email ? `Loggato come ${session.user.email}` : "";

  let qy;

  if (mode === "mine") {
    qy = query(
      collection(db, "routes_member"),
      where("ownerUid", "==", session.user.uid),
      orderBy("updatedAt", "desc"),
      limit(200)
    );
  } else if (mode === "public") {
    qy = query(
      collection(db, "routes_member"),
      where("status", "==", "public"),
      orderBy("updatedAt", "desc"),
      limit(200)
    );
  } else {
    qy = query(
      collection(db, "routes_member"),
      where("status", "==", "suggested"),
      orderBy("updatedAt", "desc"),
      limit(200)
    );
  }

  let snap;
  try {
    snap = await safeQueryRun(qy);
  } catch (e) {
    console.error("routes_member load error:", e);
    renderError(String(e?.message || e));
    return;
  }

  allDocsFallback = snap.__fallback ? snap.docs : null;

  const docs = applyClientFilter(snap.docs);

  const countEl = $("count");
  if (countEl) countEl.textContent = String(docs.length);

  if (!docs.length) return renderEmpty("Nessuna traccia trovata.");
  renderItems(docs);
}

async function main() {
  // auth
  session = await requireAuth({ target: "soci", requireRole: "member" });
  if (!session) { renderError("Accesso negato (login/ruolo)."); return; }

  // logout wiring
  const logoutBtn = $("logoutBtn");
  if (logoutBtn) logoutBtn.addEventListener("click", (e) => { e.preventDefault(); logout(); });

  // tabs
  const setActive = () => {
    const map = { suggested: "tabSuggested", public: "tabPublic", mine: "tabMine" };
    Object.entries(map).forEach(([k, id]) => {
      const b = $(id);
      if (!b) return;
      b.classList.toggle("btn-primary", mode === k);
    });
  };

  const bindTab = (id, m) => {
    const b = $(id);
    if (!b) return;
    b.addEventListener("click", async () => { mode = m; setActive(); await load(); });
  };

  bindTab("tabSuggested", "suggested");
  bindTab("tabPublic", "public");
  bindTab("tabMine", "mine");
  setActive();

  // search
  const q = $("q");
  if (q) q.addEventListener("input", () => load());

  // reload
  const btnReload = $("btnReload");
  if (btnReload) btnReload.addEventListener("click", () => load());

  await load();
}

main().catch((e) => {
  console.error(e);
  renderError(String(e?.message || e));
});
