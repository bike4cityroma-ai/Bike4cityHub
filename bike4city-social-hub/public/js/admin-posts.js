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
  return e && e.code === "permission-denied";
}

/* =========================
   AUTH (ADMIN + SUPERADMIN)
========================= */
const authResult = await requireAuth({
  target: "admin",
  requireRole: "admin" // ✅ accetta admin + superadmin
});

if (!authResult) {
  throw new Error("Accesso staff negato");
}

const user = authResult.user;

const token = await user.getIdTokenResult(true);
console.log("STAFF CLAIMS:", token.claims);

const role = String(token?.claims?.role || "").toLowerCase();
const isStaff = ["admin", "superadmin"].includes(role);

if (!isStaff) {
  setStatus("⛔ Claim staff mancante. Fai logout/login.");
  throw new Error("Claim staff mancante");
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
      status, // draft | published
      authorUid: user.uid,
      authorName: user.email || "staff",
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
    setStatus(
      isPermissionError(e)
        ? "⛔ Permessi insufficienti"
        : "Errore nel salvataggio"
    );
  }
}

/* =========================
   FORM EVENTS
========================= */
$("postForm")?.addEventListener("submit", (e) => {
  e.preventDefault();
  createPost("draft");
});

$("saveDraftBtn")?.addEventListener("click", () => {
  createPost("draft");
});

$("publishBtn")?.addEventListener("click", () => {
  createPost("published");
});

/* =========================
   LOAD + RENDER
========================= */
async function refresh() {
  const qy = query(POSTS, orderBy("createdAt", "desc"));
  const snap = await getDocs(qy);

  let posts = snap.docs.map(d => ({
    id: d.id,
    ...d.data()
  }));

  const filter = ($("filterStatus")?.value || "all").toLowerCase();
  if (filter !== "all") {
    posts = posts.filter(p => p.status === filter);
  }

  const q = ($("searchInput")?.value || "").toLowerCase();
  if (q) {
    posts = posts.filter(p =>
      (p.title || "").toLowerCase().includes(q) ||
      (p.body || "").toLowerCase().includes(q)
    );
  }

  renderPosts(posts);
}

async function setPostStatus(postId, newStatus) {
  await updateDoc(doc(db, "board_posts", postId), {
    status: newStatus,
    updatedAt: serverTimestamp()
  });
  await refresh();
}

async function deletePost(postId) {
  if (!confirm("Vuoi davvero eliminare questo messaggio?")) return;
  await deleteDoc(doc(db, "board_posts", postId));
  await refresh();
}

function renderPosts(posts) {
  const list = $("postsList");
  if (!list) return;

  list.innerHTML = posts.map(p => {
    const isPublished = p.status === "published";
    return `
      <div class="list-item">
        <strong>${escapeHtml(p.title)}</strong>
        <span class="pill">${isPublished ? "PUBBLICATO" : "BOZZA"}</span>
        <p class="muted">${escapeHtml(p.body)}</p>

        <div class="actions">
          <button data-toggle="${p.id}" data-to="${isPublished ? "draft" : "published"}">
            ${isPublished ? "Metti in bozza" : "Pubblica"}
          </button>
          <button data-delete="${p.id}" class="danger">Elimina</button>
        </div>
      </div>
    `;
  }).join("");

  list.querySelectorAll("[data-toggle]").forEach(btn => {
    btn.onclick = () =>
      setPostStatus(btn.dataset.toggle, btn.dataset.to);
  });

  list.querySelectorAll("[data-delete]").forEach(btn => {
    btn.onclick = () =>
      deletePost(btn.dataset.delete);
  });
}

/* =========================
   INIT
========================= */
await refresh();
