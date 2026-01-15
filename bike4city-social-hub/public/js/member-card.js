import { auth, db } from "/js/firebase.js";
import { requireAuth } from "/js/guard.js";
import { signOut } from "https://www.gstatic.com/firebasejs/10.12.5/firebase-auth.js";
import { doc, getDoc, collection, query, orderBy, getDocs, limit } from "https://www.gstatic.com/firebasejs/10.12.5/firebase-firestore.js";

const $ = (id) => document.getElementById(id);

const mName = $("mName");
const mEmail = $("mEmail");
const mNumber = $("mNumber");
const mValid = $("mValid");
const mStatus = $("mStatus");

const payTbody = $("payTbody");
const payHint = $("payHint");
const qrCanvas = $("qrCanvas");

function safe(v){ return (v===null||v===undefined) ? "" : String(v); }

function pill(st){
  const s = safe(st) || "pending";
  const cls = s === "active" ? "st-active" : (s === "expired" ? "st-expired" : "st-pending");
  return `<span class="pill ${cls}">${s}</span>`;
}
function fmtDate(ts){
  try{
    const d = ts?.toDate ? ts.toDate() : null;
    return d ? d.toLocaleDateString("it-IT") : "—";
  }catch{ return "—"; }
}
function euro(n){
  const v = Number(n||0);
  return v.toLocaleString("it-IT",{style:"currency",currency:"EUR"});
}

function printReceipt({ member, payment }) {
  const html = `
<!doctype html><html lang="it"><head><meta charset="utf-8"/>
<title>Ricevuta Bike4City</title>
<style>
  body{ font-family: Arial, sans-serif; padding:24px; }
  .box{ border:1px solid #ddd; border-radius:12px; padding:16px; max-width:720px; }
  h1{ margin:0 0 8px 0; font-size:20px; }
  .muted{ color:#555; font-size:12px; }
  table{ width:100%; border-collapse:collapse; margin-top:12px; }
  td{ padding:6px 4px; vertical-align:top; }
  .hr{ height:1px; background:#eee; margin:12px 0; }
</style></head><body>
<div class="box">
  <h1>Ricevuta pagamento – Bike4City</h1>
  <div class="muted">Documento generato dal sistema (stampa/salva come PDF)</div>
  <div class="hr"></div>
  <table>
    <tr><td><b>Socio</b></td><td>${(member.firstName||"")+" "+(member.lastName||"")}</td></tr>
    <tr><td><b>Email</b></td><td>${member.email||"—"}</td></tr>
    <tr><td><b>Numero tessera</b></td><td>${member.membershipNumber||"—"}</td></tr>
    <tr><td><b>Data pagamento</b></td><td>${fmtDate(payment.date)}</td></tr>
    <tr><td><b>Importo</b></td><td>${euro(payment.amount)}</td></tr>
    <tr><td><b>Metodo</b></td><td>${payment.method||"—"}</td></tr>
    <tr><td><b>Validità fino al</b></td><td>${payment.validUntilIso||member.membershipValidUntil||"—"}</td></tr>
    <tr><td><b>Nota</b></td><td>${payment.note||"—"}</td></tr>
    <tr><td><b>ID</b></td><td class="muted">${payment._id||"—"}</td></tr>
  </table>
  <div class="hr"></div>
  <div class="muted">Associazione Bike4City – Conserva questa ricevuta per i tuoi archivi.</div>
</div>
<script>window.onload=()=>window.print();</script>
</body></html>`;
  const w = window.open("", "_blank");
  w.document.open(); w.document.write(html); w.document.close();
}

async function loadPayments(uid, memberData){
  payTbody.innerHTML = `<tr><td colspan="5" class="muted">Caricamento…</td></tr>`;
  payHint.textContent = "";

  try{
    const ref = collection(db, "users", uid, "payments");
    const q = query(ref, orderBy("date","desc"), limit(20));
    const snap = await getDocs(q);
    const rows = snap.docs.map(d => ({ _id: d.id, ...d.data() }));

    if(!rows.length){
      payTbody.innerHTML = `<tr><td colspan="5" class="muted">Nessun pagamento registrato.</td></tr>`;
      return;
    }

    payTbody.innerHTML = "";
    rows.forEach(p=>{
      const tr = document.createElement("tr");
      tr.innerHTML = `
        <td>${fmtDate(p.date)}</td>
        <td>${euro(p.amount)}</td>
        <td>${p.method || "—"}</td>
        <td>${p.validUntilIso || "—"}</td>
        <td><button class="btn btn-sm">PDF</button></td>
      `;
      tr.querySelector("button").addEventListener("click", () => printReceipt({ member: memberData, payment: p }));
      payTbody.appendChild(tr);
    });
  }catch(e){
    console.error(e);
    payHint.textContent = "Errore nel caricamento pagamenti.";
    payTbody.innerHTML = `<tr><td colspan="5" style="color:#b00020;">❌ ${e?.message || e}</td></tr>`;
  }
}

async function main(){
  await requireAuth({ requireRole: "member" });

  $("logoutBtn")?.addEventListener("click", async (e)=>{
    e.preventDefault();
    await signOut(auth);
    location.href = "/index.html";
  });

  const uid = auth.currentUser.uid;
  const uref = doc(db,"users",uid);
  const usnap = await getDoc(uref);
  const u = usnap.data() || {};

  mName.textContent = `${safe(u.firstName)} ${safe(u.lastName)}`.trim() || safe(u.displayName) || "Socio";
  mEmail.textContent = safe(u.email) || auth.currentUser.email || "—";
  mNumber.textContent = safe(u.membershipNumber) || "—";
  mValid.textContent = safe(u.membershipValidUntil) || "—";
  mStatus.innerHTML = pill(u.status);

  // QR: usa membershipNumber (o uid fallback)
  const qrValue = safe(u.membershipNumber) || uid;
  if (window.QRCode && qrCanvas) {
    QRCode.toCanvas(qrCanvas, qrValue, { margin: 1, width: 84 }, (err) => {
      if (err) console.error("QR error", err);
    });
  }

  await loadPayments(uid, u);
}

main();
