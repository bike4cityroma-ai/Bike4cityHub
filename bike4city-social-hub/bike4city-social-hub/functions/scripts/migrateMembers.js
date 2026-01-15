/* eslint-disable no-console */

const admin = require("firebase-admin");

// -------------------------
// CLI args
// -------------------------
const argv = process.argv.slice(2);
const DRY =
  argv.includes("--dry") || argv.includes("--dry-run") || argv.includes("--dryrun");
const LIMIT = (() => {
  const a = argv.find((x) => x.startsWith("--limit="));
  if (!a) return null;
  const n = parseInt(a.split("=")[1], 10);
  return Number.isFinite(n) && n > 0 ? n : null;
})();

function normalizeIsoDateString(s) {
  if (!s) return "";
  const v = String(s).trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(v)) return v; // ISO
  // dd-mm-yyyy -> yyyy-mm-dd
  const m = v.match(/^(\d{2})-(\d{2})-(\d{4})$/);
  if (m) return `${m[3]}-${m[2]}-${m[1]}`;
  // dd/mm/yyyy -> yyyy-mm-dd
  const m2 = v.match(/^(\d{2})\/(\d{2})\/(\d{4})$/);
  if (m2) return `${m2[3]}-${m2[2]}-${m2[1]}`;
  return v;
}

function isoDateEndOfDayTimestamp(isoDate) {
  const v = normalizeIsoDateString(isoDate);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(v)) return null;
  const d = new Date(`${v}T23:59:59.000Z`);
  if (Number.isNaN(d.getTime())) return null;
  return admin.firestore.Timestamp.fromDate(d);
}

function computeStatus(currentStatus, approvedAt, validUntilTs) {
  const s = String(currentStatus || "").toLowerCase().trim();
  if (s === "pending") return "pending";

  const isApprovedLike =
    s === "approved" || s === "active" || s === "expired" || !!approvedAt;

  if (!isApprovedLike) {
    // fallback: se non sappiamo nulla, non tocchiamo (ma riportiamo a pending se vuoto)
    return s ? s : "pending";
  }

  if (!validUntilTs) return "active"; // approvato ma senza scadenza => consideriamo attivo

  const now = new Date();
  const end = validUntilTs.toDate();
  return end.getTime() >= now.getTime() ? "active" : "expired";
}

async function main() {
  // Init admin (service account for local run)
  if (!admin.apps.length) {
    admin.initializeApp({
      credential: admin.credential.cert(require("../serviceAccountKey.json")),
    });
  }

  const db = admin.firestore();
  const col = db.collection("users");

  console.log("migrateMembers.js");
  console.log("dry-run:", DRY);
  console.log("limit:", LIMIT || "none");
  console.log("reading users...");

  const snap = await col.get();
  console.log("found:", snap.size);

  let processed = 0;
  let updatedDocs = 0;
  let batch = db.batch();
  let batchCount = 0;

  async function commitBatch() {
    if (DRY) {
      batch = db.batch();
      batchCount = 0;
      return;
    }
    if (batchCount === 0) return;
    await batch.commit();
    batch = db.batch();
    batchCount = 0;
  }

  for (const docSnap of snap.docs) {
    if (LIMIT && processed >= LIMIT) break;
    processed += 1;

    const ref = docSnap.ref;
    const data = docSnap.data() || {};
    const updates = {};

    // membership validUntil source
    const srcValidUntil =
      (data.membership && data.membership.validUntil) ||
      data.membershipValidUntil ||
      "";

    const validUntilIso = normalizeIsoDateString(srcValidUntil);
    const validUntilTs = isoDateEndOfDayTimestamp(validUntilIso);

    const currentStatus = data.status;
    const approvedAt =
      data.approvedAt || (data.membership && data.membership.approvedAt) || null;

    const newStatus = computeStatus(currentStatus, approvedAt, validUntilTs);

    // normalize top-level status
    if (newStatus !== currentStatus) {
      if (["pending", "active", "expired"].includes(newStatus)) {
        updates.status = newStatus;
      }
    }

    // normalize legacy membershipValidUntil string and ts
    if (validUntilIso && validUntilIso !== data.membershipValidUntil) {
      updates.membershipValidUntil = validUntilIso;
    }
    if (validUntilTs) {
      const existingTs = data.membershipValidUntilTs;
      const sameTs =
        existingTs &&
        existingTs.toDate &&
        existingTs.toDate().getTime() === validUntilTs.toDate().getTime();
      if (!sameTs) {
        updates.membershipValidUntilTs = validUntilTs;
      }
    }

    // membership object alignment (if exists)
    if (data.membership && typeof data.membership === "object") {
      const mem = data.membership;

      if (validUntilIso && mem.validUntil !== validUntilIso) {
        updates["membership.validUntil"] = validUntilIso;
      }
      if (validUntilTs) {
        const existingMemTs = mem.validUntilTs;
        const sameMemTs =
          existingMemTs &&
          existingMemTs.toDate &&
          existingMemTs.toDate().getTime() === validUntilTs.toDate().getTime();
        if (!sameMemTs) {
          updates["membership.validUntilTs"] = validUntilTs;
        }
      }
      if (["pending", "active", "expired"].includes(newStatus) && mem.status !== newStatus) {
        updates["membership.status"] = newStatus;
      }
    }

    // if old status was "approved", force to active/expired
    if (String(currentStatus || "").toLowerCase() === "approved") {
      updates.status = newStatus; // active or expired
      if (data.membership && typeof data.membership === "object") {
        updates["membership.status"] = newStatus;
      }
    }

    const keys = Object.keys(updates);
    if (keys.length) {
      updatedDocs += 1;
      if (DRY) {
        console.log(`[DRY] update ${ref.id}:`, updates);
      } else {
        batch.update(ref, updates);
        batchCount += 1;
        if (batchCount >= 400) {
          await commitBatch();
        }
      }
    }
  }

  await commitBatch();

  console.log("processed:", processed);
  console.log("docs needing update:", updatedDocs);
  console.log("done.");
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
