"use strict";

const { onRequest, onCall, HttpsError } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");

admin.initializeApp();

// Secret: firebase functions:secrets:set ORS_API_KEY
const ORS_API_KEY = defineSecret("ORS_API_KEY");

/**
 * Helpers membership
 */
function normalizeIsoDateString(s) {
  if (!s) return "";
  const v = String(s).trim();
  // already ISO
  if (/^\d{4}-\d{2}-\d{2}$/.test(v)) return v;
  // dd-mm-yyyy -> yyyy-mm-dd
  const m = v.match(/^(\d{2})-(\d{2})-(\d{4})$/);
  if (m) return `${m[3]}-${m[2]}-${m[1]}`;
  return v;
}

function isoDateEndOfDayTimestamp(isoDate) {
  const v = normalizeIsoDateString(isoDate);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(v)) return null;
  // end of day UTC (simple & consistent)
  const d = new Date(`${v}T23:59:59.000Z`);
  if (Number.isNaN(d.getTime())) return null;
  return admin.firestore.Timestamp.fromDate(d);
}


/**
 * Progressive membership number (transactional counter)
 */
async function nextCounter(key) {
  const ref = admin.firestore().doc(`counters/${key}`);
  return await admin.firestore().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const current = snap.exists ? (snap.data().value || 0) : 0;
    const next = current + 1;
    tx.set(
      ref,
      { value: next, updatedAt: admin.firestore.FieldValue.serverTimestamp() },
      { merge: true }
    );
    return next;
  });
}

function formatMembershipNumber(year, n) {
  const num = String(n).padStart(6, "0");
  return `B4C-${year}-${num}`;
}

/**
 * Allowlist: solo directions cycling in geojson
 */
function isAllowedEndpoint(endpoint) {
  return /^\/v2\/directions\/(cycling-regular|cycling-road|cycling-mountain)\/geojson$/.test(
    endpoint
  );
}

/**
 * Verifica Firebase ID token (Authorization: Bearer <token>)
 */
async function verifyToken(req) {
  const auth = req.get("authorization") || "";
  const m = auth.match(/^Bearer (.+)$/);
  if (!m) return null;
  try {
    return await admin.auth().verifyIdToken(m[1]);
  } catch (e) {
    return null;
  }
}

/**
 * =========================
 * ORS PROXY (HTTP)
 * =========================
 */
exports.orsProxy = onRequest(
  {
    region: "us-central1",
    secrets: [ORS_API_KEY],
  },
  async (req, res) => {
    // CORS
    res.set("Access-Control-Allow-Origin", "*");
    res.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set("Access-Control-Allow-Methods", "POST, OPTIONS");
    if (req.method === "OPTIONS") return res.status(204).send("");

    if (req.method !== "POST") {
      return res.status(405).send("Method Not Allowed");
    }

    // AUTH
    const decoded = await verifyToken(req);
    if (!decoded) {
      return res.status(401).send("Unauthorized");
    }

    // CLAIMS
    const role = decoded.role || null;
    const approved = decoded.approved === true;

    const isAdmin = role === "admin";
    const isMember = role === "member" && approved;

    if (!isAdmin && !isMember) {
      return res.status(403).send("Forbidden");
    }

    // BODY
    const body = req.body || {};
    let endpoint = body.endpoint;
    let payload = body.payload;

    // COMPAT LEGACY (admin vecchio): profile+coordinates+mode...
    if (!endpoint && body.profile && body.coordinates) {
      endpoint = `/v2/directions/${body.profile}/geojson`;

      const options = {};
      if (Array.isArray(body.avoid) && body.avoid.length) {
        options.avoid_features = body.avoid;
      }

      if (body.mode === "roundtrip") {
        options.round_trip = {
          length: Math.max(1000, Math.round(body.lengthMeters || 0)),
          seed: Math.max(1, Math.round(body.seed || 1)),
        };
      }

      payload = {
        coordinates: body.coordinates,
        elevation: true,
        ...(Object.keys(options).length ? { options } : {}),
      };
    }

    if (!endpoint || !payload) {
      return res.status(400).send("Missing endpoint/payload");
    }

    if (!isAllowedEndpoint(endpoint)) {
      return res.status(400).send("Endpoint not allowed");
    }

    const apiKey = ORS_API_KEY.value();
    if (!apiKey) {
      return res.status(500).send("ORS_API_KEY not configured");
    }

    const url = `https://api.openrouteservice.org${endpoint}`;

    try {
      const r = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: apiKey,
        },
        body: JSON.stringify(payload),
      });

      const text = await r.text();
      res.status(r.status).send(text);
    } catch (err) {
      logger.error("ORS proxy error", err);
      res.status(502).send("ORS fetch failed");
    }
  }
);

