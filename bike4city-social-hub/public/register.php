<?php
/**
 * Shortcode: [bike4city_register]
 */

add_shortcode('bike4city_register', function () {
  ob_start();
  ?>
  <div style="max-width:760px;margin:0 auto;padding:16px;border:1px solid #e5e5e5;border-radius:12px">
    <h2>Iscrizione / Rinnovo Bike4City</h2>

    <p style="color:#666">
      Compila il modulo per iscriverti o rinnovare la tessera.
    </p>

    <p id="b4cMsg" style="padding:10px;border-radius:8px;background:#f7f7f7"></p>

    <form id="b4cReg">

      <h3>Account</h3>
      <label>Email *</label>
      <input name="email" type="email" required>

      <label>Password *</label>
      <input name="password" type="password" required minlength="6">

      <label style="margin-top:10px;display:block">
        <input type="checkbox" name="renewalFlag">
        <b>Rinnovo tessera</b> (ero socio nel 2025)
      </label>

      <h3>Dati anagrafici</h3>
      <label>Nome *</label>
      <input name="firstName" required>

      <label>Cognome *</label>
      <input name="lastName" required>

      <label>Data di nascita *</label>
      <input name="birthDate" type="date" required>

      <label>Codice fiscale *</label>
      <input name="fiscalCode" required maxlength="16">

      <h3>Contatti</h3>
      <label>Telefono *</label>
      <input name="phone" required>

      <label>Indirizzo *</label>
      <input name="address" required>

      <label>Città *</label>
      <input name="city" required>

      <label>CAP</label>
      <input name="zip">

      <h3>Consensi</h3>
      <label>
        <input name="privacyAccepted" type="checkbox" required>
        Accetto privacy *
      </label><br>

      <label>
        <input name="newsletterOptIn" type="checkbox">
        Newsletter
      </label>

      <button type="submit" id="b4cSubmit">Invia</button>
    </form>
  </div>
  <?php
  return ob_get_clean();
});

add_action('wp_footer', function () {
  global $post;
  if (!$post || strpos($post->post_content, '[bike4city_register]') === false) return;
?>
<script>
/* =============================
   CONFIGURAZIONE FIREBASE
   ============================= */
const firebaseConfig = {
  apiKey: "AIzaSyDGFlcFie1odRVolXaAKnV_sAwHjNvE2WI",
  authDomain: "bike4city-social-hub.firebaseapp.com",
  projectId: "bike4city-social-hub",
  appId: "1:1040753382248:web:3b632b6ba413b61ec8fcdd",
};

/* =============================
   ENDPOINT CLOUD FUNCTIONS
   ============================= */
const REGISTER_ENDPOINT =
  "https://us-central1-bike4city-social-hub.cloudfunctions.net/registerMember";

const RENEWAL_ENDPOINT =
  "https://us-central1-bike4city-social-hub.cloudfunctions.net/claimRenewalHttp";

/* =============================
   CARICAMENTO FIREBASE
   ============================= */
(function loadFirebase(){
  const s1 = document.createElement("script");
  s1.src = "https://www.gstatic.com/firebasejs/10.12.5/firebase-app-compat.js";
  s1.onload = () => {
    const s2 = document.createElement("script");
    s2.src = "https://www.gstatic.com/firebasejs/10.12.5/firebase-auth-compat.js";
    s2.onload = initFirebase;
    document.head.appendChild(s2);
  };
  document.head.appendChild(s1);
})();

function initFirebase(){
  if (!firebase.apps.length) {
    firebase.initializeApp(firebaseConfig);
  }
  bindForm();
}

/* =============================
   UTIL
   ============================= */
function qs(id){ return document.getElementById(id); }
function msg(t, ok){
  const el = qs("b4cMsg");
  el.textContent = t;
  el.style.color = ok ? "green" : "crimson";
}

/* =============================
   LOGIN + TOKEN
   ============================= */
async function loginAndToken(email, password){
  const cred = await firebase.auth()
    .signInWithEmailAndPassword(email, password);
  return await cred.user.getIdToken(true);
}

/* =============================
   SUBMIT FORM
   ============================= */
function bindForm(){
  const form = qs("b4cReg");
  const btn  = qs("b4cSubmit");

  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    btn.disabled = true;
    msg("Invio in corso…", true);

    const f = form;

    const payload = {
      email: f.email.value.trim().toLowerCase(),
      password: f.password.value,
      firstName: f.firstName.value.trim(),
      lastName: f.lastName.value.trim(),
      birthDate: f.birthDate.value,
      fiscalCode: f.fiscalCode.value.trim().toUpperCase(),
      phone: f.phone.value.trim(),
      address: f.address.value.trim(),
      city: f.city.value.trim(),
      zip: f.zip.value.trim(),
      newsletterOptIn: f.newsletterOptIn.checked,
      privacyAccepted: f.privacyAccepted.checked,
      renewalFlag: f.renewalFlag.checked
    };

    try {
      /* ===== RINNOVO ===== */
      if (payload.renewalFlag) {
        const token = await loginAndToken(payload.email, payload.password);

        const r = await fetch(RENEWAL_ENDPOINT, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Authorization": "Bearer " + token
          },
          body: JSON.stringify(payload)
        });

        const d = await r.json();
        if (!r.ok) throw new Error(d.error);

        msg("✅ Rinnovo effettuato. Stato: PENDING.", true);
        form.reset();
        return;
      }

      /* ===== ISCRIZIONE ===== */
      const r = await fetch(REGISTER_ENDPOINT, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });

      const d = await r.json();
      if (!r.ok) throw new Error(d.error);

      msg("✅ Iscrizione inviata. Attendi approvazione.", true);
      form.reset();

    } catch (err) {
      msg("❌ " + (err.message || "Errore"), false);
      console.error(err);
    } finally {
      btn.disabled = false;
    }
  });
}
</script>
<?php
}, 100);
