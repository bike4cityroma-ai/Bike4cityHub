import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.5/firebase-app.js";
import {
  getAuth,
  onAuthStateChanged,
  signOut,
  getIdTokenResult,
} from "https://www.gstatic.com/firebasejs/10.12.5/firebase-auth.js";
import {
  getFirestore,
  collection,
  addDoc,
  query,
  orderBy,
  limit,
  getDocs,
  serverTimestamp,
  doc,
  updateDoc,
  deleteDoc,
  getDoc,
} from "https://www.gstatic.com/firebasejs/10.12.5/firebase-firestore.js";

/* =======================
   CONFIG (stesso progetto social hub)
======================= */
const firebaseConfig = {
  apiKey: "AIzaSyDGFlcFie1odRVolXaAKnV_sAwHjNvE2WI",
  authDomain: "bike4city-social-hub.firebaseapp.com",
  databaseURL: "https://bike4city-social-hub-default-rtdb.firebaseio.com",
  projectId: "bike4city-social-hub",
  storageBucket: "bike4city-social-hub.firebasestorage.app",
  messagingSenderId: "1040753382248",
  appId: "1:1040753382248:web:3b632b6ba413b61ec8fcdd",
  measurementId: "G-ZKXSDJB2HK",
};

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const db = getFirestore(app);

const $ = (id) => document.getElementById(id);

