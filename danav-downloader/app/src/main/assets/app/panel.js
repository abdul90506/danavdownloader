/* Danav — browser overlay: download sheet (bottom), ad-block sheet (top), menu (dropdown). */
'use strict';
(function () {
  const { $, $$, call, svg, esc } = V;
  $$('[data-i]').forEach((el) => { el.innerHTML = svg(el.dataset.i); });
  V.loadState();

  let cur = null, page = {}, closing = 0;
  const P = { download: $('#dl'), adblock: $('#ad'), menu: $('#menu') };

  function open(which, d) {
    clearTimeout(closing);
    page = d;
    for (const k in P) P[k].hidden = k !== which;
    const top = (d.toolbar || 56) + 'px';
    $('#ad').style.top = top; $('#menu').style.top = top;
    $('#ad').style.maxHeight = `calc(100vh - ${top} - 40px)`;
    cur = which;
    if (which === 'download') openDownload(d);
    if (which === 'adblock') renderAd(d.ad || {});
    if (which === 'menu') { $('#mFwd').disabled = !d.canForward; $('#mDesk').checked = !!d.desktop; $('#mFab').checked = d.fab !== false; }
    document.body.classList.remove('in');
    requestAnimationFrame(() => requestAnimationFrame(() => document.body.classList.add('in')));
  }
  function close() {
    if (!cur) { call('panel', 'close', ''); return; }
    V.closeSheet();
    document.body.classList.remove('in');
    cur = null;
    closing = setTimeout(() => call('panel', 'close', ''), 220);
  }
  V.backStack.push(() => { close(); return true; });
  $('#pscrim').addEventListener('click', close);
  document.addEventListener('click', (e) => { if (e.target.closest('[data-close]')) close(); });

  // ---------------------------------------------------------------- download sheet
  const grab = V.grab($('#grab'), {
    onSignin: (u) => { call('panel', 'go', JSON.stringify({ url: u })); close(); },
    onChange: () => syncHint(),
  });
  let lastKey = '';
  function openDownload(d) {
    $('#dlHost').textContent = d.title && d.pageUrl ? d.title : d.host || 'This page';
    renderStreams(d);
    const key = d.pageUrl + '|' + (d.candidates || []).map((c) => c.url).join(',');
    if (!d.pageUrl && !(d.candidates || []).length) { grab.clear(); lastKey = ''; syncHint(); return; }
    // reuse results when nothing changed (reopening the sheet must not re-analyze)
    if (key === lastKey && (grab.count || grab.busy)) { grab.refresh(); syncHint(); return; }
    lastKey = key;
    grab.fromBrowser(d);
  }
  const KIND = { hls: 'HLS', dash: 'DASH', file: 'FILE' };
  function renderStreams(d) {
    const c = d.candidates || [];
    $('#streams').hidden = !c.length;
    $('#playing').hidden = !d.playing;
    $('#streamsT').textContent = c.length === 1 ? 'Detected video stream' : 'Detected video streams · ' + c.length;
    $('#streamList').innerHTML = c.map((x, i) => {
      const ext = x.kind === 'file' ? ((x.url.split('?')[0].match(/\.([a-z0-9]{2,4})$/i) || [])[1] || 'file').toUpperCase() : KIND[x.kind] || x.kind.toUpperCase();
      return `<div class="st"><span class="st-k ${x.kind !== 'file' ? 'm' : ''}">${esc(ext)}</span><span class="st-u">${esc(x.url.replace(/^https?:\/\//, ''))}</span>
        <button class="btn small sq" data-copy="${i}" aria-label="Copy">${svg('copy')}</button><button class="btn small" data-use="${i}">Get</button></div>`;
    }).join('');
  }
  $('#streamList').addEventListener('click', (e) => {
    const c = page.candidates || [];
    const cp = e.target.closest('[data-copy]');
    if (cp) { call('copy', c[+cp.dataset.copy].url); V.toast('Stream link copied'); return; }
    const u = e.target.closest('[data-use]');
    if (u) { lastKey = ''; grab.fromBrowser(page, c[+u.dataset.use]); $('#dl').scrollTo({ top: 0, behavior: 'smooth' }); }
  });
  function syncHint() {
    const h = $('#hint');
    if (grab.count || grab.busy) { h.hidden = true; return; }
    if (page.mse && !(page.candidates || []).length) { h.hidden = false; $('#hintT').textContent = 'This player streams through Media Source (blob:). Danav reads such pages with its site engine; if nothing is found, the site likely uses DRM.'; return; }
    if (!(page.candidates || []).length && !grab.hasProblem) { h.hidden = false; $('#hintT').textContent = 'Open a video and press play — streams appear here as soon as the player loads them.'; return; }
    h.hidden = true;
  }
  V.on('page', (d) => {
    const prevUrl = page.pageUrl;
    page = Object.assign(page, d);
    if (cur === 'download') {
      renderStreams(page);
      if (prevUrl !== d.pageUrl) openDownload(page);
      else syncHint();
    }
    if (cur === 'menu') { $('#mFwd').disabled = !d.canForward; }
    if (d.ad && cur === 'adblock') renderAd(d.ad);
  });
  V.on('job', (j) => grab.onJob(j));

  // ---------------------------------------------------------------- ad-block sheet
  let ad = {};
  const slider = V.adSlider($('#adSlider'), 2, (l) => {
    const all = $('#adAll').checked;
    call('panel', 'level', JSON.stringify({ level: l, all, reload: true }));
    V.toast(all ? `${V.AD_LEVELS[l].name} on every site · reloading` : `${V.AD_LEVELS[l].name} on ${ad.site || ad.host} · reloading`);
  });
  function renderAd(a) {
    ad = a;
    const l = a.level != null ? a.level : 2;
    slider.set(l);
    $('#adHost').textContent = a.site || a.host || 'This page';
    const custom = a.def != null && l !== a.def;
    $('#adSub').textContent = !a.host ? 'Default level for every site' : custom ? 'Custom level for this site (default: ' + V.AD_LEVELS[a.def].name + ')' : 'Using the default level';
    $('#adIc').innerHTML = svg(l === 0 ? 'shieldOff' : 'shieldOn');
    $('#ad .ad-site').classList.toggle('off', l === 0);
    $('#adIc').style.background = l === 0 ? 'var(--surface2)' : ''; $('#adIc').style.color = l === 0 ? 'var(--muted)' : '';
    $('#adPage').textContent = (a.page || 0).toLocaleString();
    $('#adTotal').textContent = V.compact(a.total || 0);
    $('#adDefault').hidden = !custom || !a.host;
    $('#adAll').checked = false;
    if (a.ready === false) $('#adSub').textContent = 'Loading filter lists…';
  }
  $('#adReload').addEventListener('click', () => { call('panel', 'reload', ''); close(); });
  $('#adDefault').addEventListener('click', () => { call('panel', 'level', JSON.stringify({ level: ad.def, all: false, reload: true })); });
  V.on('ad', (a) => { if (cur === 'adblock') renderAd(Object.assign({}, ad, a)); });

  // ---------------------------------------------------------------- menu
  $('#menu').addEventListener('click', (e) => {
    const b = e.target.closest('[data-m]'); if (!b) return;
    const m = b.dataset.m;
    if (m === 'fab') { const on = !$('#mFab').checked; $('#mFab').checked = on; call('panel', 'fab', JSON.stringify({ on })); setTimeout(close, 180); return; }
    if (m === 'desktop') { $('#mDesk').checked = !$('#mDesk').checked; setTimeout(() => { call('panel', 'desktop', ''); close(); }, 160); return; }
    if ((m === 'copy' || m === 'share' || m === 'external') && !page.pageUrl) { V.toast('Open a page first'); return; }
    call('panel', m, '');
    close();
  });

  // ---------------------------------------------------------------- native events
  V.on('open', (d) => { open(d.panel, d); });
  call('ready');
})();
