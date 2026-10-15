/* Danav — main app: Home (link → download), Videos, Music, Downloads, Settings, music player. */
'use strict';
(function () {
  const { $, $$, call, callAsync, svg, esc } = V;
  const F = window.VFormats;
  const fillIcons = (r) => (r || document).querySelectorAll('[data-i]').forEach((el) => { if (!el.dataset.done) { el.innerHTML = svg(el.dataset.i); el.dataset.done = 1; } });
  fillIcons();

  // ================================================================ navigation
  const TITLES = { home: ['Danav', ''], videos: ['Videos', 'On this phone'], music: ['Music', 'On this phone'], downloads: ['Downloads', ''], settings: ['Settings', ''], private: ['Private', 'Encrypted · locked with your PIN'] };
  let view = 'home', prevView = 'home';
  const scrollPos = {};
  function show(v) {
    if (v === view) { $('#main').scrollTo({ top: 0, behavior: 'smooth' }); return; }
    scrollPos[view] = $('#main').scrollTop;
    if (v === 'settings') prevView = view;
    if (view === 'private' && window.VaultUI) VaultUI.leave();
    view = v;
    for (const s of ['home', 'videos', 'music', 'downloads', 'settings', 'private']) $('#view-' + s).hidden = s !== v;
    $$('.nav-b').forEach((b) => b.classList.toggle('on', b.dataset.view === v));
    document.body.classList.toggle('in-settings', v === 'settings');
    $('#hTitle').textContent = TITLES[v][0];
    $('#hSub').textContent = v === 'downloads' ? downloadsSub() : TITLES[v][1];
    $('#openSettings').innerHTML = v === 'settings' ? svg('close') : svg('settings');
    $('#main').scrollTop = scrollPos[v] || 0;
    if (v === 'videos') Videos.enter();
    if (v === 'music') Music.enter();
    if (v === 'downloads') renderDownloads();
    if (v === 'settings') renderSettings();
    if (v === 'home') renderHome();
    if (v === 'private' && window.VaultUI) VaultUI.enter();
  }
  function goBack() {
    if (Player.open) { Player.hide(); return true; }
    if (view === 'settings') { show(prevView === 'settings' ? 'home' : prevView); return true; }
    if (view === 'videos' && Videos.back()) return true;
    if (view === 'music' && Music.back()) return true;
    if (view === 'private' && window.VaultUI && VaultUI.back()) return true;
    if (view !== 'home') { show('home'); return true; }
    return false;
  }
  V.backStack.push(goBack);
  V.show = show;
  $$('.nav-b').forEach((b) => b.addEventListener('click', () => show(b.dataset.view)));
  $('#openSettings').addEventListener('click', () => (view === 'settings' ? goBack() : show('settings')));
  $('#openBrowser').addEventListener('click', () => openBrowser(''));
  $('#browseAny').addEventListener('click', () => openBrowser(''));
  document.addEventListener('click', (e) => {
    const g = e.target.closest('[data-go]'); if (!g) return;
    const t = g.dataset.go;
    if (t === 'settings-ad') { show('settings'); setTimeout(() => $('#settings-ad').scrollIntoView({ behavior: 'smooth' }), 50); }
    else show(t);
  });
  function openBrowser(url, mode) { call('openBrowser', url || '', mode || 'detect'); }


  // ================================================================ HOME
  const urlIn = $('#url');
  const grab = V.grab($('#grab'), {
    onBrowser: (u) => openBrowser(u),
    onSignin: (u) => openBrowser(u, 'signin'),
    onDownload: () => updateCounts(),
  });
  function syncField() { $('#clearUrl').hidden = !urlIn.value; $('#pasteBtn').hidden = !!urlIn.value; }
  urlIn.addEventListener('input', syncField);
  // pasting a link starts reading it right away — no extra tap on Download
  urlIn.addEventListener('paste', () => setTimeout(() => {
    const u = V.findUrl(urlIn.value);
    if (u && u !== grab.lastUrl) { urlIn.blur(); go(); }
  }, 0));
  urlIn.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); urlIn.blur(); go(); } });
  $('#clearUrl').addEventListener('click', () => { urlIn.value = ''; syncField(); grab.clear(); urlIn.focus(); });
  $('#pasteBtn').addEventListener('click', () => {
    const t = call('paste') || '';
    const u = V.findUrl(t);
    if (!u) { V.toast(t ? 'The clipboard doesn\'t contain a link' : 'The clipboard is empty'); return; }
    urlIn.value = u; syncField(); $('#clipChip').hidden = true; go();
  });
  $('#goBtn').addEventListener('click', () => { urlIn.blur(); go(); });
  $('#clipChip').addEventListener('click', () => {
    const u = $('#clipChip').dataset.url; $('#clipChip').hidden = true;
    localStorage.setItem('clipSeen', u);
    urlIn.value = u; syncField(); go();
  });
  function go() {
    const u = V.findUrl(urlIn.value);
    if (!u) { V.toast('Paste a video link first'); urlIn.focus(); return; }
    urlIn.value = u; syncField();
    grab.analyze(u, null);
  }

  const SITES = [
    ['YouTube', 'youtube', 'https://m.youtube.com/'], ['Instagram', 'instagram', 'https://www.instagram.com/'], ['Facebook', 'facebook', 'https://m.facebook.com/watch/'],
    ['TikTok', 'tiktok', 'https://www.tiktok.com/'], ['X', 'x', 'https://x.com/'], ['Reddit', 'reddit', 'https://www.reddit.com/r/videos/'],
    ['Vimeo', 'vimeo', 'https://vimeo.com/watch'],
  ];
  $('#quickSites').innerHTML = SITES.map(([n, m], i) => `<button class="site" data-site="${i}"><span class="mono">${V.brand(m)}</span><span class="site-n">${esc(n)}</span></button>`).join('')
    + `<button class="site" data-site="any"><span class="mono alt">${svg('globe')}</span><span class="site-n">Any site</span></button>`;
  $('#main').addEventListener('scroll', () => $('#top').classList.toggle('scrolled', $('#main').scrollTop > 4), { passive: true });
  $('#quickSites').addEventListener('click', (e) => {
    const b = e.target.closest('[data-site]'); if (!b) return;
    openBrowser(b.dataset.site === 'any' ? '' : SITES[+b.dataset.site][2]);
  });

  let ad = {};
  const homeSlider = V.adSlider($('#homeSlider'), 2, (l) => { call('setAdLevel', '', l); ad.def = l; renderAd(); V.toast('Ad blocker: ' + V.AD_LEVELS[l].name + ' on every site'); });
  const setSlider = V.adSlider($('#setSlider'), 2, (l) => { call('setAdLevel', '', l); ad.def = l; renderAd(); });
  function renderAd() {
    const l = ad.def != null ? ad.def : 2;
    homeSlider.set(l); setSlider.set(l);
    $('#adName').textContent = V.AD_LEVELS[l].name;
    $('.ad-ic').innerHTML = svg(l === 0 ? 'shieldOff' : 'shieldOn');
    $('.ad-card').classList.toggle('off', l === 0);
    $('#adTotal').textContent = V.compact(ad.total || 0);
    $('#adStats').textContent = (ad.total || 0).toLocaleString() + ' ads, trackers and pop-ups blocked';
    $('#adSites').textContent = ad.sites ? ad.sites + (ad.sites === 1 ? ' site has its own level' : ' sites have their own level') : 'No sites changed';
    $('#adResetSites').disabled = !ad.sites;
  }
  $('#adResetStats').addEventListener('click', () => { call('adReset', 'stats'); ad.total = 0; renderAd(); });
  $('#adResetSites').addEventListener('click', async () => {
    if (await V.confirm('Reset per-site levels?', 'Every site goes back to the default level.', 'Reset')) { call('adReset', 'sites'); ad.sites = 0; renderAd(); }
  });

  function renderHome() {
    const items = V.history.slice(0, 10);
    $('#recentWrap').hidden = !items.length;
    $('#recent').innerHTML = items.map((h) => {
      const img = h.thumbFile ? V.fileUrl(h.thumbFile) : h.thumb;
      const audio = /^audio/.test(h.mime || '');
      return `<button class="rc" data-id="${esc(h.id)}"><span class="rc-th">${img ? `<img src="${esc(img)}" alt="" loading="lazy" data-ph="${audio ? 'music' : 'video'}" onload="VB.imgFit(this)" onerror="VB.imgErr(this)">` : `<span class="ph">${svg(audio ? 'music' : 'video')}</span>`}${h.duration ? `<span class="tag">${V.dur(h.duration)}</span>` : ''}</span><span class="rc-t">${esc(h.title)}</span><span class="rc-m">${esc([h.label, V.bytes(h.size)].filter(Boolean).join(' · '))}</span></button>`;
    }).join('');
    $('#nDl').textContent = V.history.length ? String(V.history.length) : '';
    if (window.VaultUI) { const vs = VaultUI.state(); $('#nPriv').innerHTML = vs.unlocked ? String(vs.count || '') : vs.set ? svg('lock', 'lt-lock') : ''; }
    Videos.preloadCount(); Music.preloadCount();
  }
  $('#recent').addEventListener('click', (e) => { const b = e.target.closest('[data-id]'); if (b) V.openItem(b.dataset.id); });

  // ================================================================ media library helpers
  const thumbCache = new Map();    // "v123" → path | ''
  const thumbWanted = new Set();
  let thumbQ = [], thumbT = 0;
  const io = new IntersectionObserver((ents) => {
    for (const e of ents) {
      if (!e.isIntersecting) continue;
      io.unobserve(e.target);
      const el = e.target, key = el.dataset.tk;
      if (thumbCache.has(key)) { applyThumb(el, thumbCache.get(key)); continue; }
      if (thumbWanted.has(key)) continue;
      thumbWanted.add(key);
      thumbQ.push({ k: key[0], id: +key.slice(1), mod: +el.dataset.mod || 0 });
      if (!thumbT) thumbT = setTimeout(flushThumbs, 40);
    }
  }, { root: $('#main'), rootMargin: '400px 0px' });
  function flushThumbs() { thumbT = 0; if (!thumbQ.length) return; call('thumbs', JSON.stringify(thumbQ.splice(0, 40))); if (thumbQ.length) thumbT = setTimeout(flushThumbs, 40); }
  function applyThumb(el, path) {
    if (!path) { el.classList.add('none'); return; }
    if (el.querySelector('img')) return;
    const img = new Image();
    img.alt = ''; img.decoding = 'async';
    img.onload = () => VB.imgFit(img);
    img.src = V.fileUrl(path);
    el.prepend(img);
  }
  V.on('thumb', (d) => {
    const key = d.k + d.id;
    thumbCache.set(key, d.path || '');
    thumbWanted.delete(key);
    $$(`[data-tk="${key}"]`).forEach((el) => applyThumb(el, d.path || ''));
    if (d.k === 'a' && d.path && Player.state.item && Player.state.item.id === d.id && !Player.state.item.art) { Player.state.item.art = d.path; Player.render(); }
  });
  function watchThumbs(root) { root.querySelectorAll('[data-tk]').forEach((el) => { const c = thumbCache.get(el.dataset.tk); if (c !== undefined) applyThumb(el, c); else io.observe(el); }); }

  /** Render a long list in chunks as the user scrolls. */
  function chunked(container, items, renderOne, size) {
    size = size || 48;
    container.innerHTML = '';
    let i = 0;
    const sentinel = document.createElement('div');
    sentinel.className = 'sentinel';
    const more = () => {
      const frag = document.createElement('div');
      const at = i;
      frag.innerHTML = items.slice(i, i + size).map((x, k) => renderOne(x, at + k)).join('');
      i += size;
      const nodes = Array.from(frag.children);
      nodes.forEach((n) => container.insertBefore(n, sentinel));
      nodes.forEach((n) => watchThumbs(n));
      if (i >= items.length) sio.unobserve(sentinel);
    };
    const sio = new IntersectionObserver((e) => { if (e[0].isIntersecting) more(); }, { root: $('#main'), rootMargin: '800px 0px' });
    container.appendChild(sentinel);
    more();
    if (i < items.length) sio.observe(sentinel);
  }

  let perm = 'denied';
  function permBox(el, kind) {
    el.hidden = false;
    const blocked = localStorage.getItem('permBlocked') === '1';
    el.innerHTML = `<div class="empty-ic">${svg(kind === 'music' ? 'music' : 'video')}</div>
      <div class="empty-t">${kind === 'music' ? 'Your music, in one place' : 'Your videos, in one place'}</div>
      <div class="empty-s">Allow Danav to read the ${kind === 'music' ? 'songs and audio' : 'videos'} on this phone to play them in Danav's player. Nothing leaves your phone.</div>
      <button class="btn primary" data-perm="${blocked ? 'settings' : 'ask'}">${blocked ? 'Open app settings' : 'Allow access'}</button>`;
    el.querySelector('[data-perm]').onclick = (e) => { if (e.target.closest('[data-perm]').dataset.perm === 'settings') call('openAppSettings'); else call('requestMediaPerm'); };
  }
  V.on('perm', (d) => {
    perm = d.perm;
    if (d.blocked && perm !== 'granted') localStorage.setItem('permBlocked', '1');
    if (perm !== 'denied') { localStorage.removeItem('permBlocked'); Videos.reload(); Music.reload(); }
  });
  V.on('resume', (d) => {
    const was = perm; perm = d.perm;
    if (was === 'denied' && perm !== 'denied') { localStorage.removeItem('permBlocked'); Videos.reload(); Music.reload(); }
  });
  function sortMenu(cur, list, onPick) {
    V.openSheet(`<div class="sheet-h">Sort by</div>${list.map(([k, l]) => V.opt(k, k === cur.k ? 'check' : '', l, k === cur.k ? (cur.asc ? 'Ascending' : 'Descending') : '', k === cur.k ? 'sel' : '')).join('')}`,
      (k) => { onPick(k === cur.k ? { k, asc: !cur.asc } : { k, asc: k === 'name' || k === 'artist' || k === 'album' }); });
  }
  function fmtName(v) {
    const ext = ((v.name || '').match(/\.([a-z0-9]{2,5})$/i) || [])[1];
    const m = { 'x-matroska': 'MKV', quicktime: 'MOV', 'x-msvideo': 'AVI', '3gpp': '3GP', 'x-flv': 'FLV', 'mp2t': 'TS', mpeg: 'MPEG', 'x-ms-wmv': 'WMV' };
    const sub = (v.mime || '').split('/')[1] || '';
    return (ext ? ext.toUpperCase() : m[sub] || sub.toUpperCase()) + (v.mime ? '  ·  ' + v.mime : '');
  }
  const cmp = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

  // ================================================================ VIDEOS
  const Videos = (() => {
    let items = null, loading = false, seg = 'all', folder = null, q = '';
    let sort = JSON.parse(localStorage.getItem('vSort') || '{"k":"date","asc":false}');
    let mode = localStorage.getItem('vMode') || 'grid';
    async function load(force) {
      if (loading || (items && !force)) return;
      perm = call('mediaPerm') || 'denied';
      if (perm === 'denied') { items = null; render(); return; }
      loading = true;
      if (!items) $('#vList').innerHTML = '<div class="loading-row"><span class="spinner"></span>Reading your videos…</div>';
      items = await callAsync('media', 'video');
      loading = false;
      $('#nVideos').textContent = items.length;
      render();
    }
    function sorted(list) {
      const k = sort.k, s = sort.asc ? 1 : -1;
      return list.slice().sort((a, b) => s * (k === 'name' ? cmp((a.title || a.name).toLowerCase(), (b.title || b.name).toLowerCase()) : k === 'size' ? a.size - b.size : k === 'dur' ? a.dur - b.dur : a.added - b.added));
    }
    function visible() {
      let list = items || [];
      if (folder) list = list.filter((v) => v.bucket === folder.bucket);
      if (q) { const t = q.toLowerCase(); list = list.filter((v) => (v.title + ' ' + v.name + ' ' + v.folder).toLowerCase().includes(t)); }
      return sorted(list);
    }
    function tile(v) {
      const r = V.res(v.w, v.h);
      if (mode === 'list') {
        return `<div class="vrow" data-id="${v.id}"><div class="vth" data-tk="v${v.id}" data-mod="${v.mod}"><span class="ph">${svg('video')}</span>${v.dur ? `<span class="tag">${V.ms(v.dur)}</span>` : ''}</div>
          <div class="vrow-i"><div class="vt">${esc(v.title || v.name)}</div><div class="vm">${esc([r, V.bytes(v.size), v.folder].filter(Boolean).join(' · '))}</div><div class="vm">${esc(new Date(v.added * 1000).toLocaleDateString())}</div></div>
          <button class="icon-btn vmore" data-more="${v.id}" aria-label="More">${svg('more')}</button></div>`;
      }
      return `<div class="vtile" data-id="${v.id}"><div class="vth" data-tk="v${v.id}" data-mod="${v.mod}"><span class="ph">${svg('video')}</span>${r ? `<span class="tag tl">${r}</span>` : ''}${v.dur ? `<span class="tag">${V.ms(v.dur)}</span>` : ''}</div>
        <div class="vtile-i"><div class="vt">${esc(v.title || v.name)}</div><div class="vm-row"><span class="vm">${esc([V.bytes(v.size), V.ago(v.added * 1000)].filter(Boolean).join(' · '))}</span><button class="icon-btn vmore" data-more="${v.id}" aria-label="More">${svg('more')}</button></div></div></div>`;
    }
    function folders() {
      const map = new Map();
      for (const v of items || []) {
        let f = map.get(v.bucket);
        if (!f) { f = { bucket: v.bucket, name: v.folder || 'Internal storage', n: 0, size: 0, first: v, latest: 0 }; map.set(v.bucket, f); }
        f.n++; f.size += v.size || 0;
        if (v.added > f.latest) { f.latest = v.added; f.first = v; }
      }
      let list = Array.from(map.values());
      if (q) list = list.filter((f) => f.name.toLowerCase().includes(q.toLowerCase()));
      return list.sort((a, b) => b.latest - a.latest);
    }
    function render() {
      const list = $('#vList');
      $('#vPerm').hidden = true; $('#vEmpty').hidden = true;
      $('#vSub').hidden = !folder;
      $('#vSeg').hidden = !!folder;
      $('#vMode').hidden = seg === 'folders' && !folder;
      $('#vMode').innerHTML = svg(mode === 'grid' ? 'list' : 'grid');
      $('#view-videos .lib-bar').hidden = perm === 'denied';
      if (perm === 'denied') { list.innerHTML = ''; $('#vCount').textContent = ''; permBox($('#vPerm'), 'video'); return; }
      if (!items) return;
      if (seg === 'folders' && !folder) {
        const fs = folders();
        $('#vCount').textContent = fs.length + (fs.length === 1 ? ' folder' : ' folders');
        list.className = 'flist';
        chunked(list, fs, (f) => `<div class="frow" data-bucket="${esc(f.bucket)}"><span class="fic">${svg('folder')}</span>
          <div class="vrow-i"><div class="vt">${esc(f.name)}</div><div class="vm">${f.n} video${f.n === 1 ? '' : 's'} · ${V.bytes(f.size)}</div></div><span class="chev">${svg('chev')}</span></div>`);
        if (!fs.length) empty();
        return;
      }
      const vs = visible();
      if (folder) { $('#vSubT').textContent = folder.name; $('#vSubS').textContent = vs.length + ' videos · ' + V.bytes(vs.reduce((s, v) => s + (v.size || 0), 0)); }
      $('#vCount').textContent = folder ? '' : vs.length + (vs.length === 1 ? ' video' : ' videos') + (q ? ' found' : '') + ' · ' + sortLabel();
      list.className = mode === 'grid' ? 'vgrid' : 'vlist';
      chunked(list, vs, tile, mode === 'grid' ? 40 : 60);
      if (!vs.length) empty();
    }
    function sortLabel() { return { date: 'Newest', name: 'Name', size: 'Size', dur: 'Length' }[sort.k] + (sort.k === 'date' ? (sort.asc ? ' last' : ' first') : sort.asc ? ' ↑' : ' ↓'); }
    function empty() {
      const e = $('#vEmpty'); e.hidden = false;
      e.innerHTML = `<div class="empty-ic">${svg('video')}</div><div class="empty-t">${q ? 'No videos match “' + esc(q) + '”' : 'No videos on this phone'}</div><div class="empty-s">${q ? 'Try another word.' : 'Videos you record or download appear here.'}</div>`;
    }
    function play(id) {
      const vs = visible();
      const idx = Math.max(0, vs.findIndex((v) => v.id === id));
      call('playVideos', JSON.stringify({ index: idx, items: vs.map((v) => ({ uri: v.uri, title: v.title || v.name, sub: [V.res(v.w, v.h), v.folder].filter(Boolean).join(' · '), path: v.path })) }));
    }
    function details(id) {
      const v = (items || []).find((x) => x.id === id); if (!v) return;
      const th = thumbCache.get('v' + v.id);
      const rows = [['File', v.name], ['Folder', v.path.replace(/[^/]*$/, '') || v.folder], ['Resolution', v.w && v.h ? `${v.w} × ${v.h}  (${V.res(v.w, v.h)})` : ''], ['Length', V.ms(v.dur)],
        ['Size', V.bytes(v.size)], ['Format', fmtName(v)], ['Added', new Date(v.added * 1000).toLocaleString()]].filter((r) => r[1]);
      V.openSheet(`<div class="det-th">${th ? `<img src="${esc(V.fileUrl(th))}" alt="">` : `<span class="ph">${svg('video')}</span>`}<button class="det-play" data-act="play">${svg('play')}</button></div>
        <div class="sheet-t">${esc(v.title || v.name)}</div>
        <div class="det">${rows.map(([k, x]) => `<div><span>${k}</span><b>${esc(x)}</b></div>`).join('')}</div>
        <div class="sheet-sep"></div>
        ${V.opt('play', 'play', 'Play')}${V.opt('audio', 'headphones', 'Play as audio', 'Listen with the screen off')}${V.opt('share', 'share', 'Share')}${V.opt('with', 'external', 'Open with another app')}
        ${V.opt('private', 'private', 'Move to Private', 'Encrypt and hide behind your PIN')}
        <div class="sheet-sep"></div>${V.opt('delete', 'trash', 'Delete from phone', '', 'danger')}`, (a) => {
        if (a === 'play') play(v.id);
        else if (a === 'private') { moving = v; VaultUI.moveIn([v.uri]); }
        else if (a === 'audio') call('music', 'queue', JSON.stringify({ items: [{ uri: v.uri, title: v.title || v.name, artist: v.folder, art: th || '', dur: v.dur }] }));
        else if (a === 'share') call('shareMedia', v.uri, v.mime || 'video/*');
        else if (a === 'with') call('openWith', v.uri, v.mime || 'video/*');
        else if (a === 'delete') del(v);
      });
    }
    let deleting = null, moving = null;
    V.on('vaultRemoved', (d) => {
      if (moving && d.ok) { items = (items || []).filter((x) => x !== moving); $('#nVideos').textContent = items.length; render(); }
      moving = null;
    });
    async function del(v) {
      if (!(await V.confirm('Delete this video?', v.name + ' will be removed from your phone.', 'Delete', true))) return;
      deleting = v; call('deleteMedia', JSON.stringify([v.uri]));
    }
    V.on('deleted', (d) => {
      if (deleting && d.ok) { items = (items || []).filter((x) => x !== deleting); $('#nVideos').textContent = items.length; render(); V.toast('Deleted'); }
      deleting = null;
    });
    $('#vList').addEventListener('click', (e) => {
      const more = e.target.closest('[data-more]'); if (more) { details(+more.dataset.more); return; }
      const f = e.target.closest('[data-bucket]');
      if (f) { const fs = folders().find((x) => x.bucket === f.dataset.bucket); folder = fs; $('#main').scrollTop = 0; render(); return; }
      const t = e.target.closest('[data-id]'); if (t) play(+t.dataset.id);
    });
    let pressT;
    $('#vList').addEventListener('touchstart', (e) => { const t = e.target.closest('[data-id]'); if (!t) return; pressT = setTimeout(() => { pressT = 0; details(+t.dataset.id); }, 550); }, { passive: true });
    ['touchend', 'touchmove', 'touchcancel'].forEach((ev) => $('#vList').addEventListener(ev, () => clearTimeout(pressT), { passive: true }));
    $('#vList').addEventListener('contextmenu', (e) => e.preventDefault());
    $('#vSeg').addEventListener('click', (e) => { const b = e.target.closest('[data-s]'); if (!b) return; seg = b.dataset.s; $$('#vSeg button').forEach((x) => x.classList.toggle('on', x === b)); folder = null; render(); });
    $('#vSubBack').addEventListener('click', () => { folder = null; render(); });
    $('#vSubPlay').addEventListener('click', () => { const vs = visible(); if (vs.length) play(vs[0].id); });
    let qT;
    $('#vSearch').addEventListener('input', () => { clearTimeout(qT); qT = setTimeout(() => { q = $('#vSearch').value.trim(); render(); }, 180); });
    $('#vSort').addEventListener('click', () => sortMenu(sort, [['date', 'Date added'], ['name', 'Name'], ['size', 'Size'], ['dur', 'Length']], (s) => { sort = s; localStorage.setItem('vSort', JSON.stringify(s)); render(); }));
    $('#vMode').addEventListener('click', () => { mode = mode === 'grid' ? 'list' : 'grid'; localStorage.setItem('vMode', mode); render(); });
    return {
      enter() { load(false); if (items) render(); },
      reload() { items = null; load(true); },
      back() { if (folder) { folder = null; render(); return true; } if (q) { q = ''; $('#vSearch').value = ''; render(); return true; } return false; },
      preloadCount() { if (!items && !loading && (call('mediaPerm') || 'denied') !== 'denied') load(false); },
    };
  })();

  // ================================================================ MUSIC
  const Music = (() => {
    let items = null, loading = false, seg = 'songs', sub = null, q = '';
    let sort = JSON.parse(localStorage.getItem('mSort') || '{"k":"date","asc":false}');
    async function load(force) {
      if (loading || (items && !force)) return;
      perm = call('mediaPerm') || 'denied';
      if (perm === 'denied') { items = null; render(); return; }
      loading = true;
      if (!items) $('#mList').innerHTML = '<div class="loading-row"><span class="spinner"></span>Reading your music…</div>';
      items = await callAsync('media', 'audio');
      loading = false;
      $('#nMusic').textContent = items.length;
      render();
    }
    function sorted(list) {
      const k = sort.k, s = sort.asc ? 1 : -1;
      return list.slice().sort((a, b) => s * (k === 'name' ? cmp(a.title.toLowerCase(), b.title.toLowerCase()) : k === 'artist' ? cmp((a.artist || '~').toLowerCase(), (b.artist || '~').toLowerCase()) : k === 'dur' ? a.dur - b.dur : a.added - b.added));
    }
    function visible() {
      let list = items || [];
      if (sub) list = list.filter(sub.filter);
      if (q) { const t = q.toLowerCase(); list = list.filter((m) => (m.title + ' ' + m.artist + ' ' + m.album).toLowerCase().includes(t)); }
      if (sub && sub.kind === 'album') return list.slice().sort((a, b) => (a.track - b.track) || cmp(a.title, b.title));
      return sorted(list);
    }
    function songRow(m, i) {
      const cur = Player.state.item && Player.state.item.uri === m.uri;
      return `<div class="srow ${cur ? 'cur' : ''}" data-i="${i}"><div class="sart" data-tk="a${m.id}" data-mod="${m.mod}"><span class="ph">${svg('music')}</span></div>
        <div class="srow-i"><div class="vt">${esc(m.title)}</div><div class="vm">${esc([m.artist || 'Unknown artist', V.ms(m.dur)].join(' · '))}</div></div>
        ${cur ? `<span class="eq ${Player.state.playing ? 'on' : ''}"><i></i><i></i><i></i></span>` : ''}<button class="icon-btn vmore" data-more="${i}" aria-label="More">${svg('more')}</button></div>`;
    }
    function groups(key) {
      const map = new Map();
      for (const m of items || []) {
        const k = key === 'album' ? m.albumId + '|' + m.album : key === 'artist' ? (m.artist || 'Unknown artist') : (m.folder || 'Music');
        let g = map.get(k);
        if (!g) { g = { key: k, name: key === 'album' ? m.album || 'Unknown album' : k, artist: m.artist, n: 0, first: m, dur: 0, artists: new Set() }; map.set(k, g); }
        g.n++; g.dur += m.dur || 0; if (m.artist) g.artists.add(m.artist);
      }
      let list = Array.from(map.values());
      list.forEach((g) => { if (g.artists.size > 1) g.artist = 'Various artists'; });
      if (q) list = list.filter((g) => (g.name + ' ' + (g.artist || '')).toLowerCase().includes(q.toLowerCase()));
      return list.sort((a, b) => cmp(a.name.toLowerCase(), b.name.toLowerCase()));
    }
    let shown = [];
    function render() {
      const list = $('#mList');
      $('#mPerm').hidden = true; $('#mEmpty').hidden = true;
      $('#mSub').hidden = !sub; $('#mSeg').hidden = !!sub;
      $('#view-music .lib-bar').hidden = perm === 'denied';
      if (perm === 'denied') { list.innerHTML = ''; $('#mPlayBar').hidden = true; permBox($('#mPerm'), 'music'); return; }
      if (!items) { $('#mPlayBar').hidden = true; return; }
      if (!sub && seg !== 'songs') {
        $('#mPlayBar').hidden = true;
        const key = seg === 'albums' ? 'album' : seg === 'artists' ? 'artist' : 'folder';
        const gs = groups(key);
        if (key === 'album') {
          list.className = 'agrid';
          chunked(list, gs, (g) => `<div class="atile" data-g="${esc(g.key)}"><div class="aart" data-tk="a${g.first.id}" data-mod="${g.first.mod}"><span class="ph">${svg('album')}</span></div><div class="vt">${esc(g.name)}</div><div class="vm">${esc((g.artist || 'Unknown artist') + ' · ' + g.n)}</div></div>`);
        } else {
          list.className = 'flist';
          chunked(list, gs, (g) => `<div class="frow" data-g="${esc(g.key)}"><span class="fic">${svg(key === 'artist' ? 'user' : 'folder')}</span>
            <div class="vrow-i"><div class="vt">${esc(g.name)}</div><div class="vm">${g.n} song${g.n === 1 ? '' : 's'} · ${V.ms(g.dur)}</div></div><span class="chev">${svg('chev')}</span></div>`);
        }
        if (!gs.length) empty();
        return;
      }
      shown = visible();
      $('#mPlayBar').hidden = !shown.length;
      $('#mCount').textContent = shown.length + (shown.length === 1 ? ' song' : ' songs') + (sub ? ' · ' + V.ms(shown.reduce((s, m) => s + (m.dur || 0), 0)) : '');
      if (sub) { $('#mSubT').textContent = sub.name; $('#mSubS').textContent = sub.sub || ''; }
      list.className = 'slist';
      chunked(list, shown, songRow, 80);
      if (!shown.length) empty();
    }
    function empty() {
      const e = $('#mEmpty'); e.hidden = false;
      e.innerHTML = `<div class="empty-ic">${svg('music')}</div><div class="empty-t">${q ? 'Nothing matches “' + esc(q) + '”' : 'No music on this phone'}</div><div class="empty-s">${q ? 'Try another word.' : 'Songs and audio you download (Audio only) appear here.'}</div>`;
    }
    function item(m) { return { uri: m.uri, title: m.title, artist: m.artist, album: m.album, art: thumbCache.get('a' + m.id) || '', dur: m.dur, id: m.id, mod: m.mod }; }
    function playList(list, index, shuffle) {
      if (!list.length) return;
      const s = Math.max(0, index - 300), e = Math.min(list.length, s + 600);
      call('music', 'queue', JSON.stringify({ items: list.slice(s, e).map(item), index: index - s, shuffle: !!shuffle }));
      // art for the queue: make sure thumbnails exist (notification + lock screen artwork)
      const need = list.slice(s, e).filter((m) => !thumbCache.has('a' + m.id)).slice(0, 60).map((m) => ({ k: 'a', id: m.id, mod: m.mod }));
      if (need.length) call('thumbs', JSON.stringify(need));
    }
    function songMenu(i) {
      const m = shown[i]; if (!m) return;
      const th = thumbCache.get('a' + m.id);
      V.openSheet(`<div class="det-head"><div class="sart lg">${th ? `<img src="${esc(V.fileUrl(th))}" alt="">` : `<span class="ph">${svg('music')}</span>`}</div><div><div class="sheet-t">${esc(m.title)}</div><div class="sheet-s">${esc([m.artist, m.album].filter(Boolean).join(' · '))}</div></div></div>
        ${V.opt('play', 'play', 'Play')}${V.opt('next', 'queue', 'Play next')}${V.opt('add', 'plus', 'Add to queue')}
        ${m.album ? V.opt('album', 'album', 'Go to album', m.album) : ''}${m.artist ? V.opt('artist', 'user', 'Go to artist', m.artist) : ''}
        ${V.opt('share', 'share', 'Share')}${V.opt('info', 'info', 'Details', [V.ms(m.dur), V.bytes(m.size), (m.mime || '').replace('audio/', '').toUpperCase()].filter(Boolean).join(' · '))}
        ${V.opt('private', 'private', 'Move to Private', 'Encrypt and hide behind your PIN')}
        <div class="sheet-sep"></div>${V.opt('delete', 'trash', 'Delete from phone', '', 'danger')}`, (a) => {
        if (a === 'play') playList(shown, i);
        else if (a === 'private') { moving = m; VaultUI.moveIn([m.uri]); }
        else if (a === 'next' || a === 'add') { call('music', 'add', JSON.stringify({ item: item(m), next: a === 'next' })); V.toast(a === 'next' ? 'Plays next' : 'Added to queue'); }
        else if (a === 'album') openGroup('album', m.albumId + '|' + m.album);
        else if (a === 'artist') openGroup('artist', m.artist);
        else if (a === 'share') call('shareMedia', m.uri, m.mime || 'audio/*');
        else if (a === 'info') V.toast(m.path);
        else if (a === 'delete') del(m);
      });
    }
    let deleting = null, moving = null;
    V.on('vaultRemoved', (d) => {
      if (moving && d.ok) { items = (items || []).filter((x) => x !== moving); $('#nMusic').textContent = items.length; render(); }
      moving = null;
    });
    async function del(m) {
      if (!(await V.confirm('Delete this song?', m.title + ' will be removed from your phone.', 'Delete', true))) return;
      deleting = m; call('deleteMedia', JSON.stringify([m.uri]));
    }
    V.on('deleted', (d) => {
      if (deleting && d.ok) { items = (items || []).filter((x) => x !== deleting); $('#nMusic').textContent = items.length; render(); V.toast('Deleted'); }
      deleting = null;
    });
    function openGroup(kind, key) {
      const g = groups(kind).find((x) => x.key === key) || { name: key, artist: '' };
      sub = kind === 'album' ? { kind, name: g.name, sub: g.artist || '', filter: (m) => m.albumId + '|' + m.album === key }
        : kind === 'artist' ? { kind, name: g.name, sub: 'Artist', filter: (m) => (m.artist || 'Unknown artist') === key }
          : { kind, name: g.name, sub: 'Folder', filter: (m) => (m.folder || 'Music') === key };
      $('#main').scrollTop = 0;
      render();
    }
    $('#mList').addEventListener('click', (e) => {
      const more = e.target.closest('[data-more]'); if (more) { songMenu(+more.dataset.more); return; }
      const g = e.target.closest('[data-g]');
      if (g) { openGroup(seg === 'albums' ? 'album' : seg === 'artists' ? 'artist' : 'folder', g.dataset.g); return; }
      const r = e.target.closest('.srow'); if (r) playList(shown, +r.dataset.i);
    });
    $('#mSeg').addEventListener('click', (e) => { const b = e.target.closest('[data-s]'); if (!b) return; seg = b.dataset.s; $$('#mSeg button').forEach((x) => x.classList.toggle('on', x === b)); sub = null; render(); });
    $('#mSubBack').addEventListener('click', () => { sub = null; render(); });
    $('#mPlayAll').addEventListener('click', () => playList(shown, 0, false));
    $('#mShuffle').addEventListener('click', () => playList(shown, Math.floor(Math.random() * shown.length), true));
    let qT;
    $('#mSearch').addEventListener('input', () => { clearTimeout(qT); qT = setTimeout(() => { q = $('#mSearch').value.trim(); render(); }, 180); });
    $('#mSort').addEventListener('click', () => sortMenu(sort, [['date', 'Date added'], ['name', 'Title'], ['artist', 'Artist'], ['dur', 'Length']], (s) => { sort = s; localStorage.setItem('mSort', JSON.stringify(s)); render(); }));
    return {
      enter() { load(false); if (items) render(); },
      reload() { items = null; load(true); },
      back() { if (sub) { sub = null; render(); return true; } if (q) { q = ''; $('#mSearch').value = ''; render(); return true; } return false; },
      preloadCount() { if (!items && !loading && (call('mediaPerm') || 'denied') !== 'denied') load(false); },
      refreshCurrent() { if (view === 'music' && items && (seg === 'songs' || sub)) { $$('#mList .srow').forEach((r) => { const m = shown[+r.dataset.i]; const cur = m && Player.state.item && Player.state.item.uri === m.uri; if (cur !== r.classList.contains('cur')) r.outerHTML = songRow(m, +r.dataset.i); }); watchThumbs($('#mList')); $$('#mList .eq').forEach((e) => e.classList.toggle('on', !!Player.state.playing)); } },
    };
  })();

  // ================================================================ MUSIC PLAYER (mini + full)
  const Player = (() => {
    const P = { state: {}, queue: [], open: false };
    let dragging = false;
    function art(el, it, big) {
      const p = it && it.art;
      const key = it ? (p || '') + '|' + it.uri : '';
      if (el.dataset.key === key) return;
      el.dataset.key = key;
      el.innerHTML = p ? `<img src="${esc(V.fileUrl(p))}" alt="" onerror="this.remove()">` : '';
      if (!p) el.innerHTML = `<span class="ph">${svg('music', big ? 'xl' : '')}</span>`;
      if (big) { const bg = $('#npBg'); bg.style.backgroundImage = p ? `url("${V.fileUrl(p)}")` : ''; bg.classList.toggle('on', !!p); }
    }
    // only touch the DOM when the icon really changes — replacing it under a finger loses the tap
    const setIc = (el, name) => { if (el.dataset.ic !== name) { el.innerHTML = svg(name); el.dataset.ic = name; } };
    const setTxt = (el, t) => { if (el.textContent !== t) el.textContent = t; };
    function fillArt(it) {
      if (!it || it.art || !it.id) return;
      const k = 'a' + it.id, c = thumbCache.get(k);
      if (c) { it.art = c; return; }
      if (!thumbCache.has(k) && !thumbWanted.has(k)) { thumbWanted.add(k); call('thumbs', JSON.stringify([{ k: 'a', id: it.id, mod: it.mod || 0 }])); }
    }
    function render() {
      const s = P.state;
      const active = !!s.active;
      fillArt(s.item);
      $('#mini').hidden = !active;
      document.body.classList.toggle('has-mini', active);
      if (!active) { if (P.open) hide(); return; }
      const it = s.item || {};
      const artist = it.artist || 'Unknown artist';
      setTxt($('#miniT'), it.title || ''); setTxt($('#miniA'), artist);
      setIc($('#miniPlay'), s.want && !s.ended ? 'pause' : 'play');
      $('#miniP').style.width = s.dur ? Math.min(100, (s.pos / s.dur) * 100) + '%' : '0';
      art($('#miniArt'), it);
      if (!P.open) return;
      setTxt($('#npT'), it.title || ''); setTxt($('#npA'), artist);
      setTxt($('#npPos'), s.count > 1 ? `${s.index + 1} of ${s.count}` : '');
      art($('#npArt'), it, true);
      setIc($('#npPlay'), s.want && !s.ended ? 'pause' : 'play');
      $('#npPlay').classList.toggle('buf', !!s.buffering);
      $('#np').classList.toggle('paused', !(s.want && !s.ended));
      $('#npShuffle').classList.toggle('on', !!s.shuffle);
      $('#npRepeat').classList.toggle('on', s.repeat > 0);
      setIc($('#npRepeat'), s.repeat === 1 ? 'repeat1' : 'repeat');
      // speed / sleep only show up when they're active (tap to change)
      const tags = [];
      $('#npFx').classList.toggle('on', !!s.fx && s.fx !== 'off');
      if (s.fx && s.fx !== 'off') tags.push(`<button class="np-tag" data-tag="fx">${svg('eq')}${FX[s.fx] || ''}</button>`);
      else if (s.speed && Math.abs(s.speed - 1) > 0.01) tags.push(`<button class="np-tag" data-tag="speed">${svg('speed')}${+(s.speed).toFixed(2)}×</button>`);
      if (s.sleep) tags.push(`<button class="np-tag" data-tag="sleep">${svg('timer')}${s.sleep > 0 ? 'Stops in ' + Math.ceil(s.sleep / 60000) + ' min' : 'Stops after this song'}</button>`);
      const th = tags.join('');
      if ($('#npTags').dataset.h !== th) { $('#npTags').innerHTML = th; $('#npTags').dataset.h = th; }
      $('#npErr').hidden = !s.error; $('#npErr').textContent = s.error ? s.error + ' — skipping' : '';
      if (!dragging) setTl(s.pos, s.dur, s.buf);
      $('#npPrev').disabled = false;
      $('#npNext').disabled = s.hasNext === false;
    }
    function setTl(pos, dur, buf) {
      const f = dur ? Math.min(1, pos / dur) : 0;
      $('#npTl .tl-fill').style.width = f * 100 + '%';
      $('#npTl .tl-thumb').style.left = f * 100 + '%';
      $('#npTl .tl-buf').style.width = (dur ? Math.min(1, (buf || 0) / dur) : 0) * 100 + '%';
      $('#npCur').textContent = V.ms(pos); $('#npDur').textContent = V.ms(dur);
    }
    const cmd = (c, a) => call('music', c, a ? JSON.stringify(a) : '');
    function show() {
      P.open = true;
      const np = $('#np'); np.hidden = false;
      requestAnimationFrame(() => np.classList.add('in'));
      render();
    }
    function hide() {
      P.open = false;
      const np = $('#np'); np.classList.remove('in');
      setTimeout(() => { if (!P.open) np.hidden = true; }, 260);
    }
    $('#mini').addEventListener('click', (e) => { if (e.target.closest('button')) return; show(); });
    $('#miniPlay').addEventListener('click', () => cmd('toggle'));
    $('#miniNext').addEventListener('click', () => cmd('next'));
    $('#miniClose').addEventListener('click', () => { cmd('stop'); $('#mini').hidden = true; document.body.classList.remove('has-mini'); });
    $('#npClose').addEventListener('click', hide);
    $('#npPlay').addEventListener('click', () => cmd('toggle'));
    $('#npPrev').addEventListener('click', () => cmd('prev'));
    $('#npNext').addEventListener('click', () => cmd('next'));
    $('#npShuffle').addEventListener('click', () => { cmd('shuffle', { on: !P.state.shuffle }); V.toast(P.state.shuffle ? 'Shuffle off' : 'Shuffle on'); });
    $('#npRepeat').addEventListener('click', () => { const r = P.state.repeat || 0, m = r === 0 ? 2 : r === 2 ? 1 : 0; cmd('repeat', { mode: m }); V.toast(m === 2 ? 'Repeat all' : m === 1 ? 'Repeat this song' : 'Repeat off'); });
    const FX = { off: 'Off', slowed: 'Slowed + Reverb', bass: 'Bass boost', nightcore: 'Nightcore', reverb: 'Reverb', vocal: 'Vocal boost', lofi: 'Lo-fi' };
    const FXS = { off: 'Original sound', slowed: 'Slower, deeper, with a big hall', bass: 'Stronger low end', nightcore: 'Faster and higher', reverb: 'Concert-hall space', vocal: 'Clearer voices', lofi: 'Warm, soft and muffled' };
    function fxSheet() {
      const cur = P.state.fx || 'off';
      V.openSheet(`<div class="sheet-h">Sound effects</div>${Object.keys(FX).map((k) => V.opt(k, k === cur ? 'check' : '', FX[k], FXS[k], k === cur ? 'sel' : '')).join('')}`, (a) => { cmd('fx', { id: a }); if (a !== 'off') V.toast(FX[a] + ' on'); });
    }
    V.on('musicFx', (r) => { if (r && r.warn) V.toast(r.warn); });
    $('#npFx').addEventListener('click', fxSheet);
    function speedSheet() {
      const cur = P.state.speed || 1;
      V.openSheet(`<div class="sheet-h">Playback speed</div>${[0.5, 0.75, 1, 1.25, 1.5, 1.75, 2].map((x) => V.opt(String(x), Math.abs(cur - x) < 0.01 ? 'check' : '', x + '×' + (x === 1 ? '  Normal' : ''), '', Math.abs(cur - x) < 0.01 ? 'sel' : '')).join('')}`, (a) => cmd('speed', { v: +a }));
    }
    function sleepSheet() {
      const s = P.state.sleep || 0;
      V.openSheet(`<div class="sheet-h">Sleep timer${s > 0 ? ' · ' + Math.ceil(s / 60000) + ' min left' : ''}</div>${V.opt('0', !s ? 'check' : '', 'Off')}${V.opt('-1', s < 0 ? 'check' : '', 'End of this song')}${[10, 15, 30, 45, 60, 90].map((m) => V.opt(String(m), '', m + ' minutes')).join('')}`,
        (a) => { cmd('sleep', { min: +a }); if (+a > 0) V.toast('Music stops in ' + a + ' min'); });
    }
    $('#npTags').addEventListener('click', (e) => { const t = e.target.closest('[data-tag]'); if (!t) return; if (t.dataset.tag === 'speed') speedSheet(); else if (t.dataset.tag === 'fx') fxSheet(); else sleepSheet(); });
    $('#npMore').addEventListener('click', () => {
      const s = P.state, sp = +(s.speed || 1).toFixed(2), sl = s.sleep || 0;
      V.openSheet(`<div class="sheet-h">${esc((s.item && s.item.title) || 'Now playing')}</div>`
        + V.opt('fx', 'eq', 'Sound effects', FX[s.fx || 'off'])
        + V.opt('speed', 'speed', 'Playback speed', sp === 1 ? 'Normal' : sp + '×')
        + V.opt('sleep', 'timer', 'Sleep timer', sl > 0 ? Math.ceil(sl / 60000) + ' min left' : sl < 0 ? 'End of this song' : 'Off')
        + V.opt('queue', 'queue', 'Queue', P.queue.length ? P.queue.length + ' songs' : '')
        + '<div class="sheet-sep"></div>' + V.opt('stop', 'cancel', 'Stop and close player', '', 'danger'),
      (a) => {
        if (a === 'fx') setTimeout(fxSheet, 230);
        else if (a === 'speed') setTimeout(speedSheet, 230);
        else if (a === 'sleep') setTimeout(sleepSheet, 230);
        else if (a === 'queue') setTimeout(queueSheet, 230);
        else if (a === 'stop') { cmd('stop'); hide(); }
      });
    });
    $('#npQueue').addEventListener('click', queueSheet);
    function queueSheet() {
      const cur = P.state.index;
      V.openSheet(`<div class="sheet-h">Queue · ${P.queue.length}</div>${P.queue.map((it, i) => `<div class="qrow ${i === cur ? 'cur' : ''}" data-act="j${i}"><span class="qn">${i === cur ? svg('play') : i + 1}</span><span class="qt"><b>${esc(it.title)}</b><span>${esc(it.artist || '')}</span></span>${i !== cur ? `<button class="icon-btn" data-act="r${i}" aria-label="Remove">${svg('close')}</button>` : ''}</div>`).join('')}
        <div class="sheet-sep"></div>${V.opt('stop', 'cancel', 'Stop and clear queue', '', 'danger')}`, (a) => {
        if (a === 'stop') { cmd('stop'); return; }
        const i = +a.slice(1);
        if (a[0] === 'j') cmd('jump', { index: i });
        else { cmd('remove', { index: i }); return false; }
      });
      const c = $('#sheet .qrow.cur'); if (c) c.scrollIntoView({ block: 'center' });
    }
    // timeline drag
    const tl = $('#npTl');
    const posAt = (x) => { const r = tl.getBoundingClientRect(); return Math.max(0, Math.min(1, (x - r.left) / r.width)); };
    tl.addEventListener('pointerdown', (e) => { dragging = true; tl.classList.add('drag'); tl.setPointerCapture(e.pointerId); setTl(posAt(e.clientX) * (P.state.dur || 0), P.state.dur, P.state.buf); });
    tl.addEventListener('pointermove', (e) => { if (dragging) setTl(posAt(e.clientX) * (P.state.dur || 0), P.state.dur, P.state.buf); });
    tl.addEventListener('pointerup', (e) => { if (!dragging) return; dragging = false; tl.classList.remove('drag'); const ms = Math.round(posAt(e.clientX) * (P.state.dur || 0)); P.state.pos = ms; seekHold = { ms, until: performance.now() + 1500 }; base = { pos: ms, t: performance.now() }; cmd('seek', { ms }); });
    tl.addEventListener('pointercancel', () => { dragging = false; tl.classList.remove('drag'); render(); });
    // native state arrives every 500 ms; move the timeline smoothly in between
    let base = { pos: 0, t: 0 }, seekHold = null;
    const estimate = () => { const s = P.state; return s.playing && !s.buffering ? Math.min(s.dur || 0, base.pos + (performance.now() - base.t) * (s.speed || 1)) : base.pos; };
    let lastDraw = -1;
    (function loop() {
      requestAnimationFrame(loop);
      const s = P.state;
      if (!s.active || !s.dur || dragging || document.hidden) return;
      const p = estimate();
      if (Math.abs(p - lastDraw) < 40) return;        // ~25 updates/s is plenty for a smooth bar
      lastDraw = p;
      if (P.open) setTl(p, s.dur, s.buf);
      $('#miniP').style.width = Math.min(100, (p / s.dur) * 100) + '%';
    })();
    // swipe the artwork sideways = next / previous song (the cover follows the finger)
    const stage = $('#npStage'), artEl = $('#npArt');
    let ax = null, ay = 0, aOn = false, aDx = 0;
    stage.addEventListener('touchstart', (e) => { if (e.touches.length > 1) { ax = null; return; } ax = e.touches[0].clientX; ay = e.touches[0].clientY; aOn = false; aDx = 0; }, { passive: true });
    stage.addEventListener('touchmove', (e) => {
      if (ax == null) return;
      const dx = e.touches[0].clientX - ax, dy = e.touches[0].clientY - ay;
      if (!aOn) { if (Math.abs(dx) > 12 && Math.abs(dx) > Math.abs(dy) * 1.3) { aOn = true; artEl.style.transition = 'none'; } else if (Math.abs(dy) > 12) { ax = null; return; } else return; }
      const can = dx < 0 ? P.state.hasNext !== false : (P.state.hasPrev !== false || (P.state.pos || 0) > 4000);
      aDx = can ? dx : dx * 0.3;
      artEl.style.transform = `translateX(${aDx}px) rotate(${aDx / 60}deg)`;
      artEl.style.opacity = String(1 - Math.min(0.5, Math.abs(aDx) / 600));
    }, { passive: true });
    const artEnd = () => {
      if (ax == null) return; ax = null;
      if (!aOn) return; aOn = false;
      const w = stage.clientWidth || 300, next = aDx < 0;
      const can = next ? P.state.hasNext !== false : (P.state.hasPrev !== false || (P.state.pos || 0) > 4000);
      artEl.style.transition = 'transform .2s cubic-bezier(.2,.8,.2,1), opacity .2s';
      if (Math.abs(aDx) > Math.min(90, w * 0.22) && can) {
        artEl.style.transform = `translateX(${next ? -w : w}px)`; artEl.style.opacity = '0';
        if (next) cmd('next'); else { cmd('seek', { ms: 0 }); cmd('prev'); }
        setTimeout(() => {
          artEl.style.transition = 'none'; artEl.style.transform = `translateX(${next ? w * 0.5 : -w * 0.5}px)`;
          requestAnimationFrame(() => requestAnimationFrame(() => { artEl.style.transition = 'transform .26s cubic-bezier(.2,.8,.2,1), opacity .26s'; artEl.style.transform = ''; artEl.style.opacity = ''; }));
        }, 190);
      } else { artEl.style.transform = ''; artEl.style.opacity = ''; }
    };
    stage.addEventListener('touchend', artEnd); stage.addEventListener('touchcancel', artEnd);

    // swipe down to close — only after a clear vertical drag, so taps on buttons never move the screen
    const npEl = $('#np');
    let sy = null, sx = 0, engaged = false, swallow = false;
    npEl.addEventListener('touchstart', (e) => { if (e.target.closest('.tl') || e.touches.length > 1 || aOn) { sy = null; return; } sy = e.touches[0].clientY; sx = e.touches[0].clientX; engaged = false; }, { passive: true });
    npEl.addEventListener('touchmove', (e) => {
      if (sy == null) return;
      const dy = e.touches[0].clientY - sy, dx = e.touches[0].clientX - sx;
      if (!engaged) { if (dy > 14 && dy > Math.abs(dx) * 1.5) { engaged = true; npEl.classList.add('dragging'); } else if (Math.abs(dx) > 14 || dy < -14) { sy = null; return; } else return; }
      npEl.style.transform = `translateY(${Math.max(0, dy - 14)}px)`;
    }, { passive: true });
    const endSwipe = (e) => {
      if (sy == null) return;
      const dy = (e.changedTouches[0] || {}).clientY - sy; sy = null;
      if (!engaged) return;
      engaged = false; swallow = true; setTimeout(() => { swallow = false; }, 350);
      npEl.classList.remove('dragging'); npEl.style.transform = '';
      if (dy > 130) hide();
    };
    npEl.addEventListener('touchend', endSwipe); npEl.addEventListener('touchcancel', endSwipe);
    npEl.addEventListener('click', (e) => { if (swallow) { e.stopPropagation(); e.preventDefault(); swallow = false; } }, true);

    V.on('music', (s) => {
      const prevUri = P.state.item && P.state.item.uri, prevPlaying = P.state.playing;
      if (seekHold) { if (Math.abs((s.pos || 0) - seekHold.ms) < 1500 || performance.now() > seekHold.until) seekHold = null; else s.pos = seekHold.ms; }
      // keep the smooth clock unless the real position drifted (seek, buffering, new song)
      const sameSong = (s.item && s.item.uri) === prevUri;
      const est = estimate();
      if (sameSong && s.playing && P.state.playing && !s.buffering && Math.abs((s.pos || 0) - est) < 700) s.pos = est;
      else base = { pos: s.pos || 0, t: performance.now() };
      if (s.speed !== P.state.speed) base = { pos: s.pos || 0, t: performance.now() };
      P.state = s;
      render();
      if ((s.item && s.item.uri) !== prevUri || s.playing !== prevPlaying) Music.refreshCurrent();
    });
    V.on('musicQueue', (q) => { P.queue = q || []; });
    P.show = show; P.hide = hide; P.render = render;
    return P;
  })();

  // ================================================================ DOWNLOADS
  function downloadsSub() {
    const n = Array.from(V.jobs.values()).filter((j) => V.isActive(j)).length;
    return n ? n + ' in progress' : V.history.length ? V.history.length + ' saved' : '';
  }
  function updateCounts() {
    const n = Array.from(V.jobs.values()).filter((j) => V.isActive(j)).length;
    const c = $('#dlCount'); c.hidden = !n; c.textContent = n;
    if (view === 'downloads') $('#hSub').textContent = downloadsSub();
  }
  function thumbBox(h, w) {
    const img = h.thumbFile ? V.fileUrl(h.thumbFile) : h.thumb;
    const audio = h.audio || /^audio/.test(h.mime || '');
    return `<div class="dth">${img ? `<img src="${esc(img)}" alt="" referrerpolicy="no-referrer" data-ph="${audio ? 'music' : 'video'}" onload="VB.imgFit(this)" onerror="VB.imgErr(this)">` : `<span class="ph">${svg(audio ? 'music' : 'video')}</span>`}${h.duration ? `<span class="tag">${V.dur(h.duration)}</span>` : ''}</div>`;
  }
  function renderDownloads() {
    const act = Array.from(V.jobs.values()).filter((j) => V.isActive(j) || j.state === 'paused' || j.state === 'error');
    $('#activeWrap').hidden = !act.length;
    const al = $('#activeList'); al.innerHTML = '';
    for (const j of act) {
      const r = j.req || {};
      const el = document.createElement('div');
      el.className = 'video job'; el.dataset.id = j.id;
      el.innerHTML = `<div class="v-row">${thumbBox({ thumb: r.thumb, duration: r.duration, audio: r.audio }, 100)}<div class="v-info"><div class="title">${esc(r.title)}</div><div class="meta">${esc([r.label, r.host].filter(Boolean).join(' · '))}</div></div></div>
        <div class="prog"><div class="bar"><i></i></div><div class="p-row"><div class="pinfo"></div><div class="pbtns"></div></div></div>`;
      al.appendChild(el);
      V.renderProgress(j, el.querySelector('.prog'));
    }
    const hl = $('#histList'); hl.innerHTML = '';
    $('#histWrap').hidden = !V.history.length;
    hl.innerHTML = V.history.map((h) => {
      const meta = [h.label, V.bytes(h.size), h.host].filter(Boolean).join(' · ');
      return `<div class="hist" data-id="${esc(h.id)}">${thumbBox(h, 112)}<div class="v-info"><div class="title">${esc(h.title)}</div><div class="meta">${esc(meta)}</div><div class="meta">${esc(V.ago(h.finishedAt))}</div></div>
        <div class="pbtns"><button class="pb" data-h="more" aria-label="More">${svg('more')}</button></div></div>`;
    }).join('');
    $('#dlEmpty').hidden = act.length > 0 || V.history.length > 0;
    try {
      const st = JSON.parse(call('storage') || '{}');
      if (st.total) {
        $('#store').hidden = false;
        $('#storeT').textContent = 'Phone storage';
        $('#storeF').textContent = V.bytes(st.free) + ' free of ' + V.bytes(st.total);
        $('#storeI').style.width = Math.round((1 - st.free / st.total) * 100) + '%';
      }
    } catch (e) { /* */ }
    $('#hSub').textContent = downloadsSub();
  }
  $('#histList').addEventListener('click', (e) => {
    const row = e.target.closest('[data-id]'); if (!row) return;
    const h = V.history.find((x) => x.id === row.dataset.id); if (!h) return;
    if (e.target.closest('[data-h]')) itemMenu(h); else V.openItem(h.id);
  });
  function itemMenu(h) {
    const audio = /^audio/.test(h.mime || '');
    V.openSheet(`<div class="sheet-t">${esc(h.title)}</div><div class="sheet-s">${esc(h.shown || h.name || '')}</div>
      ${V.opt('open', 'play', audio ? 'Play in Music' : 'Play')}${V.opt('with', 'external', 'Open with another app')}${V.opt('share', 'share', 'Share')}
      ${h.pageUrl ? V.opt('source', 'link', 'Copy page link') + V.opt('again', 'retry', 'Download again', 'Pick another quality') : ''}
      <div class="sheet-sep"></div>${V.opt('remove', 'cancel', 'Remove from list')}${V.opt('delete', 'trash', 'Delete file from phone', '', 'danger')}`, (act) => {
      if (act === 'open') V.openItem(h.id);
      else if (act === 'with') { const r = call('openFileWith', h.id); if (r === 'missing') V.toast('The file was moved or deleted'); }
      else if (act === 'share') V.shareItem(h.id);
      else if (act === 'source') { call('copy', h.pageUrl); V.toast('Page link copied'); }
      else if (act === 'again') { show('home'); urlIn.value = h.pageUrl; syncField(); go(); }
      else if (act === 'remove') { call('deleteItem', h.id, false); refreshHistory(); }
      else if (act === 'delete') { const ok = call('deleteItem', h.id, true); refreshHistory(); V.toast(ok === false ? 'Removed from list (the file was already gone)' : 'File deleted'); }
    });
  }
  $('#clearHist').addEventListener('click', async () => {
    if (await V.confirm('Clear the list?', 'Downloaded files stay on your phone.', 'Clear list')) { call('clearHistory'); refreshHistory(); }
  });
  function refreshHistory() {
    try { const s = JSON.parse(call('getState') || '{}'); V.history = s.history || []; V.signedIn = s.signedIn || []; } catch (e) { /* */ }
    if (view === 'downloads') renderDownloads();
    if (view === 'home') renderHome();
  }

  // ================================================================ SETTINGS
  const SIGN = [
    ['YouTube', 'https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F'],
    ['Instagram', 'https://www.instagram.com/accounts/login/'], ['Facebook', 'https://m.facebook.com/login/'],
    ['TikTok', 'https://www.tiktok.com/login'], ['X', 'https://x.com/i/flow/login'], ['Vimeo', 'https://vimeo.com/log_in'],
    ['Reddit', 'https://www.reddit.com/login/'], ['Dailymotion', 'https://www.dailymotion.com/signin'], ['Other site…', ''],
  ];
  $('#signSites').innerHTML = SIGN.map(([n], i) => `<button class="btn small" data-site="${i}">${V.brand(n.toLowerCase()) || svg('globe')}${esc(n)}</button>`).join('');
  $('#signSites').addEventListener('click', (e) => { const b = e.target.closest('[data-site]'); if (b) openBrowser(SIGN[+b.dataset.site][1], 'signin'); });
  $('#signOut').addEventListener('click', async () => {
    if (await V.confirm('Sign out of all sites?', 'Danav forgets every sign-in. You can sign in again any time.', 'Sign out', true)) { call('signOutAll'); V.signedIn = []; renderSettings(); V.toast('Signed out'); }
  });
  function renderSettings() {
    const s = V.settings;
    $('#folder').value = s.folder || '';
    $('#quality').value = s.quality || 'best';
    $('#preferMp4').checked = s.preferMp4 !== false;
    $('#clipboard').checked = s.clipboard !== false;
    $('#notify').checked = s.notify !== false;
    $('#signedList').textContent = V.signedIn.length ? V.signedIn.join(', ') : 'No sites';
    $('#signOut').disabled = !V.signedIn.length;
    renderEngine(); renderAd(); renderLook(); renderPrivacy();
  }
  // ---- appearance
  $('#accentOpts').innerHTML = V.ACCENTS.map(([k, n, c]) => `<button data-accent="${k}" style="--sw:${c}" aria-label="${n}" title="${n}"><i></i></button>`).join('');
  function renderLook() {
    const s = V.settings;
    $$('#themeOpts [data-theme]').forEach((b) => b.classList.toggle('on', b.dataset.theme === (s.theme || 'light')));
    $$('#accentOpts [data-accent]').forEach((b) => b.classList.toggle('on', b.dataset.accent === (s.accent || 'red')));
    $$('#sizeOpts [data-size]').forEach((b) => b.classList.toggle('on', b.dataset.size === (s.textSize || 'm')));
    $('#compact').checked = !!s.compact;
  }
  function setLook(patch) {
    Object.assign(V.settings, patch);
    const dark = call('setTheme', JSON.stringify(patch));
    V.applyTheme(dark === undefined ? undefined : dark);
    renderLook();
  }
  $('#themeOpts').addEventListener('click', (e) => { const b = e.target.closest('[data-theme]'); if (b) setLook({ theme: b.dataset.theme }); });
  $('#accentOpts').addEventListener('click', (e) => { const b = e.target.closest('[data-accent]'); if (b) setLook({ accent: b.dataset.accent }); });
  $('#sizeOpts').addEventListener('click', (e) => { const b = e.target.closest('[data-size]'); if (b) setLook({ textSize: b.dataset.size }); });
  $('#compact').addEventListener('change', () => setLook({ compact: $('#compact').checked }));
  V.on('theme', (d) => V.applyTheme(d.dark));
  // ---- privacy
  function renderPrivacy() {
    const vs = window.VaultUI ? VaultUI.state() : {};
    $('#pvState').textContent = !vs.set ? 'Hide photos, videos and files behind a PIN' : vs.unlocked ? `Unlocked · ${vs.count} item${vs.count === 1 ? '' : 's'}` : 'Locked';
    $('#pvChange').disabled = !vs.set; $('#pvErase').disabled = !vs.set;
  }
  $('#pvOpen').addEventListener('click', () => show('private'));
  $('#pvChange').addEventListener('click', async () => { await VaultUI.changePin(); renderPrivacy(); });
  $('#pvErase').addEventListener('click', async () => { await VaultUI.erase(); renderPrivacy(); });
  function saveSettings(patch) { Object.assign(V.settings, patch); call('setSettings', JSON.stringify(patch)); renderFolder(); V.toast('Saved'); }
  $('#folder').addEventListener('change', () => {
    const f = $('#folder').value.replace(/[\\:*?"<>|]/g, '').replace(/\.\./g, '').replace(/^\/+|\/+$/g, '').trim();
    $('#folder').value = f; saveSettings({ folder: f });
  });
  $('#quality').addEventListener('change', () => saveSettings({ quality: $('#quality').value }));
  for (const k of ['preferMp4', 'clipboard', 'notify']) $('#' + k).addEventListener('change', () => saveSettings({ [k]: $('#' + k).checked }));
  function renderEngine() {
    const e = V.engine;
    $('#engineVer').textContent = e.version || '';
    const btn = $('#updateEngine');
    btn.disabled = e.state !== 'ready' || e.updating;
    btn.textContent = e.updating ? 'Updating…' : 'Update';
    const n = $('#engineNote');
    if (e.state === 'init') { n.hidden = false; n.classList.remove('bad'); $('#engineText').textContent = 'Preparing the download engine… (first launch only)'; n.querySelector('.spinner').hidden = false; }
    else if (e.state === 'failed') { n.hidden = false; n.classList.add('bad'); $('#engineText').textContent = e.message || 'The download engine couldn\'t start.'; n.querySelector('.spinner').hidden = true; }
    else n.hidden = true;
  }
  $('#updateEngine').addEventListener('click', async () => {
    V.engine.updating = true; renderEngine();
    $('#engineStatus').textContent = 'Checking for a new version…';
    const r = await callAsync('updateEngine');
    V.engine.updating = false;
    if (r && r.version) V.engine.version = r.version;
    renderEngine();
    const res = (r && r.result) || '';
    $('#engineStatus').textContent = res === 'updated' ? 'Updated to the latest version.' : res === 'latest' ? 'You have the latest version.' : 'Update failed: ' + res.replace(/^error:\s*/, '') + '. Check your connection.';
  });
  function renderFolder() { const f = V.settings.folder; $('#emptyFolder').textContent = 'Download' + (f ? '/' + f : ''); }

  // ================================================================ native events
  V.on('job', (j) => {
    if (j.history) { refreshHistory(); return; }
    grab.onJob(j);
    updateCounts();
    if (!j.removed && j.state === 'done' && !doneSeen.has(j.id)) { doneSeen.add(j.id); V.toast('Saved to ' + (j.result && j.result.shown ? j.result.shown.replace(/\/[^/]*$/, '') : 'Download')); }
    if (view === 'downloads') {
      const el = document.querySelector(`.job[data-id="${j.id}"]`);
      const listed = !j.removed && (V.isActive(j) || j.state === 'paused' || j.state === 'error');
      if (el && listed) V.renderProgress(j, el.querySelector('.prog'));
      else renderDownloads();
    }
  });
  const doneSeen = new Set();
  V.on('engine', renderEngine);
  V.on('ad', (d) => { ad = Object.assign(ad, d); renderAd(); });
  V.on('shared', (d) => { show('home'); urlIn.value = d.url; syncField(); $('#clipChip').hidden = true; localStorage.setItem('clipSeen', d.url); grab.analyze(d.url, null); });
  V.on('clipboard', (d) => {
    const u = d.url;
    if (!u || u === urlIn.value || u === localStorage.getItem('clipSeen') || u === grab.lastUrl) return;
    const chip = $('#clipChip'); chip.dataset.url = u; $('#clipUrl').textContent = u.replace(/^https?:\/\/(www\.)?/, '');
    chip.hidden = false;
    call('prefetch', u);   // read it in the background so tapping the chip is instant
  });
  V.on('browser', (d) => { if (d && d.signin) { V.toast('Signed in'); refreshHistory(); renderSettings(); if (grab.lastUrl && grab.hasProblem) grab.analyze(grab.lastUrl, null); } });
  V.on('view', (d) => { if (d.view === 'player') { if (Player.state.active) Player.show(); } else show(d.view); });

  // ================================================================ start
  const s = V.loadState();
  for (const j of V.jobs.values()) if (j.state === 'done') doneSeen.add(j.id);
  ad = s.ad || {};
  perm = s.perm || 'denied';
  Player.state = s.music || {}; Player.queue = s.queue || [];
  $('#appVer').textContent = s.appVersion ? ' · v' + s.appVersion : '';
  $('#aboutVer').textContent = s.appVersion || '';
  renderFolder(); renderEngine(); renderAd(); updateCounts(); renderHome(); Player.render();
  syncField();
  call('ready');
})();
