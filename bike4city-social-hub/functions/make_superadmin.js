const admin = require("firebase-admin");
const path = require("path");

const serviceAccount = require(path.join(__dirname, "serviceAccountKey.json"));

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
});

async function run() {
  const uid = "l0ewnT8dhkZ0zBuNZ42yTF4fHoN2";

  await admin.auth().setCustomUserClaims(uid, {
    role: "superadmin",
    approved: true,
  });

  console.log("✅ Superadmin impostato su:", uid);
  process.exit(0);
}

run().catch((e) => {
  console.error("❌ Errore:", e);
  process.exit(1);
});
