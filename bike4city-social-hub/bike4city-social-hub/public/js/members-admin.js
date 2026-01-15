import { auth, db } from "/js/firebase.js";
import { requireAuth } from "/js/guard.js";
import { signOut } from "https://www.gstatic.com/firebasejs/10.12.5/firebase-auth.js";
import {
  collection, query, where, orderBy, getDocs, limit
} from "https://www.gstatic.com/firebasejs/10.12.5/firebase-firestore.js";

const APPROVE_URL = "https://us-central1-bike4city-social-hub.cloudfunctions.net/approveMemberHttp";
const PAY_RENEW_URL = "https://us-central1-bike4city-social-hub.cloudfunctions.net/recordPaymentAndRenewHttp";

const $ = (id) => document.getElementById(id);

const tbody = $("tbody");
const search = $("search");
const countPill = $("countPill");
const hint = $("hint");
const btnExportCsv = $("btnExportCsv");

const dlg = $("memberDlg");
const dlgTitle = $("dlgTitle");
const dlgEmail = $("dlgEmail");
const dlgUid = $("dlgUid");
const dlgStatus = $("dlgStatus");
const dlgMsg = $("dlgMsg");
const btnApprove = $("btnApprove");
const btnClose = $("btnClose");

/* Tessera preview */
const cardName = $("cardName");
const cardEmail = $("cardEmail");
const cardNumber = $("cardNumber");
const cardValid = $("cardValid");
const cardStatusPill = $("cardStatusPill");
const cardQrBox = $("cardQrBox");

/* Pagamento UI */
const payAmount = $("payAmount");
const payMethod = $("payMethod");
const payNote = $("payNote");
const btnPayRenew = $("btnPayRenew");

const payTbody = document.getElementById("payTbody");
const payHint = document.getElementById("payHint");

let currentStatus = "pending";
let members = [];
let currentMember = null;

function safe(v) {
  if (v === null || v === undefined) return "";
  return String(v);
}

function pillForStatus(st) {
  if (st === "active") return `<span class="pill-status st-active">active</span>`;
  if (st === "expired") return `<span class="pill-status st-expired">expired</span>`;
  return `<span class="pill-status st-pending">pending</span>`;
}

function toMillisMaybe(x) {
  if (x && typeof x.toMillis === "function") return x.toMillis();
  return 0;
}

function showError(msg, err) {
  console.error(msg, err);
  tbody.innerHTML = `<tr><td colspan="5" style="color:#b00020;">❌ ${msg}</td></tr>`;
  hint.textContent = (err && err.message) ? err.message : "";
}

async function logout() {
  try { await signOut(auth); } catch (e) {}
  location.href = "/index.html";
}

async function load(status) {
  currentStatus = status;
  tbody.innerHTML = `<tr><td colspan="5" class="muted">Caricamento…</td></tr>`;
  hint.textContent = "";

  const usersRef = collection(db, "users");

  // 1) Query "bella" (può richiedere indici)
  try {
    let q;
    if (status === "pending") {
      q = query(
        usersRef,
        where("status", "==", "pending"),
        orderBy("createdAt", "desc")
      );
    } else if (status === "active") {
      q = query(
        usersRef,
        where("status", "==", "active"),
        orderBy("membershipValidUntilTs", "asc")
      );
    } else {
      q = query(
        usersRef,
        where("status", "==", "expired"),
        orderBy("membershipValidUntilTs", "desc")
      );
    }

    const snap = await getDocs(q);
    members = snap.docs.map(d => ({ id: d.id, ...d.data() }));
    countPill.textContent = members.length;
    render();
    return;
  } catch (err) {
    console.warn("Query con orderBy fallita, provo fallback senza orderBy:", err);
  }

  // 2) Fallback: senza orderBy + sort client
  try {
    const q2 = query(usersRef, where("status", "==", status));
    const snap2 = await getDocs(q2);
    members = snap2.docs.map(d => ({ id: d.id, ...d.data() }));

    if (status === "pending") {
      members.sort((a, b) => toMillisMaybe(b.createdAt) - toMillisMaybe(a.createdAt));
    } else if (status === "active") {
      members.sort((a, b) => toMillisMaybe(a.membershipValidUntilTs) - toMillisMaybe(b.membershipValidUntilTs));
    } else {
      members.sort((a, b) => toMillisMaybe(b.membershipValidUntilTs) - toMillisMaybe(a.membershipValidUntilTs));
    }

    countPill.textContent = members.length;
    render();
  } catch (err2) {
    showError("Impossibile caricare l'elenco soci (rules/indici/campi).", err2);
  }
}

