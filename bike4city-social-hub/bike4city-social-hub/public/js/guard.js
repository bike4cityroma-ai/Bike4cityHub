// public/js/guard.js
import { auth, db } from "/js/firebase.js";
import { onAuthStateChanged } from "https://www.gstatic.com/firebasejs/10.12.5/firebase-auth.js";
import { doc, getDoc, setDoc, serverTimestamp } from "https://www.gstatic.com/firebasejs/10.12.5/firebase-firestore.js";

/**
 * requireAuth({ target, requireRole, onDeny })
 * - target: "soci" | "admin" | "any"
 * - requireRole: "member" | "admin" | null
 *
 * RITORNA: { user, profile }
 */
export async function requireAuth(opts) {
  opts = opts || {};
  const target = opts.target || "any";
  const requireRoleRaw = opts.requireRole || null;
  const onDeny = typeof opts.onDeny === "function" ? opts.onDeny : () => {};

  // Normalizza: "socio" == "member"
  const normalizeRole = (r) => {
    const role = String(r || "").toLowerCase();
    if (role === "socio") return "member";
    return role;
  };

  const requireRole = normalizeRole(requireRoleRaw);

  // 1) Aspetta Firebase Auth
  const user = await new Promise((resolve) => {
    const unsub = onAuthStateChanged(auth, (u) => {
      unsub();
      resolve(u || null);
    });
  });

  if (!user) {
    onDeny("not_logged_in", "");
    location.href = "/login.html";
    return null;
  }

  // 🔄 Forza refresh del token per avere i custom claims aggiornati (approved/role)
  // Serve quando i claims vengono appena settati (es. dopo approveMember).
  try {
    await user.getIdToken(true);
  } catch (e) {
    console.warn("Token refresh failed:", e);
  }


  // 2) Leggi profilo utente (users/{uid})
  const ref = doc(db, "users", user.uid);
  let snap;
  try {
    snap = await getDoc(ref);
  } catch (e) {
    onDeny("profile_read_failed", e?.message || "unknown");
    location.href = "/login.html?reason=profile_read_failed";
    return null;
  }

  // 3) Se profilo non esiste: lo creiamo come MEMBER pending
  if (!snap.exists()) {
    try {
      await setDoc(
        ref,
        {
          email: user.email || "",
          displayName: user.displayName || "",
          role: "member",          // ✅ allineato al Social Hub
          status: "pending",
          active: true,
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        },
        { merge: true }
      );
    } catch (e2) {
      onDeny("profile_create_failed", e2?.message || "unknown");
      location.href = "/login.html?reason=profile_create_failed";
      return null;
    }

    location.href = "/pending.html";
    return null;
  }

  const profile = snap.data() || {};
  const role = normalizeRole(profile.role || "");
  const status = String(profile.status || "");

  // 4) Pending → pagina attesa
  if (status === "pending") {
    onDeny("pending", "");
    location.href = "/pending.html";
    return null;
  }

  // 5) Role mismatch → porta nella sezione giusta
  if (requireRole && role !== requireRole) {
    onDeny("role_mismatch", `expected=${requireRole} got=${role}`);

    if (role === "admin") location.href = "/admin/index.html";
    else location.href = "/soci/index.html";
    return null;
  }

  // 6) Target (opzionale)
  if (target === "admin" && role !== "admin") {
    onDeny("not_admin", "");
    location.href = "/soci/index.html";
    return null;
  }

  // OK
  return { user, profile: { ...profile, role } };
}