/**
 * =========================
 * APPROVE MEMBER (HTTP)
 * =========================
 */
exports.approveMemberHttp = onRequest(
  { region: "us-central1" },
  async (req, res) => {
    // CORS
    res.set("Access-Control-Allow-Origin", "*");
    res.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set("Access-Control-Allow-Methods", "POST, OPTIONS");
    if (req.method === "OPTIONS") return res.status(204).send("");

    if (req.method !== "POST") {
      return res.status(405).json({ error: "Method Not Allowed" });
    }

    // Auth
    const decoded = await verifyToken(req);
    if (!decoded || decoded.role !== "admin") {
      return res.status(403).json({ error: "Admin only" });
    }

    const uid = req.body?.uid;
    if (!uid) {
      return res.status(400).json({ error: "Missing uid" });
    }

    try {
      const year = new Date().getFullYear();
      const userRef = admin.firestore().doc(`users/${uid}`);
      const snap = await userRef.get();
      const data = snap.exists ? snap.data() : {};

      let membershipNumber =
        data?.membership?.number ||
        data?.membershipNumber;

      // Progressive number if missing
      if (!membershipNumber) {
        const seq = await nextCounter(`membership_${year}`);
        membershipNumber = formatMembershipNumber(year, seq);
      }

      const membershipValidUntil =
        data?.membership?.validUntil ||
        data?.membershipValidUntil ||
        `${year}-12-31`;

      const membershipValidUntilIso = normalizeIsoDateString(membershipValidUntil);
      const membershipValidUntilTs = isoDateEndOfDayTimestamp(membershipValidUntilIso);

      // Claims
      await admin.auth().setCustomUserClaims(uid, {
        approved: true,
        role: "member",
      });

      // Firestore update (MERGE!)
      await userRef.set(
        {
          status: "active",
          role: "member",
          approvedAt: admin.firestore.FieldValue.serverTimestamp(),

          // legacy
          membershipNumber,
          membershipValidUntil: membershipValidUntilIso,
          ...(membershipValidUntilTs ? { membershipValidUntilTs } : {}),

          // nuovo oggetto membership
          membership: {
            number: membershipNumber,
            issuedAt:
              data?.membership?.issuedAt ||
              admin.firestore.FieldValue.serverTimestamp(),
            validFrom: `${year}-01-01`,
            validUntil: membershipValidUntilIso,
            ...(membershipValidUntilTs ? { validUntilTs: membershipValidUntilTs } : {}),
            status: "active",
            paymentStatus: "unpaid",
            tier: "standard",
          },
        },
        { merge: true }
      );

      res.json({ ok: true, uid, membershipNumber, membershipValidUntil: membershipValidUntilIso });
    } catch (e) {
      logger.error("approveMemberHttp error", e);
      res.status(500).json({ error: "Internal error" });
    }
  }
);

/**
 * =========================
 * APPROVE MEMBER (CALLABLE)
 * =========================
 */
