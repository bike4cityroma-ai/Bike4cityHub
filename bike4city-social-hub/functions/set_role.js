const admin = require("firebase-admin");
const path = require("path");

const serviceAccount = require(path.join(__dirname, "serviceAccountKey.json"));

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
});

async function run() {
  const uid = process.argv[2];
  const role = process.argv[3];

  if (!uid || !role) {
    console.log('Uso: node set_role.js <UID> <member|admin|superadmin>');
    process.exit(1);
  }

  const allowed = ["member", "admin", "superadmin"];
  if (!allowed.includes(role)) {
    console.log("Ruolo non valido:", role);
    process.exit(1);
  }

  // 🔐 CLAIMS = verità assoluta
  await admin.auth().setCustomUserClaims(uid, {
    role,
    approved: true,
  });

  // 📄 FIRESTORE = UI (superadmin resta admin)
  const firestoreRole = (role === "superadmin") ? "admin" : role;

  await admin.firestore().doc(`users/${uid}`).set(
    {
      role: firestoreRole,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    },
    { merge: true }
  );

  console.log(
    `✅ UID ${uid}\n` +
    `   → claims.role = "${role}"\n` +
    `   → firestore.users.role = "${firestoreRole}"`
  );

  process.exit(0);
}

run().catch((e) => {
  console.error("❌ Errore:", e);
  process.exit(1);
});