function esc(s) {
  return (s || "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function setStatus(msg) {
  const el = $("formStatus");
  if (el) el.textContent = msg || "";
}

function setEditingBadge(msg) {
  const el = $("routeEditingBadge");
  if (el) el.textContent = msg || "";
}

function toNum(v, fallback = 0) {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

function toLatLng(latStr, lngStr) {
  const lat = latStr === "" || latStr == null ? null : Number(latStr);
  const lng = lngStr === "" || lngStr == null ? null : Number(lngStr);
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  return { lat, lng };
}

/* =======================
   AUTH / GUARD
======================= */
async function requireAdmin(user) {
  const tok = await getIdTokenResult(user, true);
  return tok?.claims?.role === "admin";
}

$("logoutBtn")?.addEventListener("click", async (e) => {
  e.preventDefault();
  await signOut(auth);
});

/* =======================
   Stato in memoria: stages & pois
======================= */
let stages = []; // { title, note, lat, lng, order }
let pois = [];   // { name, category, note, lat, lng }

function resetStageInputs() {
  if ($("sTitle")) $("sTitle").value = "";
  if ($("sNote")) $("sNote").value = "";
  if ($("sLat")) $("sLat").value = "";
  if ($("sLng")) $("sLng").value = "";
}

function resetPoiInputs() {
  if ($("pName")) $("pName").value = "";
  if ($("pCat")) $("pCat").value = "landmark";
  if ($("pNote")) $("pNote").value = "";
  if ($("pLat")) $("pLat").value = "";
  if ($("pLng")) $("pLng").value = "";
}

function renderStages() {
  const list = $("stagesList");
  if (!list) return;

  if (!stages.length) {
    list.innerHTML = "<p style='color:#666; margin:0;'>Nessuna tappa inserita.</p>";
    return;
  }

  const rows = stages
    .slice()
    .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
    .map((s, idx) => {
      const coord = (s.lat != null && s.lng != null) ? ` · (${s.lat.toFixed(5)}, ${s.lng.toFixed(5)})` : "";
      return `
        <div style="border:1px solid #eee; border-radius:10px; padding:10px; background:#fff;">
          <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
            <strong>#${idx + 1} — ${esc(s.title)}</strong>
            <span style="margin-left:auto; color:#666;">${coord ? esc(coord) : ""}</span>
          </div>
          ${s.note ? `<div style="color:#444; margin-top:6px;">${esc(s.note)}</div>` : ""}
          <div style="display:flex; gap:8px; flex-wrap:wrap; margin-top:10px;">
            <button type="button" data-sact="up" data-idx="${idx}">Su</button>
            <button type="button" data-sact="down" data-idx="${idx}">Giù</button>
            <button type="button" data-sact="del" data-idx="${idx}">Rimuovi</button>
          </div>
        </div>
      `;
    });

  list.innerHTML = rows.join("");

  list.querySelectorAll("button[data-sact]").forEach((btn) => {
    btn.onclick = (e) => {
      e.preventDefault();
      const act = btn.getAttribute("data-sact");
      const idx = Number(btn.getAttribute("data-idx"));
      const sorted = stages.slice().sort((a, b) => (a.order ?? 0) - (b.order ?? 0));

      // mappa idx visivo -> idx reale (per sicurezza)
      const stageRef = sorted[idx];
      const realIndex = stages.findIndex((x) => x === stageRef);
      if (realIndex < 0) return;

      if (act === "del") {
        stages.splice(realIndex, 1);
      }
      if (act === "up" && idx > 0) {
        const otherRef = sorted[idx - 1];
        const otherIndex = stages.findIndex((x) => x === otherRef);
        if (otherIndex >= 0) {
          const tmp = stages[realIndex].order;
          stages[realIndex].order = stages[otherIndex].order;
          stages[otherIndex].order = tmp;
        }
      }
      if (act === "down" && idx < sorted.length - 1) {
        const otherRef = sorted[idx + 1];
        const otherIndex = stages.findIndex((x) => x === otherRef);
        if (otherIndex >= 0) {
          const tmp = stages[realIndex].order;
          stages[realIndex].order = stages[otherIndex].order;
          stages[otherIndex].order = tmp;
        }
      }

      // normalizza order 1..n
      stages = stages
        .slice()
        .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
        .map((s, i) => ({ ...s, order: i + 1 }));

      renderStages();
    };
  });
}

function renderPois() {
  const list = $("poisList");
  if (!list) return;

  if (!pois.length) {
    list.innerHTML = "<p style='color:#666; margin:0;'>Nessun POI inserito.</p>";
    return;
  }

  const rows = pois.map((p, idx) => {
    const coord = (p.lat != null && p.lng != null) ? `(${p.lat.toFixed(5)}, ${p.lng.toFixed(5)})` : "";
    return `
      <div style="border:1px solid #eee; border-radius:10px; padding:10px; background:#fff;">
        <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
          <strong>${esc(p.name)}</strong>
          <span style="color:#666;">${esc(p.category || "other")}</span>
          <span style="margin-left:auto; color:#666;">${esc(coord)}</span>
        </div>
        ${p.note ? `<div style="color:#444; margin-top:6px;">${esc(p.note)}</div>` : ""}
        <div style="display:flex; gap:8px; flex-wrap:wrap; margin-top:10px;">
          <button type="button" data-pact="del" data-idx="${idx}">Rimuovi</button>
        </div>
      </div>
    `;
  });

  list.innerHTML = rows.join("");

  list.querySelectorAll("button[data-pact='del']").forEach((btn) => {
    btn.onclick = (e) => {
      e.preventDefault();
      const idx = Number(btn.getAttribute("data-idx"));
      if (Number.isFinite(idx)) {
        pois.splice(idx, 1);
        renderPois();
      }
    };
  });
}

/* =======================
   Lettura/Scrittura Form <-> Documento
======================= */
function getFormData(status) {
  const title = ($("rTitle")?.value || "").trim();
  const type = $("rType")?.value || "loop";
  const difficulty = $("rDifficulty")?.value || "easy";
  const description = ($("rDesc")?.value || "").trim();

  const startPoint = ($("rStart")?.value || "").trim();
  const endPoint = ($("rEnd")?.value || "").trim();

  const distanceKm = toNum($("rKm")?.value, 0);
  const ascentM = toNum($("rUp")?.value, 0);

  const surface = ($("rSurface")?.value || "").trim();
  const bikeSuggested = ($("rBike")?.value || "").trim();

  const tagsRaw = ($("rTags")?.value || "").trim();
  const tags = tagsRaw
    ? tagsRaw.split(",").map((t) => t.trim()).filter(Boolean).slice(0, 30)
    : [];

  const gpxText = ($("rGpx")?.value || "").trim();

  // Validazioni minime
  if (!title) return { error: "Titolo obbligatorio." };
  if (gpxText.length > 300000) return { error: "GPX troppo grande. Meglio passare a Storage." };
  if (type === "linear" && !endPoint) {
    // non blocco totale, ma suggerisco
    // se vuoi bloccare: return { error: "Per i percorsi lineari serve il punto di arrivo." };
  }

  // stages: ordina e normalizza
  const normStages = stages
    .slice()
    .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
    .map((s, i) => ({
      order: i + 1,
      title: (s.title || "").trim(),
      note: (s.note || "").trim(),
      lat: s.lat ?? null,
      lng: s.lng ?? null,
    }))
    .filter((s) => s.title);

  const normPois = pois
    .slice()
    .map((p) => ({
      name: (p.name || "").trim(),
      category: p.category || "other",
      note: (p.note || "").trim(),
      lat: p.lat ?? null,
      lng: p.lng ?? null,
    }))
    .filter((p) => p.name);

  return {
    data: {
      title,
      type,            // loop | linear
      difficulty,      // easy | medium | hard
      description,
      startPoint,
      endPoint: type === "linear" ? endPoint : "", // per loop lo svuotiamo
      distanceKm,
      ascentM,
      surface,
      bikeSuggested,
      tags,
      stages: normStages,
      pois: normPois,
      gpxText,
      status,          // draft | published
    }
  };
}

function fillFormFromDoc(routeId, r) {
  $("routeId").value = routeId;

  $("rTitle").value = r.title || "";
  $("rType").value = r.type || "loop";
  $("rDifficulty").value = r.difficulty || "easy";
  $("rDesc").value = r.description || "";

  $("rStart").value = r.startPoint || "";
  $("rEnd").value = r.endPoint || "";

  $("rKm").value = (r.distanceKm ?? 0);
  $("rUp").value = (r.ascentM ?? 0);

  $("rSurface").value = r.surface || "";
  $("rBike").value = r.bikeSuggested || "";

  $("rTags").value = Array.isArray(r.tags) ? r.tags.join(", ") : "";
  $("rGpx").value = r.gpxText || "";

  stages = Array.isArray(r.stages) ? r.stages.map((s) => ({
    title: s.title || "",
    note: s.note || "",
    lat: s.lat ?? null,
    lng: s.lng ?? null,
    order: s.order ?? 0,
  })) : [];

  // normalizza order
  stages = stages
    .slice()
    .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
    .map((s, i) => ({ ...s, order: i + 1 }));

  pois = Array.isArray(r.pois) ? r.pois.map((p) => ({
    name: p.name || "",
    category: p.category || "other",
    note: p.note || "",
    lat: p.lat ?? null,
    lng: p.lng ?? null,
  })) : [];

  renderStages();
  renderPois();

  setEditingBadge(`✏️ Modifica: ${r.title || ""}`);
  setStatus("");
}

function resetFormAll() {
  $("routeForm")?.reset();
  $("routeId").value = "";
  stages = [];
  pois = [];
  renderStages();
  renderPois();
  setEditingBadge("");
  setStatus("");
}

/* =======================
   Salvataggio: create o update
======================= */
async function saveRoute(status) {
  setStatus("");

  const routeId = ($("routeId")?.value || "").trim();
  const result = getFormData(status);
  if (result.error) {
    setStatus(result.error);
    return;
  }

  const payload = result.data;

  try {
    setStatus("Salvataggio…");

    if (!auth.currentUser) {
      setStatus("Sessione scaduta, rifai login.");
      return;
    }

    if (!routeId) {
      // CREATE
      await addDoc(collection(db, "routes"), {
        ...payload,
        createdBy: auth.currentUser.uid,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });
      setStatus(status === "published" ? "✅ Pubblicato" : "✅ Bozza salvata");
      resetFormAll();
    } else {
      // UPDATE
      await updateDoc(doc(db, "routes", routeId), {
        ...payload,
        updatedAt: serverTimestamp(),
      });
      setStatus(status === "published" ? "✅ Aggiornato e pubblicato" : "✅ Bozza aggiornata");
      // resta in edit, ma aggiorna lista
    }

    await loadRoutes();
  } catch (e) {
    setStatus("❌ " + (e?.message || "Errore salvataggio"));
  }
}

/* =======================
   Lista / Filtri / Azioni
======================= */
function routeMeta(r) {
  const parts = [];
  parts.push(r.type === "linear" ? "➡️ Lineare" : "🔁 Anello");
  if (r.distanceKm) parts.push(`🛣️ ${r.distanceKm} km`);
  if (r.ascentM) parts.push(`⛰️ ${r.ascentM} m`);
  if (r.startPoint) parts.push(`📍 ${r.startPoint}`);
  if (r.type === "linear" && r.endPoint) parts.push(`🏁 ${r.endPoint}`);
  if (r.difficulty) parts.push(`⚙️ ${r.difficulty}`);
  return parts.join(" · ");
}

function renderRouteCard(id, r) {
  const title = esc(r.title || "(senza titolo)");
  const status = esc(r.status || "draft");
  const meta = esc(routeMeta(r));
  const desc = esc(r.description || "");
  const tags = Array.isArray(r.tags) && r.tags.length ? r.tags.map(esc).join(", ") : "";
  const stageCount = Array.isArray(r.stages) ? r.stages.length : 0;
  const poiCount = Array.isArray(r.pois) ? r.pois.length : 0;

  const toggleBtn =
    status === "draft"
      ? `<button type="button" data-act="publish" data-id="${id}">Pubblica</button>`
      : `<button type="button" data-act="draft" data-id="${id}">Metti in bozza</button>`;

  return `
    <div style="border:1px solid #ddd; border-radius:10px; padding:14px; background:#fff;">
      <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
        <strong style="font-size:16px;">${title}</strong>
        <span style="margin-left:auto; color:#555;">${status}</span>
      </div>

      <div style="color:#666; margin-top:6px;">${meta}</div>

      ${desc ? `<p style="margin:10px 0 0;">${desc.replaceAll("\n","<br>")}</p>` : ""}

      <div style="color:#666; margin-top:8px;">
        🧩 Tappe: <strong>${stageCount}</strong> · 📌 POI: <strong>${poiCount}</strong>
        ${tags ? ` · 🏷️ ${tags}` : ""}
      </div>

      <div style="display:flex; gap:10px; margin-top:12px; flex-wrap:wrap;">
        <button type="button" data-act="edit" data-id="${id}">Modifica</button>
        ${toggleBtn}
        <button type="button" data-act="delete" data-id="${id}">Cancella</button>
      </div>
    </div>
  `;
}

async function loadRoutes() {
  const list = $("routesList");
  if (!list) return;

  list.innerHTML = "<p style='color:#666'>Caricamento…</p>";

  const snap = await getDocs(
    query(collection(db, "routes"), orderBy("createdAt", "desc"), limit(200))
  );

  const filterStatus = $("filterStatus")?.value || "all";
  const filterType = $("filterType")?.value || "all";
  const qtxt = ($("searchInput")?.value || "").trim().toLowerCase();

  const rows = [];
  snap.forEach((d) => {
    const r = d.data();

    const st = (r.status || "draft");
    const tp = (r.type || "loop");

    if (filterStatus !== "all" && st !== filterStatus) return;
    if (filterType !== "all" && tp !== filterType) return;

    const hay = [
      r.title || "",
      r.description || "",
      Array.isArray(r.tags) ? r.tags.join(" ") : "",
      r.startPoint || "",
      r.endPoint || "",
    ].join(" ").toLowerCase();

    if (qtxt && !hay.includes(qtxt)) return;

    rows.push(renderRouteCard(d.id, r));
  });

  list.innerHTML = rows.length ? rows.join("") : "<p style='color:#666'>Nessun percorso.</p>";

  list.querySelectorAll("button[data-act]").forEach((btn) => {
    btn.onclick = async (e) => {
      e.preventDefault();
      const act = btn.getAttribute("data-act");
      const id = btn.getAttribute("data-id");

      try {
        if (act === "delete") {
          if (!confirm("Cancellare definitivamente questo percorso?")) return;
          await deleteDoc(doc(db, "routes", id));
          // se stavi editando quello stesso percorso, reset
          if (($("routeId")?.value || "") === id) resetFormAll();
          await loadRoutes();
          return;
        }

        if (act === "publish") {
          await updateDoc(doc(db, "routes", id), {
            status: "published",
            updatedAt: serverTimestamp(),
          });
          await loadRoutes();
          return;
        }

        if (act === "draft") {
          await updateDoc(doc(db, "routes", id), {
            status: "draft",
            updatedAt: serverTimestamp(),
          });
          await loadRoutes();
          return;
        }

        if (act === "edit") {
          setStatus("Carico…");
          const snap = await getDoc(doc(db, "routes", id));
          if (!snap.exists()) {
            setStatus("Percorso non trovato.");
            return;
          }
          fillFormFromDoc(id, snap.data());
          setStatus("✅ In modifica");
          // scroll su form
          window.scrollTo({ top: 0, behavior: "smooth" });
          return;
        }
      } catch (err) {
        setStatus("❌ " + (err?.message || "Errore azione"));
      }
    };
  });
}

/* =======================
   Eventi: Aggiungi tappa / POI
======================= */
$("addStageBtn")?.addEventListener("click", (e) => {
  e.preventDefault();

  const title = ($("sTitle")?.value || "").trim();
  const note = ($("sNote")?.value || "").trim();
  const coord = toLatLng($("sLat")?.value ?? "", $("sLng")?.value ?? "");

  if (!title) {
    setStatus("Inserisci almeno il titolo della tappa.");
    return;
  }

  const nextOrder = stages.length ? Math.max(...stages.map(s => s.order || 0)) + 1 : 1;

  stages.push({
    title,
    note,
    lat: coord?.lat ?? null,
    lng: coord?.lng ?? null,
    order: nextOrder,
  });

  // normalizza
  stages = stages
    .slice()
    .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
    .map((s, i) => ({ ...s, order: i + 1 }));

  resetStageInputs();
  renderStages();
  setStatus("");
});

$("addPoiBtn")?.addEventListener("click", (e) => {
  e.preventDefault();

  const name = ($("pName")?.value || "").trim();
  const category = $("pCat")?.value || "other";
  const note = ($("pNote")?.value || "").trim();
  const coord = toLatLng($("pLat")?.value ?? "", $("pLng")?.value ?? "");

  if (!name) {
    setStatus("Inserisci il nome del POI.");
    return;
  }
  if (!coord) {
    setStatus("Per i POI servono lat e lng validi.");
    return;
  }

  pois.push({
    name,
    category,
    note,
    lat: coord.lat,
    lng: coord.lng,
  });

  resetPoiInputs();
  renderPois();
  setStatus("");
});

/* =======================
   Eventi: Salva / Pubblica / Reset / Filtri
======================= */
$("routeForm")?.addEventListener("submit", async (e) => {
  e.preventDefault();
  await saveRoute("draft");
});

$("publishBtn")?.addEventListener("click", async (e) => {
  e.preventDefault();
  await saveRoute("published");
});

$("resetBtn")?.addEventListener("click", (e) => {
  e.preventDefault();
  resetFormAll();
});

$("refreshBtn")?.addEventListener("click", async (e) => {
  e.preventDefault();
  await loadRoutes();
});

$("filterStatus")?.addEventListener("change", loadRoutes);
$("filterType")?.addEventListener("change", loadRoutes);

let searchTimer = null;
$("searchInput")?.addEventListener("input", () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(loadRoutes, 150);
});

/* =======================
   BOOT
======================= */
onAuthStateChanged(auth, async (user) => {
  if (!user) {
    location.replace("/index.html");
    return;
  }

  const ok = await requireAdmin(user);
  if (!ok) {
    alert("Accesso non autorizzato");
    location.replace("/index.html");
    return;
  }

  // init UI
  renderStages();
  renderPois();
  await loadRoutes();
});