exports.approveMember = onCall(async (request) => {
  const decoded = request.auth?.token;
  if (!decoded) throw new HttpsError("unauthenticated", "Devi essere autenticato.");
  if (decoded.role !== "admin") throw new HttpsError("permission-denied", "Non sei admin.");

  const uid = request.data?.uid;
  if (!uid || typeof uid !== "string") {
    throw new HttpsError("invalid-argument", "Missing uid");
  }

  try {
    const year = new Date().getFullYear();
    const userRef = admin.firestore().doc(`users/${uid}`);
    const snap = await userRef.get();
    const data = snap.exists ? snap.data() : {};

    let membershipNumber =
        data?.membership?.number ||
        data?.membershipNumber;

      // Progressive number if missing
      if (!membershipNumber) {
        const seq = await nextCounter(`membership_${year}`);
        membershipNumber = formatMembershipNumber(year, seq);
      }

    const membershipValidUntil =
      data?.membership?.validUntil ||
      data?.membershipValidUntil ||
      `${year}-12-31`;

    const membershipValidUntilIso = normalizeIsoDateString(membershipValidUntil);
    const membershipValidUntilTs = isoDateEndOfDayTimestamp(membershipValidUntilIso);

    await admin.auth().setCustomUserClaims(uid, {
      approved: true,
      role: "member",
    });

    await userRef.set(
      {
        status: "active",
        role: "member",
        approvedAt: admin.firestore.FieldValue.serverTimestamp(),

        membershipNumber,
        membershipValidUntil: membershipValidUntilIso,
        ...(membershipValidUntilTs ? { membershipValidUntilTs } : {}),

        membership: {
          number: membershipNumber,
          issuedAt:
            data?.membership?.issuedAt ||
            admin.firestore.FieldValue.serverTimestamp(),
          validFrom: `${year}-01-01`,
          validUntil: membershipValidUntilIso,
          ...(membershipValidUntilTs ? { validUntilTs: membershipValidUntilTs } : {}),
          status: "active",
          paymentStatus: "unpaid",
          tier: "standard",
        },
      },
      { merge: true }
    );

    return { ok: true, uid, membershipNumber, membershipValidUntil: membershipValidUntilIso };
  } catch (e) {
    logger.error("approveMember callable error", e);
    throw new HttpsError("internal", "Errore interno durante l'approvazione");
  }
});

/**
 * =========================
 * REGISTER MEMBER (HTTP)
 * chiamata dal pubblico (WP snippet / register.php)
 * =========================
 */
exports.registerMember = onRequest(
  { region: "us-central1" },
  async (req, res) => {
    // CORS (form su dominio diverso)
    res.set("Access-Control-Allow-Origin", "*");
    res.set("Access-Control-Allow-Headers", "Content-Type");
    res.set("Access-Control-Allow-Methods", "POST, OPTIONS");
    if (req.method === "OPTIONS") return res.status(204).send("");

    if (req.method !== "POST") {
      return res.status(405).json({ error: "Method Not Allowed" });
    }

    try {
      const b = req.body || {};

      // Campi dal form pubblico
      const email = String(b.email || "").trim().toLowerCase();
      const password = String(b.password || "");
      const firstName = String(b.firstName || "").trim();
      const lastName = String(b.lastName || "").trim();
      const displayName = String(
        b.displayName || `${firstName} ${lastName}`.trim()
      ).trim();

      const birthDate = String(b.birthDate || "").trim(); // YYYY-MM-DD
      const fiscalCode = String(b.fiscalCode || "").trim().toUpperCase();

      const phone = String(b.phone || "").trim();
      const address = String(b.address || "").trim();
      const city = String(b.city || "").trim();
      const zip = String(b.zip || "").trim();

      const privacyAccepted = b.privacyAccepted === true;
      const newsletterOptIn = b.newsletterOptIn === true;

      // Validazioni minime
      if (!email || !email.includes("@")) {
        return res.status(400).json({ error: "Email non valida" });
      }
      if (!password || password.length < 6) {
        return res
          .status(400)
          .json({ error: "Password troppo corta (min 6)" });
      }
      if (!firstName || !lastName) {
        return res
          .status(400)
          .json({ error: "Nome e cognome sono obbligatori" });
      }
      if (!privacyAccepted) {
        return res
          .status(400)
          .json({ error: "Devi accettare la privacy" });
      }

      // 1) Crea utente Auth (o recupera se email già esiste)
      let userRecord;
      try {
        userRecord = await admin.auth().createUser({
          email,
          password,
          displayName,
        });
      } catch (e) {
        // email già esistente -> recupero utente
        if (
          e &&
          (e.code === "auth/email-already-exists" ||
            String(e.message || "").includes("EMAIL_EXISTS"))
        ) {
          userRecord = await admin.auth().getUserByEmail(email);
        } else {
          logger.error("registerMember createUser error", e);
          return res.status(400).json({
            error:
              "Impossibile creare account (email già usata o dati non validi)",
          });
        }
      }

      const uid = userRecord.uid;

      // 2) Scrive profilo su Firestore users/{uid} (status pending)
      // (mai salvare la password!)
      const userRef = admin.firestore().doc(`users/${uid}`);
      await userRef.set(
        {
          email,
          displayName,
          firstName,
          lastName,
          birthDate,
          fiscalCode,
          phone,
          address,
          city,
          zip,
          newsletterOptIn,
          privacyAccepted,

          role: "member",
          status: "pending",

          createdAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          source: "wp_snippet",
        },
        { merge: true }
      );

      return res.status(200).json({ ok: true, uid });
    } catch (e) {
      logger.error("registerMember error", e);
      return res.status(500).json({ error: "Internal error" });
    }
  }
);

