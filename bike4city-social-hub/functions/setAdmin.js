const admin = require("firebase-admin");

// 1) scarica una service account key dal Firebase project (Settings → Service accounts)
// 2) salva il json come serviceAccountKey.json qui nella cartella
admin.initializeApp({
  credential: admin.credential.cert(require("./serviceAccountKey.json")),
});

const uid = process.argv[2];
if (!uid) {
  console.log("Uso: node setAdmin.js <UID>");
  process.exit(1);
}

(async () => {
  await admin.auth().setCustomUserClaims(uid, { approved: true, role: "admin" });
  console.log("Admin impostato ✅");
  process.exit(0);
})();
