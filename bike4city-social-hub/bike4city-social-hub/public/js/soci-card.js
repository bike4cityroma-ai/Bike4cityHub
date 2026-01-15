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
const whoPill = $("whoPill");

const payTbody = $("payTbody");
const payHint = $("payHint");
const payCount = $("payCount");

const qrCanvas = $("qrCanvas");

const btnPrintCard = $("btnPrintCard");
const logoutBtn = $("logoutBtn");

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

function escapeHtml(s){
  return String(s ?? "")
    .replaceAll("&","&amp;")
    .replaceAll("<","&lt;")
    .replaceAll(">","&gt;")
    .replaceAll('"',"&quot;")
    .replaceAll("'","&#39;");
}

function printReceipt({ member, payment }) {
  const html = `<!doctype html><html lang="it"><head><meta charset="utf-8"/>
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
    <tr><td><b>Socio</b></td><td>${escapeHtml(`${safe(member.firstName)} ${safe(member.lastName)}`.trim() || safe(member.displayName) || "—")}</td></tr>
    <tr><td><b>Email</b></td><td>${escapeHtml(safe(member.email) || "—")}</td></tr>
    <tr><td><b>Numero tessera</b></td><td>${escapeHtml(safe(member.membershipNumber) || "—")}</td></tr>
    <tr><td><b>Data pagamento</b></td><td>${escapeHtml(fmtDate(payment.date))}</td></tr>
    <tr><td><b>Importo</b></td><td>${escapeHtml(euro(payment.amount))}</td></tr>
    <tr><td><b>Metodo</b></td><td>${escapeHtml(payment.method || "—")}</td></tr>
    <tr><td><b>Validità fino al</b></td><td>${escapeHtml(payment.validUntilIso || member.membershipValidUntil || "—")}</td></tr>
    <tr><td><b>Nota</b></td><td>${escapeHtml(payment.note || "—")}</td></tr>
    <tr><td><b>ID</b></td><td class="muted">${escapeHtml(payment._id || "—")}</td></tr>
  </table>
  <div class="hr"></div>
  <div class="muted">Associazione Bike4City – Conserva questa ricevuta per i tuoi archivi.</div>
</div>
<script>window.onload=()=>window.print();</script>
</body></html>`;

  const w = window.open("", "_blank");
  if (!w) return;
  w.document.open(); w.document.write(html); w.document.close();
}

function printCard(member){
  const html = `<!doctype html><html lang="it"><head><meta charset="utf-8"/>
<title>Tessera Bike4City</title>
<style>
  body{ font-family: Arial, sans-serif; padding:24px; }
  .muted{ color:#555; font-size:12px; }
  .card{ border:1px solid #ddd; border-radius:14px; overflow:hidden; max-width:720px; }
  .top{ background:#111; color:#fff; padding:18px; }
  .name{ font-size:20px; font-weight:900; }
  .sub{ margin-top:6px; font-size:12px; opacity:.9; }
  .grid{ display:flex; gap:12px; justify-content:space-between; margin-top:14px; }
  .kv b{ display:block; font-size:11px; opacity:.85; }
  .kv div{ font-size:13px; font-weight:700; }
  .bottom{ padding:14px 18px; }
</style></head><body>
  <div class="card">
    <div class="top">
      <div class="name">${escapeHtml(`${safe(member.firstName)} ${safe(member.lastName)}`.trim() || safe(member.displayName) || "Socio")}</div>
      <div class="sub">${escapeHtml(safe(member.email) || "—")}</div>
      <div class="grid">
        <div class="kv"><b>Tessera</b><div>${escapeHtml(safe(member.membershipNumber) || "—")}</div></div>
        <div class="kv"><b>Validità</b><div>${escapeHtml(safe(member.membershipValidUntil) || "—")}</div></div>
        <div class="kv"><b>Stato</b><div>${escapeHtml(safe(member.status) || "pending")}</div></div>
      </div>
    </div>
    <div class="bottom">
      <div class="muted">Documento generato dal sistema (stampa/salva come PDF).</div>
    </div>
  </div>
  <script>window.onload=()=>window.print();</script>
</body></html>`;
  const w = window.open("", "_blank");
  if (!w) return;
  w.document.open(); w.document.write(html); w.document.close();
}

async function loadPayments(uid, member){
  payTbody.innerHTML = `<tr><td colspan="5" class="muted">Caricamento…</td></tr>`;
  payHint.textContent = "";
  payCount.textContent = "…";

  try{
    const ref = collection(db, "users", uid, "payments");
    const q = query(ref, orderBy("date","desc"), limit(20));
    const snap = await getDocs(q);
    const rows = snap.docs.map(d => ({ _id: d.id, ...d.data() }));

    payCount.textContent = rows.length;

    if(!rows.length){
      payTbody.innerHTML = `<tr><td colspan="5" class="muted">Nessun pagamento registrato.</td></tr>`;
      payHint.textContent = "Se hai rinnovato di recente, potrebbe non essere ancora stato registrato.";
      return;
    }

    payTbody.innerHTML = "";
    rows.forEach(p=>{
      const tr = document.createElement("tr");
      tr.innerHTML = `
        <td>${fmtDate(p.date)}</td>
        <td>${euro(p.amount)}</td>
        <td>${escapeHtml(safe(p.method) || "—")}</td>
        <td>${escapeHtml(safe(p.validUntilIso) || "—")}</td>
        <td><button class="btn btn-sm">PDF</button></td>
      `;
      tr.querySelector("button").addEventListener("click", () => printReceipt({ member, payment: p }));
      payTbody.appendChild(tr);
    });
  }catch(e){
    console.error(e);
    payHint.textContent = "Errore nel caricamento pagamenti.";
    payTbody.innerHTML = `<tr><td colspan="5" style="color:#b00020;">❌ ${escapeHtml(e?.message || e)}</td></tr>`;
    payCount.textContent = "0";
  }
}

async function main(){
  await requireAuth({ requireRole: "member" });

  logoutBtn?.addEventListener("click", async (e)=>{
    e.preventDefault();
    await signOut(auth);
    location.href = "/index.html";
  });

  const uid = auth.currentUser.uid;
  whoPill.textContent = auth.currentUser.email || "Socio";

  const uref = doc(db, "users", uid);
  const usnap = await getDoc(uref);
  const u = usnap.data() || {};

  const name = `${safe(u.firstName)} ${safe(u.lastName)}`.trim() || safe(u.displayName) || "Socio";
  mName.textContent = name;
  mEmail.textContent = safe(u.email) || auth.currentUser.email || "—";
  mNumber.textContent = safe(u.membershipNumber) || "—";
  mValid.textContent = safe(u.membershipValidUntil) || "—";
  mStatus.innerHTML = pill(u.status);

  // QR: usa membershipNumber (fallback uid)
  const qrValue = safe(u.membershipNumber) || uid;
  if (window.QRCode && qrCanvas) {
    window.QRCode.toCanvas(qrCanvas, qrValue, { margin: 1, width: 86 }, (err) => {
      if (err) console.error("QR error", err);
    });
  }

  btnPrintCard?.addEventListener("click", () => printCard(u));

  await loadPayments(uid, u);
}

main();
