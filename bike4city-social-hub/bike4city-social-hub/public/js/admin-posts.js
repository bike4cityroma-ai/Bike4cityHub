import { auth, db } from "./firebase.js";
import { requireAuth } from "./guard.js";
import {
  collection,
  addDoc,
  getDocs,
  query,
  orderBy,
  updateDoc,
  deleteDoc,
  doc,
  serverTimestamp
} from "https://www.gstatic.com/firebasejs/10.12.5/firebase-firestore.js";

/* =========================
   UTILS
========================= */
const $ = (id) => document.getElementById(id);

function escapeHtml(str) {
  if (!str) return "";
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function formatDate(ts) {
  try {
    if (!ts) return "";
    const d = ts.toDate ? ts.toDate() : new Date(ts);
    return d.toLocaleString("it-IT", {
      dateStyle: "medium",
      timeStyle: "short"
    });
  } catch {
    return "";
  }
}

function setStatus(msg) {
  const el = $("formStatus");
  if (el) el.textContent = msg || "";
}

function isPermissionError(e) {
  return e && (e.code === "permission-denied");
}

/* =========================
   AUTH (ADMIN)
========================= */
const authResult = await requireAuth({
  target: "admin",
  requireRole: "admin"
});

if (!authResult) {
  throw new Error("Accesso admin negato");
}

const user = authResult.user;

// ✅ UNICA verifica token / claims
let token;
try {
  token = await user.getIdTokenResult(true);
  console.log("ADMIN CLAIMS:", token.claims);
} catch (e) {
  console.error("Errore token:", e);
  throw e;
}

if (token?.claims?.role !== "admin") {
  setStatus("⛔ Claim role=admin mancante. Fai logout/login.");
  throw new Error("Claim admin mancante");
}

/* =========================
   FIRESTORE
========================= */
const POSTS = collection(db, "board_posts");

/* =========================
   CREATE
========================= */
async function createPost(status) {
  const title = $("title").value.trim();
  const body = $("body").value.trim();

  if (!title || !body) {
    alert("Titolo e testo sono obbligatori");
    return;
  }

  try {
    setStatus("Salvataggio…");

    await addDoc(POSTS, {
      title,
      body,
      status, // "draft" | "published"
      authorUid: user.uid,
      authorName: user.email || "admin",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });

    $("title").value = "";
    $("body").value = "";

    setStatus(status === "published"
      ? "Post pubblicato ✓"
      : "Bozza salvata ✓"
    );

    await refresh();
  } catch (e) {
    console.error("Errore salvataggio:", e);
    setStatus(isPermissionError(e)
      ? "⛔ Permessi insufficienti"
      : "Errore nel salvataggio"
    );
  }
}

/* =========================
   FORM EVENTS (🔥 FIX BOZZE)
========================= */
const postForm = $("postForm");

// Enter → salva bozza
postForm?.addEventListener("submit", (e) => {
  e.preventDefault();
  createPost("draft");
});

$("saveDraftBtn")?.addEventListener("click", (e) => {
  e.preventDefault();
  createPost("draft");
});

$("publishBtn")?.addEventListener("click", (e) => {
  e.preventDefault();
  createPost("published");
});

/* =========================
   LOAD + RENDER
========================= */
async function refresh() {
  try {
    const qy = query(POSTS, orderBy("createdAt", "desc"));
    const snap = await getDocs(qy);

    let posts = snap.docs.map((d) => ({
      id: d.id,
      ...d.data()
    }));

    const filter = ($("filterStatus")?.value || "all").toLowerCase();
    if (filter !== "all") {
      posts = posts.filter(p =>
        String(p.status || "").toLowerCase() === filter
      );
    }

    const q = ($("searchInput")?.value || "").toLowerCase();
    if (q) {
      posts = posts.filter(
        p =>
          (p.title || "").toLowerCase().includes(q) ||
          (p.body || "").toLowerCase().includes(q)
      );
    }

    renderPosts(posts);
    setStatus("");
  } catch (e) {
    console.error("Errore refresh:", e);
    setStatus("⛔ Errore nel caricamento post");
    renderPosts([]);
  }
}

function renderPosts(posts) {
  const list = $("postsList");
  if (!list) return;

  if (!posts.length) {
    list.innerHTML = `<div class="empty">Nessun messaggio trovato.</div>`;
    return;
  }

  list.innerHTML = posts.map(p => `
    <div class="list-item">
      <div class="list-main">
        <div style="display:flex; justify-content:space-between;">
          <strong>${escapeHtml(p.title)}</strong>
          <span class="pill">${p.status === "published" ? "PUBBLICATO" : "BOZZA"}</span>
        </div>
        <div class="muted" style="margin-top:8px;">${escapeHtml(p.body)}</div>
      </div>
    </div>
  `).join("");
}

/* =========================
   INIT
========================= */
await refresh();
