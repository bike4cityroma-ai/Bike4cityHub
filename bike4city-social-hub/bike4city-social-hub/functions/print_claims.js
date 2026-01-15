const admin = require("firebase-admin");
const path = require("path");

const serviceAccount = require(path.join(__dirname, "serviceAccountKey.json"));

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
});

async function show(uid) {
  const u = await admin.auth().getUser(uid);
  console.log("\nUID:", uid);
  console.log("email:", u.email);
  console.log("customClaims:", u.customClaims || {});
}

async function run() {
  await show("l0ewnT8dhkZ0zBuNZ42yTF4fHoN2"); // admin hub
  await show("dwPcXghW1tZf4Yqp11abRB8rImK2"); // livio
  process.exit(0);
}

run().catch(e => {
  console.error("Errore:", e);
  process.exit(1);
});
