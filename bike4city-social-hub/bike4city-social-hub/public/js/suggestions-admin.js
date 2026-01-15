import { auth, db } from "/js/firebase.js";
import { requireAuth } from "/js/guard.js";
import { signOut } from "https://www.gstatic.com/firebasejs/10.12.5/firebase-auth.js";
import {
  collection, query, where, orderBy, limit, getDocs,
  doc, updateDoc, addDoc, serverTimestamp
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

async function logout() {
  try { await signOut(auth); } catch {}
  location.href = "/index.html";
}

function setList(html) { $("list").innerHTML = html; }

function routePayloadFromSuggestion(s, session) {
  const name = session.profile?.displayName || session.profile?.name || session.user.displayName || session.user.email;

  return {
    title: s.title || "Percorso",
    description: s.description || "",
    gpxText: s.gpxText || "",
    distanceKm: Number(s.distanceKm || 0),
    ascentM: Number(s.ascentM || 0),
    descentM: Number(s.descentM || 0),

    // autore originario
    createdByUid: s.suggestedByUid || "",
    createdByEmail: s.suggestedByEmail || "",
    createdByName: s.suggestedByName || "",

    // admin che approva/pubblica
    moderatedByUid: session.user.uid,
    moderatedByEmail: session.user.email || "",
    moderatedByName: name || "",

    updatedAt: serverTimestamp(),
  };
}

async function load(session) {
  setList("Caricamento…");

  const qText = ($("q").value || "").trim().toLowerCase();

  // pending più recenti
  const qy = query(
    collection(db, "routes_suggestions"),
    where("status", "==", "pending"),
    orderBy("updatedAt", "desc"),
    limit(200)
  );

  let snap;
  try {
    snap = await getDocs(qy);
  } catch (e) {
    console.error(e);
    setList(`❗ Errore: ${escapeHtml(e?.message || e)}`);
    return;
  }

  let docs = snap.docs;

  // filtro titolo lato client (semplice e stabile)
  if (qText) {
    docs = docs.filter(d => String(d.data()?.title || "").toLowerCase().includes(qText));
  }

  $("count").textContent = String(docs.length);

  if (!docs.length) {
    setList(`<div class="empty">Nessuna proposta in attesa 🎉</div>`);
    return;
  }

  // render lista
  $("list").innerHTML = "";
  for (const d of docs) {
    const s = d.data() || {};
    const card = document.createElement("div");
    card.className = "list-item";

    const main = document.createElement("div");
    main.className = "list-main";

    const title = document.createElement("div");
    title.className = "list-title";
    title.textContent = s.title || "Senza titolo";

    const meta = document.createElement("div");
    meta.className = "muted";
    meta.textContent = `${Number(s.distanceKm || 0).toFixed(2)} km · ${Math.round(s.ascentM || 0)} m`;

    const by = document.createElement("div");
    by.className = "muted";
    by.textContent = `proposta da ${s.suggestedByName || s.suggestedByEmail || s.suggestedByUid || "—"}`;

    main.appendChild(title);
    main.appendChild(meta);
    main.appendChild(by);

    const actions = document.createElement("div");
    actions.className = "list-actions";

    const btnView = document.createElement("button");
    btnView.className = "btn btn-sm";
    btnView.type = "button";
    btnView.textContent = "🗺️ Visualizza nel planner";
    btnView.onclick = () => {
      location.href = `/admin/hub-percorsi.html?open=routes_suggestions:${d.id}`;
    };

    const btnCopy = document.createElement("button");
    btnCopy.className = "btn btn-sm";
    btnCopy.type = "button";
    btnCopy.textContent = "📋 Copia GPX";
    btnCopy.onclick = async () => {
      try {
        await navigator.clipboard.writeText(String(s.gpxText || ""));
        btnCopy.textContent = "Copiato ✅";
        setTimeout(() => (btnCopy.textContent = "📋 Copia GPX"), 900);
      } catch {
        alert("Impossibile copiare negli appunti");
      }
    };

    const btnAccept = document.createElement("button");
    btnAccept.className = "btn btn-sm btn-primary";
    btnAccept.type = "button";
    btnAccept.textContent = "✅ Accetta (Admin)";
    btnAccept.onclick = async () => {
      btnAccept.disabled = true;
      try {
        // 1) crea in routes_admin
        const payload = {
          ...routePayloadFromSuggestion(s, session),
          source: "member-suggestion",
          status: "draft",
          createdAt: serverTimestamp(),
        };
        const adminDoc = await addDoc(collection(db, "routes_admin"), payload);

        // 2) marca suggestion accepted con riferimento
        await updateDoc(doc(db, "routes_suggestions", d.id), {
          status: "accepted",
          acceptedAt: serverTimestamp(),
          acceptedAdminRouteId: adminDoc.id,
          updatedAt: serverTimestamp(),
        });

        await load(session);
      } catch (e) {
        console.error(e);
        alert("Errore accettazione: " + (e?.message || e));
        btnAccept.disabled = false;
      }
    };

    const btnPublish = document.createElement("button");
    btnPublish.className = "btn btn-sm";
    btnPublish.type = "button";
    btnPublish.textContent = "📣 Pubblica ai soci";
    btnPublish.onclick = async () => {
      btnPublish.disabled = true;
      try {
        // pubblicazione diretta in routes_member (public)
        const payload = {
          ...routePayloadFromSuggestion(s, session),

          // 🔥 nuovi campi per APP (Bike4City archive)
          isB4C: true,
          b4cCategory: "COMMUNITY",

          source: "admin-published",
          status: "public",
          createdAt: serverTimestamp(),
          // NOTA: ownerUid lasciato vuoto -> è “pubblico”, non personale
        };

        const pubDoc = await addDoc(collection(db, "routes_member"), payload);

        await updateDoc(doc(db, "routes_suggestions", d.id), {
          status: "accepted",
          publishedAt: serverTimestamp(),
          publishedMemberRouteId: pubDoc.id,
          updatedAt: serverTimestamp(),
        });

        await load(session);
      } catch (e) {
        console.error(e);
        alert("Errore pubblicazione: " + (e?.message || e));
        btnPublish.disabled = false;
      }
    };

    const btnReject = document.createElement("button");
    btnReject.className = "btn btn-sm";
    btnReject.type = "button";
    btnReject.textContent = "❌ Rifiuta";
    btnReject.onclick = async () => {
      if (!confirm("Rifiutare questa proposta?")) return;
      btnReject.disabled = true;
      try {
        await updateDoc(doc(db, "routes_suggestions", d.id), {
          status: "rejected",
          rejectedAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        });
        await load(session);
      } catch (e) {
        console.error(e);
        alert("Errore rifiuto: " + (e?.message || e));
        btnReject.disabled = false;
      }
    };

    actions.appendChild(btnView);
    actions.appendChild(btnCopy);
    actions.appendChild(btnAccept);
    actions.appendChild(btnPublish);
    actions.appendChild(btnReject);

    card.appendChild(main);
    card.appendChild(actions);
    $("list").appendChild(card);
  }
}

(async function main() {
  const session = await requireAuth({ target: "admin", requireRole: "admin" });
  if (!session) return;

  $("logoutBtn").addEventListener("click", (e) => { e.preventDefault(); logout(); });

  const tok = await auth.currentUser.getIdTokenResult(true);
  $("whoami").textContent = `Loggato: ${session.user.email || session.user.uid} · claims → approved: ${tok?.claims?.approved} · role: ${tok?.claims?.role}`;

  $("btnReload").addEventListener("click", () => load(session));
  $("q").addEventListener("input", () => load(session));

  await load(session);
})();
