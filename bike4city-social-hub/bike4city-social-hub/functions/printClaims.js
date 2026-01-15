const admin = require("firebase-admin");

admin.initializeApp({
  credential: admin.credential.cert(require("./serviceAccountKey.json")),
});

const uid = process.argv[2];
if (!uid) {
  console.log("Uso: node printClaims.js <UID>");
  process.exit(1);
}

(async () => {
  const u = await admin.auth().getUser(uid);
  console.log("email:", u.email);
  console.log("customClaims:", u.customClaims);
  process.exit(0);
})();
const admin = require("firebase-admin");

admin.initializeApp();

async function main() {
  const email = process.argv[2];
  if (!email) throw new Error("Passa una email: node printClaims.js user@email.it");

  const u = await admin.auth().getUserByEmail(email);
  console.log("uid:", u.uid);
  console.log("email:", u.email);
  console.log("claims:", u.customClaims || {});
}

main().catch(e => { console.error(e); process.exit(1); });
