import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.5/firebase-app.js";
import {
  getAuth,
  onAuthStateChanged,
  signInWithEmailAndPassword,
  signOut,
  getIdTokenResult,
} from "https://www.gstatic.com/firebasejs/10.12.5/firebase-auth.js";
import {
  getFirestore,
  doc,
  getDoc,
  collection,
  query,
  where,
  orderBy,
  limit,
  onSnapshot,
  getDocs,
  addDoc,
  updateDoc,
  deleteDoc,
  serverTimestamp,
} from "https://www.gstatic.com/firebasejs/10.12.5/firebase-firestore.js";

/* =======================
   CONFIG FIREBASE
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

// Cloud Function
const APPROVE_URL = `https://us-central1-${firebaseConfig.projectId}.cloudfunctions.net/approveMemberHttp`;

/* =======================
   HELPERS UI (tolleranti)
   (se un elemento non esiste, non esplode)
======================= */
function $(id) { return document.getElementById(id); }

function showOnly(idsToShow = []) {
  const ids = ["view-loading", "view-login", "view-pending", "view-member"];
  ids.forEach(id => {
    const el = $(id);
    if (!el) return;
    el.classList.toggle("hidden", !idsToShow.includes(id));
  });
}

function setText(id, value) {
  const el = $(id);
  if (el) el.textContent = value ?? "";
}

function setMsg(id, value) {
  const el = $(id);
  if (el) el.textContent = value ?? "";
}

function escapeHtml(s) {
  return (s || "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

/* =======================
   LISTENER CLEANUP
======================= */
let unsubBoard = null;
let unsubAdminPosts = null;

function cleanupRealtime() {
  if (unsubBoard) { unsubBoard(); unsubBoard = null; }
  if (unsubAdminPosts) { unsubAdminPosts(); unsubAdminPosts = null; }
}

/* =======================
   LOGIN / LOGOUT
======================= */
function wireAuthButtons() {
  const btnLogin = $("btnLogin");
  if (btnLogin) {
    btnLogin.addEventListener("click", async (e) => {
      e.preventDefault(); // importantissimo se sei dentro un <form>
      const email = ($("email")?.value || "").trim();
      const password = $("password")?.value || "";
      setMsg("loginMsg", "Accesso in corso…");

      try {
        await signInWithEmailAndPassword(auth, email, password);
        // NON facciamo redirect qui: ci pensa onAuthStateChanged
        setMsg("loginMsg", "");
      } catch (err) {
        setMsg("loginMsg", "Errore login: " + (err?.message || "unknown"));
      }
    });
  }

  const out1 = $("btnLogout1");
  if (out1) out1.addEventListener("click", (e) => { e.preventDefault(); signOut(auth); });

  const out2 = $("btnLogout2");
  if (out2) out2.addEventListener("click", (e) => { e.preventDefault(); signOut(auth); });
}

/* =======================
   BACHECA (solo published)
======================= */
function renderBoardPost(p) {
  const title = escapeHtml(p.title || "Messaggio");
  const body = escapeHtml(p.body || p.content || "").replaceAll("\n", "<br>");
  const when = p.createdAt?.toDate?.()
    ? p.createdAt.toDate().toLocaleString("it-IT")
    : "";

  return `
    <div class="card">
      <h4>${title}</h4>
      <p>${body}</p>
      <p class="muted">${when}</p>
    </div>
  `;
}

function loadBoard() {
  const boardEl = $("board");
  if (!boardEl) return;

  boardEl.innerHTML = "<p class='muted'>Caricamento bacheca…</p>";

  const q = query(collection(db, "board_posts"), orderBy("createdAt", "desc"), limit(50));

  if (unsubBoard) unsubBoard();
  unsubBoard = onSnapshot(q, (snap) => {
    const published = [];
    snap.forEach(d => {
      const p = d.data();
      if ((p.status || "published") === "published") published.push(p);
    });

    boardEl.innerHTML = published.length
      ? published.map(renderBoardPost).join("")
      : "<p class='muted'>Nessun messaggio.</p>";
  }, (err) => {
    boardEl.innerHTML = `<p class='muted'>Errore bacheca: ${escapeHtml(err?.message || "unknown")}</p>`;
  });
}

/* =======================
   ADMIN - APPROVA
======================= */
async function approveMember(uid) {
  const msgEl = $(`msg_${uid}`);
  if (msgEl) msgEl.textContent = "Approvazione in corso…";

  try {
    const token = await auth.currentUser.getIdToken(true);
    const res = await fetch(APPROVE_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ uid }),
    });

    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data?.error || `HTTP ${res.status}`);

    if (msgEl) msgEl.textContent = "✅ Approvato";
  } catch (e) {
    if (msgEl) msgEl.textContent = "❌ " + (e?.message || "Errore");
  }
}