function render() {
  const q = safe(search.value).toLowerCase().trim();

  const filtered = !q ? members : members.filter(m => {
    const full = `${safe(m.firstName)} ${safe(m.lastName)}`.toLowerCase();
    return (
      full.includes(q) ||
      safe(m.email).toLowerCase().includes(q) ||
      safe(m.membershipNumber).toLowerCase().includes(q) ||
      safe(m.displayName).toLowerCase().includes(q) ||
      safe(m.id).toLowerCase().includes(q)
    );
  });

  if (q) hint.textContent = `Risultati: ${filtered.length}/${members.length}`;
  else hint.textContent = "";

  if (!filtered.length) {
    tbody.innerHTML = `<tr><td colspan="5" class="muted">Nessun socio trovato.</td></tr>`;
    return;
  }

  tbody.innerHTML = "";
  filtered.forEach(m => {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td>${safe(m.firstName)} ${safe(m.lastName) || safe(m.displayName) || ""}</td>
      <td>${safe(m.email)}</td>
      <td>${safe(m.membershipNumber) || "—"}</td>
      <td>${safe(m.membershipValidUntil) || "—"}</td>
      <td>${pillForStatus(safe(m.status))}</td>
    `;
    tr.addEventListener("click", () => openDialog(m));
    tbody.appendChild(tr);
  });
}

function openDialog(m) {
  currentMember = m;

  const name =
    `${safe(m.firstName)} ${safe(m.lastName)}`.trim() ||
    safe(m.displayName) ||
    "Socio";

  const email = safe(m.email) || "—";
  const number = safe(m.membershipNumber) || "—";
  const valid = safe(m.membershipValidUntil) || "—";
  const st = safe(m.status) || "pending";

  dlgTitle.textContent = name;

  dlgEmail.textContent = email;
  dlgUid.textContent = m.id;
  dlgStatus.textContent = st;
  dlgMsg.textContent = "";

  cardName.textContent = name;
  cardEmail.textContent = email;
  cardNumber.textContent = number;
  cardValid.textContent = valid;
  cardStatusPill.innerHTML = pillForStatus(st);

  if (cardQrBox) cardQrBox.textContent = "QR";

  // reset form pagamento
  if (payAmount) payAmount.value = "";
  if (payNote) payNote.value = "";

  btnApprove.style.display = (st === "pending") ? "inline-flex" : "none";

  dlg.showModal();

  // storico pagamenti
  loadPaymentsForMember(m.id, m);
}


function fmtDate(ts) {
  try {
    const d = ts?.toDate ? ts.toDate() : (ts ? new Date(ts) : null);
    if (!d) return "—";
    return d.toLocaleDateString("it-IT");
  } catch {
    return "—";
  }
}

function euro(n) {
  const v = Number(n || 0);
  return v.toLocaleString("it-IT", { style: "currency", currency: "EUR" });
}

function escapeHtml(s) {
  return String(s ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function printReceipt({ member, payment }) {
  const html = `<!doctype html>
<html lang="it"><head>
<meta charset="utf-8"/>
<title>Ricevuta Bike4City</title>
<style>
  body{ font-family: Arial, sans-serif; padding:24px; }
  .box{ border:1px solid #ddd; border-radius:12px; padding:16px; max-width:720px; }
  h1{ margin:0 0 8px 0; font-size:20px; }
  .muted{ color:#555; font-size:12px; }
  table{ width:100%; border-collapse:collapse; margin-top:12px; }
  td{ padding:6px 4px; vertical-align:top; }
  .hr{ height:1px; background:#eee; margin:12px 0; }
</style>
</head><body>
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
  w.document.open();
  w.document.write(html);
  w.document.close();
}

async function loadPaymentsForMember(uid, memberData) {
  if (!payTbody) return;

  payTbody.innerHTML = `<tr><td colspan="5" class="muted" style="padding:6px 4px;">Caricamento…</td></tr>`;
  if (payHint) payHint.textContent = "";

  try {
    const ref = collection(db, "users", uid, "payments");
    const q = query(ref, orderBy("date", "desc"), limit(20));
    const snap = await getDocs(q);
    const rows = snap.docs.map(d => ({ _id: d.id, ...d.data() }));

    if (!rows.length) {
      payTbody.innerHTML = `<tr><td colspan="5" class="muted" style="padding:6px 4px;">Nessun pagamento registrato.</td></tr>`;
      return;
    }

    payTbody.innerHTML = "";
    rows.forEach(p => {
      const tr = document.createElement("tr");
      tr.innerHTML = `
        <td style="padding:6px 4px;">${fmtDate(p.date)}</td>
        <td style="padding:6px 4px;">${euro(p.amount)}</td>
        <td style="padding:6px 4px;">${safe(p.method) || "—"}</td>
        <td style="padding:6px 4px;">${safe(p.validUntilIso) || "—"}</td>
        <td style="padding:6px 4px;"><button class="btn btn-sm">PDF</button></td>
      `;

      const btn = tr.querySelector("button");
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        printReceipt({ member: memberData, payment: p });
      });

      payTbody.appendChild(tr);
    });
  } catch (e) {
    console.error("loadPaymentsForMember error", e);
    if (payHint) payHint.textContent = "Errore nel caricamento pagamenti.";
    payTbody.innerHTML = `<tr><td colspan="5" style="color:#b00020; padding:6px 4px;">❌ ${escapeHtml(e?.message || e)}</td></tr>`;
  }
}

/* CSV */
function csvEscape(v) {
  const s = (v === null || v === undefined) ? "" : String(v);
  if (/[",\n]/.test(s)) return `"${s.replace(/"/g, '""')}"`;
  return s;
}

function downloadCsv(filename, rows) {
  const csv = rows.map(r => r.map(csvEscape).join(",")).join("\n");
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

btnExportCsv?.addEventListener("click", () => {
  const q = (search?.value || "").toLowerCase().trim();

  const filtered = !q ? members : members.filter(m => {
    const full = `${safe(m.firstName)} ${safe(m.lastName)}`.toLowerCase();
    return (
      full.includes(q) ||
      safe(m.email).toLowerCase().includes(q) ||
      safe(m.membershipNumber).toLowerCase().includes(q) ||
      safe(m.displayName).toLowerCase().includes(q) ||
      safe(m.id).toLowerCase().includes(q)
    );
  });

  const now = new Date();
  const stamp = now.toISOString().slice(0,19).replace(/[:T]/g, "-");
  const filename = `libro-soci_${currentStatus}_${q ? "search" : "all"}_${stamp}.csv`;

  const rows = [
    ["statusFiltro", currentStatus],
    ["search", q],
    ["exportAt", now.toISOString()],
    [],
    ["uid", "firstName", "lastName", "displayName", "email", "membershipNumber", "membershipValidUntil", "status", "phone", "city"]
  ];

  filtered.forEach(m => {
    rows.push([
      m.id,
      safe(m.firstName),
      safe(m.lastName),
      safe(m.displayName),
      safe(m.email),
      safe(m.membershipNumber),
      safe(m.membershipValidUntil),
      safe(m.status),
      safe(m.phone),
      safe(m.city)
    ]);
  });

  downloadCsv(filename, rows);
});

btnClose.addEventListener("click", () => dlg.close());

btnApprove.addEventListener("click", async () => {
  if (!currentMember) return;

  btnApprove.disabled = true;
  dlgMsg.textContent = "Approvazione in corso…";

  try {
    const token = await auth.currentUser.getIdToken(true);

    const r = await fetch(APPROVE_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": "Bearer " + token
      },
      body: JSON.stringify({ uid: currentMember.id })
    });

    const data = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(data?.error || "Approve failed");

    dlgMsg.textContent = "✅ Approvato. Aggiorno lista…";
    dlg.close();

    await load("pending");
  } catch (e) {
    dlgMsg.textContent = "❌ Errore: " + (e?.message || e);
  } finally {
    btnApprove.disabled = false;
  }
});

btnPayRenew?.addEventListener("click", async () => {
  if (!currentMember) return;

  const amt = Number(payAmount?.value || 0);
  if (!Number.isFinite(amt) || amt <= 0) {
    dlgMsg.textContent = "❌ Inserisci un importo valido.";
    return;
  }

  btnPayRenew.disabled = true;
  dlgMsg.textContent = "Registrazione pagamento + rinnovo in corso…";

  try {
    const token = await auth.currentUser.getIdToken(true);

    const r = await fetch(PAY_RENEW_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": "Bearer " + token
      },
      body: JSON.stringify({
        uid: currentMember.id,
        amount: amt,
        method: payMethod?.value || "cash",
        note: payNote?.value || ""
      })
    });

    const data = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(data?.error || "Pagamento/Rinnovo fallito");

    dlgMsg.textContent = `✅ OK. Nuova validità: ${data.newValidIso}`;
    dlg.close();

    await load(currentStatus);
  } catch (e) {
    dlgMsg.textContent = "❌ Errore: " + (e?.message || e);
  } finally {
    btnPayRenew.disabled = false;
  }
});

document.querySelectorAll("[data-status]").forEach(btn => {
  btn.addEventListener("click", () => load(btn.getAttribute("data-status")));
});

search.addEventListener("input", render);

// Boot
await requireAuth({ requireRole: "admin" });

$("adminEmail").textContent = auth.currentUser?.email || "—";
$("logoutBtn")?.addEventListener("click", (e) => { e.preventDefault(); logout(); });

load("pending");
