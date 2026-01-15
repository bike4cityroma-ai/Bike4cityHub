"use strict";

const { onRequest, onCall, HttpsError } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");

admin.initializeApp();

// Secret: firebase functions:secrets:set ORS_API_KEY
const ORS_API_KEY = defineSecret("ORS_API_KEY");

/**
 * Allowlist: solo directions cycling in geojson
 */
function isAllowedEndpoint(endpoint) {
  return /^\/v2\/directions\/(cycling-regular|cycling-road|cycling-mountain)\/geojson$/.test(endpoint);
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
 * Utility membership
 */
function currentYear() {
  return new Date().getFullYear();
}

function makeMembershipNumber(uid, year) {
  return `B4C-${year}-${String(uid).slice(0, 6).toUpperCase()}`;
}

function endOfYear(year) {
  return `${year}-12-31`;
}

function startOfYear(year) {
  return `${year}-01-01`;
}

/**
 * =========================
 * ORS PROXY (ok già)
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
    if (req.method === "OPTIONS") {
      res.status(204).send("");
      return;
    }

    if (req.method !== "POST") {
      res.status(405).send("Method Not Allowed");
      return;
    }

    // AUTH
    const decoded = await verifyToken(req);
    if (!decoded) {
      res.status(401).send("Unauthorized");
      return;
    }

    // CLAIMS
    const role = decoded.role || null;
    const approved = decoded.approved === true;

    const isAdmin = role === "admin";
    const isMember = role === "member" && approved;

    if (!isAdmin && !isMember) {
      res.status(403).send("Forbidden");
      return;
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
      res.status(400).send("Missing endpoint/payload");
      return;
    }

    if (!isAllowedEndpoint(endpoint)) {
      res.status(400).send("Endpoint not allowed");
      return;
    }

    const apiKey = ORS_API_KEY.value();
    if (!apiKey) {
      res.status(500).send("ORS_API_KEY not configured");
      return;
    }

    const url = `https://api.openrouteservice.org${endpoint}`;

    try {
      const r = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": apiKey,
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
 * Usata dalla dashboard admin (/admin/index.html)
 * =========================
 */
exports.approveMemberHttp = onRequest(
  { region: "us-central1" },
  async (req, res) => {
    // CORS (ti serve perché chiamata da hosting web)
    res.set("Access-Control-Allow-Origin", "*");
    res.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set("Access-Control-Allow-Methods", "POST, OPTIONS");
    if (req.method === "OPTIONS") {
      res.status(204).send("");
      return;
    }

    if (req.method !== "POST") {
      res.status(405).json({ error: "Method Not Allowed" });
      return;
    }

    // Auth admin
    const decoded = await verifyToken(req);
    if (!decoded) {
      res.status(401).json({ error: "Unauthorized" });
      return;
    }
    if (decoded.role !== "admin") {
      res.status(403).json({ error: "Forbidden (admin only)" });
      return;
    }

    const uid = req.body?.uid;
    if (!uid || typeof uid !== "string") {
      res.status(400).json({ error: "Missing uid" });
      return;
    }

    try {
      const year = currentYear();

      // Leggo user doc per essere idempotente (se già ha membership, non la rigenero)
      const userRef = admin.firestore().doc(`users/${uid}`);
      const snap = await userRef.get();
      const data = snap.exists ? (snap.data() || {}) : {};

      const existingNumber = data?.membership?.number || data?.membershipNumber || null;
      const membershipNumber = existingNumber || makeMembershipNumber(uid, year);

      const membershipValidUntil = data?.membership?.validUntil || data?.membershipValidUntil || endOfYear(year);
      const membershipValidFrom = data?.membership?.validFrom || startOfYear(year);

      // Claims
      await admin.auth().setCustomUserClaims(uid, { approved: true, role: "member" });

      // Profilo + membership (merge per non perdere i campi che già avete: address, fiscalCode, ecc.)
      await userRef.set(
        {
          status: "active",
          role: "member",
          approvedAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),

          // legacy compat (finché vuoi)
          membershipNumber,
          membershipValidUntil,

          // nuovo blocco membership
          membership: {
            number: membershipNumber,
            issuedAt: data?.membership?.issuedAt || admin.firestore.FieldValue.serverTimestamp(),
            validFrom: membershipValidFrom,
            validUntil: membershipValidUntil,
            status: "active",
            paymentStatus: data?.membership?.paymentStatus || "unpaid",
            lastPaymentAt: data?.membership?.lastPaymentAt || null,
            tier: data?.membership?.tier || "standard",
          },
        },
        { merge: true }
      );

      res.status(200).json({ ok: true, uid, membershipNumber, membershipValidUntil });
    } catch (e) {
      logger.error("approveMemberHttp error", e);
      res.status(500).json({ error: e?.message || "Internal error" });
    }
  }
);

/**
 * =========================
 * APPROVE MEMBER (CALLABLE) - opzionale
 * Se in futuro vuoi approvare da SDK invece che da URL
 * =========================
 */
exports.approveMember = onCall(async (request) => {
  const decoded = request.auth?.token;
  if (!decoded) throw new HttpsError("unauthenticated", "Devi essere autenticato.");
  if (decoded.role !== "admin") throw new HttpsError("permission-denied", "Non sei admin.");

  const uid = request.data?.uid;
  if (!uid) throw new HttpsError("invalid-argument", "Missing uid");

  const year = currentYear();
  const userRef = admin.firestore().doc(`users/${uid}`);
  const snap = await userRef.get();
  const data = snap.exists ? (snap.data() || {}) : {};

  const existingNumber = data?.membership?.number || data?.membershipNumber || null;
  const membershipNumber = existingNumber || makeMembershipNumber(uid, year);

  const membershipValidUntil = data?.membership?.validUntil || data?.membershipValidUntil || endOfYear(year);
  const membershipValidFrom = data?.membership?.validFrom || startOfYear(year);

  await admin.auth().setCustomUserClaims(uid, { approved: true, role: "member" });

  await userRef.set(
    {
      status: "active",
      role: "member",
      approvedAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      membershipNumber,
      membershipValidUntil,
      membership: {
        number: membershipNumber,
        issuedAt: data?.membership?.issuedAt || admin.firestore.FieldValue.serverTimestamp(),
        validFrom: membershipValidFrom,
        validUntil: membershipValidUntil,
        status: "active",
        paymentStatus: data?.membership?.paymentStatus || "unpaid",
        lastPaymentAt: data?.membership?.lastPaymentAt || null,
        tier: data?.membership?.tier || "standard",
      },
    },
    { merge: true }
  );

  return { ok: true, uid, membershipNumber, membershipValidUntil };
});