async function loadPendingRequests() {
  const list = $("pendingList");
  if (!list) return;

  list.innerHTML = "<p class='muted'>Caricamento richieste…</p>";
  try {
    const qy = query(collection(db, "users"), where("status", "==", "pending"));
    const snap = await getDocs(qy);

    if (snap.empty) {
      list.innerHTML = "<p class='muted'>Nessuna richiesta in attesa.</p>";
      return;
    }

    list.innerHTML = "";
    snap.forEach((d) => {
      const u = d.data();
      const wrap = document.createElement("div");
      wrap.className = "card";
      wrap.innerHTML = `
        <p><strong>${escapeHtml(u.displayName || "(senza nome)")}</strong> — ${escapeHtml(u.email || "")}</p>
        <p class="muted">UID: ${escapeHtml(d.id)}</p>
        <button type="button" data-uid="${escapeHtml(d.id)}">Approva</button>
        <p class="muted" id="msg_${escapeHtml(d.id)}"></p>
      `;
      wrap.querySelector("button").onclick = () => approveMember(d.id);
      list.appendChild(wrap);
    });
  } catch (e) {
    list.innerHTML = `<p class='muted'>Errore richieste: ${escapeHtml(e?.message || "unknown")}</p>`;
  }
}

/* =======================
   ADMIN - POST CRUD
======================= */
async function createPostFromForm() {
  const titleEl = $("postTitle");
  const bodyEl = $("postBody");
  const statusEl = $("postStatus");
  const msgEl = $("postMsg");

  const title = (titleEl?.value || "").trim();
  const body = (bodyEl?.value || "").trim();
  const status = statusEl?.value || "draft";

  if (msgEl) msgEl.textContent = "";
  if (!title || !body) {
    if (msgEl) msgEl.textContent = "Titolo e testo sono obbligatori.";
    return;
  }

  try {
    if (msgEl) msgEl.textContent = "Salvataggio…";
    await addDoc(collection(db, "board_posts"), {
      title,
      body,
      status,
      authorUid: auth.currentUser.uid,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
    if (titleEl) titleEl.value = "";
    if (bodyEl) bodyEl.value = "";
    if (statusEl) statusEl.value = "draft";
    if (msgEl) msgEl.textContent = "✅ Salvato";
  } catch (e) {
    if (msgEl) msgEl.textContent = "❌ " + (e?.message || "Errore");
  }
}

async function setPostStatus(postId, nextStatus) {
  await updateDoc(doc(db, "board_posts", postId), {
    status: nextStatus,
    updatedAt: serverTimestamp(),
  });
}

async function removePost(postId) {
  if (!confirm("Cancellare definitivamente questo messaggio?")) return;
  await deleteDoc(doc(db, "board_posts", postId));
}

function loadAdminPosts() {
  const listEl = $("adminPostsList");
  if (!listEl) return;

  listEl.innerHTML = "<p class='muted'>Caricamento messaggi…</p>";
  const qy = query(collection(db, "board_posts"), orderBy("createdAt", "desc"), limit(50));

  if (unsubAdminPosts) unsubAdminPosts();
  unsubAdminPosts = onSnapshot(qy, (snap) => {
    if (snap.empty) {
      listEl.innerHTML = "<p class='muted'>Nessun messaggio.</p>";
      return;
    }

    const rows = [];
    snap.forEach((d) => {
      const p = d.data();
      const id = d.id;
      const title = escapeHtml(p.title || "(senza titolo)");
      const status = escapeHtml(p.status || "draft");
      const when = p.createdAt?.toDate?.() ? p.createdAt.toDate().toLocaleString("it-IT") : "";

      rows.push(`
        <div class="card">
          <p><strong>${title}</strong></p>
          <p class="muted">Status: <strong>${status}</strong> — ${escapeHtml(when)}</p>
          <div class="row">
            ${status === "draft"
              ? `<button type="button" data-act="publish" data-id="${id}">Pubblica</button>`
              : `<button type="button" data-act="draft" data-id="${id}">Metti in bozza</button>`
            }
            <button type="button" data-act="delete" data-id="${id}">Cancella</button>
          </div>
        </div>
      `);
    });

    listEl.innerHTML = rows.join("");

    listEl.querySelectorAll("button[data-act]").forEach((btn) => {
      const act = btn.getAttribute("data-act");
      const id = btn.getAttribute("data-id");
      btn.onclick = async (e) => {
        e.preventDefault();
        try {
          if (act === "publish") await setPostStatus(id, "published");
          if (act === "draft") await setPostStatus(id, "draft");
          if (act === "delete") await removePost(id);
        } catch (err) {
          alert("Errore: " + (err?.message || "unknown"));
        }
      };
    });
  }, (err) => {
    listEl.innerHTML = `<p class='muted'>Errore lista admin: ${escapeHtml(err?.message || "unknown")}</p>`;
  });
}

/* =======================
   AUTH STATE (SENZA BOOTED)
   - reagisce SEMPRE a login/logout
======================= */
let lastUid = null;

onAuthStateChanged(auth, async (user) => {
  // evita lavoro doppio sullo stesso utente in “micro-eventi”
  const uid = user?.uid || null;
  if (uid === lastUid && uid !== null) return;
  lastUid = uid;

  // ogni cambio stato: stacca listener vecchi
  cleanupRealtime();

  if (!user) {
    // stato “logged out”
    showOnly(["view-login"]);
    setMsg("loginMsg", "");
    const adminPanel = $("adminPanel");
    if (adminPanel) adminPanel.classList.add("hidden");
    return;
  }

  showOnly(["view-loading"]);

  try {
    const tokenRes = await getIdTokenResult(user, true);
    const claims = tokenRes?.claims || {};
    const isAdmin = claims.role === "admin";
    const isApproved = claims.approved === true || isAdmin;

    // profilo fireStore (best effort)
    let profile = null;
    try {
      const snap = await getDoc(doc(db, "users", user.uid));
      if (snap.exists()) profile = snap.data();
    } catch { /* ignora */ }

    if (!isApproved) {
      showOnly(["view-pending"]);
      return;
    }

    // utente ok
    setText("uName", profile?.displayName || user.displayName || "");
    setText("uEmail", user.email || profile?.email || "");
    setText("uStatus", profile?.status || (isAdmin ? "admin" : "active"));
    setText("uCard", profile?.membershipNumber || profile?.cardNumber || "-");
    setText("uValidUntil", profile?.membershipValidUntil || profile?.validUntil || "-");

    showOnly(["view-member"]);
    loadBoard();

    // admin
    const adminPanel = $("adminPanel");
    if (adminPanel) {
      if (isAdmin) {
        adminPanel.classList.remove("hidden");
        const btn = $("btnSavePost");
        if (btn) btn.onclick = (e) => { e.preventDefault(); createPostFromForm(); };
        loadPendingRequests();
        loadAdminPosts();
      } else {
        adminPanel.classList.add("hidden");
      }
    }
  } catch (e) {
    console.error("Auth render error:", e);
    await signOut(auth);
    showOnly(["view-login"]);
    setMsg("loginMsg", "Errore sessione, rifai login.");
  }
});

/* =======================
   INIT UI
======================= */
document.addEventListener("DOMContentLoaded", () => {
  wireAuthButtons();
});