/**
 * =========================
 * RECORD PAYMENT + RENEW (HTTP)
 * - Admin only
 * - Scrive users/{uid}/payments
 * - Aggiorna validità al 31/12
 * =========================
 */
exports.recordPaymentAndRenewHttp = onRequest(
  { region: "us-central1" },
  async (req, res) => {
    try {
      // CORS
      res.set("Access-Control-Allow-Origin", "*");
      res.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
      res.set("Access-Control-Allow-Methods", "POST, OPTIONS");
      if (req.method === "OPTIONS") return res.status(204).send("");

      if (req.method !== "POST") {
        return res.status(405).json({ error: "Method Not Allowed" });
      }

      // Auth
      const decoded = await verifyToken(req);
      if (!decoded || decoded.role !== "admin") {
        return res.status(403).json({ error: "Admin only" });
      }

      const { uid, amount, method, note, dateIso } = req.body || {};
      if (!uid) return res.status(400).json({ error: "Missing uid" });

      const amt = Number(amount || 0);
      if (!Number.isFinite(amt) || amt <= 0) {
        return res.status(400).json({ error: "Invalid amount" });
      }

      const payMethod = (method || "cash").toString().slice(0, 40);
      const payNote = (note || "").toString().slice(0, 300);

      // date pagamento (default: now)
      const now = new Date();
      let payDate = now;
      if (dateIso && typeof dateIso === "string") {
        const d = new Date(dateIso);
        if (!Number.isNaN(d.getTime())) payDate = d;
      }

      const userRef = admin.firestore().doc(`users/${uid}`);
      const userSnap = await userRef.get();
      if (!userSnap.exists) return res.status(404).json({ error: "User not found" });

      const userData = userSnap.data() || {};
      const status = userData.status || "pending";


      // Ensure membership number exists (progressive) even for legacy/edge cases
      const year = new Date().getFullYear();
      let membershipNumber = userData.membershipNumber || userData?.membership?.number;
      if (!membershipNumber) {
        const seq = await nextCounter(`membership_${year}`);
        membershipNumber = formatMembershipNumber(year, seq);
      }

      // --- decide se è attivo oggi ---
      const today = new Date();
      today.setHours(0, 0, 0, 0);

      let currentValidDate = null;
      const validTs = userData.membershipValidUntilTs;
      const validStr = userData.membershipValidUntil;

      if (validTs && typeof validTs.toDate === "function") {
        currentValidDate = validTs.toDate();
      } else if (typeof validStr === "string" && /^\d{4}-\d{2}-\d{2}$/.test(validStr)) {
        // stringa -> fine giornata UTC coerente
        currentValidDate = new Date(validStr + "T23:59:59.000Z");
      }

      // PATCH: se lo status NON è active, non consideriamo il socio attivo
      const isCurrentlyActive =
        status === "active" &&
        currentValidDate &&
        currentValidDate.getTime() >= today.getTime();

      // regola 31/12:
      // - se già attivo -> 31/12 anno prossimo
      // - se scaduto/pending -> 31/12 anno corrente
      const baseYear = today.getFullYear();
      const targetYear = isCurrentlyActive ? baseYear + 1 : baseYear;

      const newValidIso = `${targetYear}-12-31`;
      const newValidTs = isoDateEndOfDayTimestamp(newValidIso);

      // payment record
      const payRef = userRef.collection("payments").doc();
      const paymentRecord = {
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        date: admin.firestore.Timestamp.fromDate(payDate),
        amount: amt,
        method: payMethod,
        note: payNote,
        validUntilIso: newValidIso,
      };

      // update user (legacy + opzionale membership)
      const updates = {
        status: "active",
        membershipNumber,
        membershipValidUntil: newValidIso,
        ...(newValidTs ? { membershipValidUntilTs: newValidTs } : {}),
        lastPaymentAt: admin.firestore.FieldValue.serverTimestamp(),
        lastPaymentAmount: amt,
        lastPaymentMethod: payMethod,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      };

      // se era pending e non aveva approvedAt, lo settiamo (coerenza)
      if (!userData.approvedAt) {
        updates.approvedAt = admin.firestore.FieldValue.serverTimestamp();
      }

      // se esiste il blocco membership, lo aggiorniamo coerentemente
      if (userData.membership && typeof userData.membership === "object") {
        updates.membership = {
          ...userData.membership,
          number: membershipNumber,
          validUntil: newValidIso,
          ...(newValidTs ? { validUntilTs: newValidTs } : {}),
          status: "active",
          paymentStatus: "paid",
        };
      }

      await admin.firestore().runTransaction(async (tx) => {
        tx.set(payRef, paymentRecord, { merge: false });
        tx.set(userRef, updates, { merge: true });
      });

      return res.json({
        ok: true,
        uid,
        newValidIso,
        targetYear,
        paymentId: payRef.id,
      });
    } catch (e) {
      logger.error("recordPaymentAndRenewHttp error", e);
      return res.status(500).json({ error: e?.message || String(e) });
    }
  }
  
);
/**
 * =========================
 * SET USER ROLE (HTTP)
 * - Superadmin only
 * - Aggiorna custom claims + users/{uid}
 * =========================
 */
