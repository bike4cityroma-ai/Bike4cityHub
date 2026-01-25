const fs = require("fs");
const csv = require("csv-parser");
const admin = require("firebase-admin");

// Service Account (NON committare su git)
admin.initializeApp({
  credential: admin.credential.cert(require("./serviceAccountKey.json")),
  projectId: "bike4city-social-hub"
});

const db = admin.firestore();

const FILE = "da_inserire.csv";
const COLLECTION = "members_registry";

// ---- helpers ----
function normalizeFiscalCode(v) {
  return String(v || "").trim().toUpperCase().replace(/\s+/g, "");
}

// da "20/05/1960" -> "1960-05-20"
function normalizeBirthDate(v) {
  const s = String(v || "").trim();
  if (!s) return "";
  // accetta sia dd/mm/yyyy che yyyy-mm-dd
  if (s.includes("/")) {
    const [dd, mm, yyyy] = s.split("/");
    if (!yyyy || !mm || !dd) return "";
    const DD = dd.padStart(2, "0");
    const MM = mm.padStart(2, "0");
    return `${yyyy}-${MM}-${DD}`;
  }
  return s; // assumo già ISO
}

function toStringSafe(v) {
  if (v === null || v === undefined) return "";
  return String(v).trim();
}

async function run() {
  const rows = [];

  fs.createReadStream(FILE)
    .pipe(csv())
    .on("data", (data) => rows.push(data))
    .on("end", async () => {
      console.log(`Letti ${rows.length} record`);

      let batch = db.batch();
      let written = 0;
      let skippedNoCF = 0;

      // scadenza unica per i soci 2025: 31/12/2025 23:59:59 (Europa/Roma)
      const validUntilTs = admin.firestore.Timestamp.fromDate(
        new Date("2025-12-31T23:59:59+01:00")
      );

      for (const r of rows) {
        const cf = normalizeFiscalCode(r["Codice fiscale"]);
        if (!cf) {
          skippedNoCF++;
          continue;
        }

        // ✅ docId = CF => niente doppioni MAI
        const ref = db.collection(COLLECTION).doc(cf);

        const doc = {
          // campi canonici per members_registry
          fiscalCode: cf,
          firstName: toStringSafe(r["Nome"]),
          lastName: toStringSafe(r["Cognome"]),
          email: toStringSafe(r["Indirizzo email"]).toLowerCase(),
          phone: toStringSafe(r["telefono"]),
          birthDate: normalizeBirthDate(r["data di nascita"]),
          city: toStringSafe(r["Città di residenza"]),
          address: toStringSafe(r["Indirizzo di residenza"]),
          zip: "",

          // stato “registro”
          statusRegistry: "expired",
          membershipValidUntilTs: validUntilTs,

          // ancora non agganciato
          claimedByUid: null,
          claimedAt: null,

          // audit
          createdAt: admin.firestore.Timestamp.now(),
          updatedAt: admin.firestore.Timestamp.now(),

          // opzionale: tieni anche info extra del CSV
          birthPlace: toStringSafe(r["Nato a"])
        };

        // merge true: se ricarichi lo stesso CSV aggiorna e non duplica
        batch.set(ref, doc, { merge: true });
        written++;

        // Firestore batch max 500
        if (written % 500 === 0) {
          await batch.commit();
          console.log(`Scritti/Aggiornati ${written}`);
          batch = db.batch();
        }
      }

      if (written % 500 !== 0) {
        await batch.commit();
      }

      console.log(
        `✅ Import completato. Scritti/Aggiornati: ${written}. Saltati senza CF: ${skippedNoCF}`
      );
      process.exit(0);
    });
}

run().catch((err) => {
  console.error("❌ Errore import:", err);
  process.exit(1);
});
