// /public/js/footer.js
(function injectFooter() {
  const footerHtml = `
    <footer class="footer">
      <div>Bike4City © – mobilità sostenibile, territorio, comunità</div>
      <div class="footer-links">
        <a href="/pages/privacy.html">Privacy</a>
        <span class="sep">·</span>
        <a href="/pages/note-legali.html">Note legali</a>
        <span class="sep">·</span>
        <a href="/pages/riconoscimenti.html">Riconoscimenti</a>
        <span class="sep">·</span>
        <a href="/pages/contatti.html">Contatti</a>
      </div>
    </footer>
  `.trim();

  // se la pagina ha già un footer, non fare doppioni
  if (document.querySelector("footer.footer")) return;

  // target preferito: un placeholder dedicato
  const placeholder = document.getElementById("siteFooter");
  if (placeholder) {
    placeholder.innerHTML = footerHtml;
    return;
  }

  // fallback: appende in fondo al body
  document.body.insertAdjacentHTML("beforeend", footerHtml);
})();