exports.setUserRoleHttp = onRequest(
  { region: "us-central1" },
  async (req, res) => {
    // CORS
    res.set("Access-Control-Allow-Origin", "*");
    res.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set("Access-Control-Allow-Methods", "POST, OPTIONS");
    if (req.method === "OPTIONS") return res.status(204).send("");

    if (req.method !== "POST") {
      return res.status(405).json({ error: "Method Not Allowed" });
    }

    // Auth
    const decoded = await verifyToken(req);
    if (!decoded || decoded.role !== "superadmin") {
      return res.status(403).json({ error: "Superadmin only" });
    }

    const { uid, role } = req.body || {};
    if (!uid || !role) {
      return res.status(400).json({ error: "Missing uid or role" });
    }

    const allowedRoles = ["member", "admin", "superadmin"];
    if (!allowedRoles.includes(role)) {
      return res.status(400).json({ error: "Invalid role" });
    }

    try {
      // Claims (quelli che contano davvero)
      await admin.auth().setCustomUserClaims(uid, {
        approved: true,
        role,
      });

      // Firestore (coerenza UI)
      const userRef = admin.firestore().doc(`users/${uid}`);
      await userRef.set(
        {
          role,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );

      return res.json({ ok: true, uid, role });
    } catch (e) {
      logger.error("setUserRoleHttp error", e);
      return res.status(500).json({ error: "Internal error" });
    }
  }
);
