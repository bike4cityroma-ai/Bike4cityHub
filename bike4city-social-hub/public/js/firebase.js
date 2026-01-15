import { initializeApp, getApps } from "https://www.gstatic.com/firebasejs/10.12.5/firebase-app.js";
import {
  getAuth,
  setPersistence,
  browserLocalPersistence
} from "https://www.gstatic.com/firebasejs/10.12.5/firebase-auth.js";
import { getFirestore } from "https://www.gstatic.com/firebasejs/10.12.5/firebase-firestore.js";

export const firebaseConfig = {
  apiKey: "AIzaSyDGFlcFie1odRVolXaAKnV_sAwHjNvE2WI",
  authDomain: "bike4city-social-hub.firebaseapp.com",
  projectId: "bike4city-social-hub",
  storageBucket: "bike4city-social-hub.firebasestorage.app",
  messagingSenderId: "1040753382248",
  appId: "1:1040753382248:web:3b632b6ba413b61ec8fcdd",
};

// evita doppia init
export const app = getApps().length
  ? getApps()[0]
  : initializeApp(firebaseConfig);

export const auth = getAuth(app);
export const db = getFirestore(app);

// 🔐 QUESTO È IL PEZZO CHE MANCAVA
await setPersistence(auth, browserLocalPersistence);
