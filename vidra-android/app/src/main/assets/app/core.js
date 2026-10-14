/* Danav — shared UI core (main app + browser panel).
   Native side: window.Vidra.* (Bridge.java). Events come back through VB.event / VB.resolve. */
'use strict';
(function () {
  const N = window.Vidra;
  const F = window.VFormats;
  const V = (window.V = {});
  const $ = (V.$ = (s, r) => (r || document).querySelector(s));
  V.$$ = (s, r) => Array.from((r || document).querySelectorAll(s));

  // ---------------------------------------------------------------- bridge
  const pending = new Map();
  let reqSeq = 0;
  const handlers = {};
  V.call = function (name, ...args) {
    try { return N && N[name] ? N[name](...args) : undefined; } catch (e) { console.error(name, e); return undefined; }
  };
  V.callAsync = function (name, ...args) {
    return new Promise((resolve) => {
      const id = 'r' + (++reqSeq);
      pending.set(id, resolve);
      V.call(name, id, ...args);
    });
  };
  V.on = (type, fn) => { (handlers[type] = handlers[type] || []).push(fn); };
  V.backStack = [];   // functions returning true when they handled "back"
  window.VB = {
    resolve(id, data) { const f = pending.get(id); if (f) { pending.delete(id); f(data); } },
    event(type, data) { (handlers[type] || []).forEach((fn) => { try { fn(data); } catch (e) { console.error(type, e); } }); },
    back() {
      if (V.sheetOpen()) { V.closeSheet(); return true; }
      for (let i = V.backStack.length - 1; i >= 0; i--) if (V.backStack[i]()) return true;
      return false;
    },
    /** Portrait / square pictures keep their shape: the frame is filled with a soft blurred copy instead of black bars. */
    imgFit(img) {
      const r = img.naturalWidth / Math.max(1, img.naturalHeight), box = img.parentElement;
      if (!box) return;
      box.classList.add('ok');
      if (r < 1.25) { box.style.setProperty('--bgimg', `url("${img.src.replace(/"/g, '%22')}")`); box.classList.add('fitbg'); }
    },
    imgErr(img) { const s = document.createElement('span'); s.className = 'ph'; s.innerHTML = V.svg(img.dataset.ph || 'video'); img.replaceWith(s); },
  };

  // ---------------------------------------------------------------- icons & helpers
  V.svg = function (name, cls) {
    return `<svg class="ic ${cls || ''}" viewBox="0 -960 960 960" fill="currentColor" aria-hidden="true"><path d="${(window.VICONS || {})[name] || ''}"/></svg>`;
  };
  /** Real brand mark in its official colour (Simple Icons). */
  V.brand = function (name, cls) {
    const b = (window.VBRANDS || {})[name]; if (!b) return '';
    return `<svg class="brand-ic ${cls || ''}" viewBox="0 0 24 24" fill="${/^0{6}$/.test(b.hex) ? 'var(--text)' : '#' + b.hex}" aria-hidden="true"><path d="${b.d}"/></svg>`;
  };
  V.esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  V.bytes = function (n) {
    if (!(n > 0)) return '';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(0) + ' KB';
    if (n < 1024 ** 3) return (n / 1024 / 1024).toFixed(n < 10 * 1024 * 1024 ? 1 : 0) + ' MB';
    return (n / 1024 ** 3).toFixed(2) + ' GB';
  };
  V.dur = function (s) {
    s = Math.round(s || 0); if (!(s > 0)) return '';
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
    return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0');
  };
  V.ms = (ms) => V.dur((ms || 0) / 1000) || '0:00';
  V.speed = (b) => (b > 0 ? V.bytes(b) + '/s' : '');
  V.ago = function (t) {
    const d = (Date.now() - t) / 1000;
    if (d < 60) return 'just now';
    if (d < 3600) return Math.floor(d / 60) + ' min ago';
    if (d < 86400) return Math.floor(d / 3600) + ' h ago';
    if (d < 86400 * 7) return Math.floor(d / 86400) + ' d ago';
    return new Date(t).toLocaleDateString();
  };
  V.findUrl = function (text) {
    const m = String(text || '').match(/https?:\/\/[^\s"'<>]+/i);
    if (m) return m[0].replace(/[).,;!]+$/, '');
    const t = String(text || '').trim();
    if (/^[\w-]+(\.[\w-]+)+\/\S*$/.test(t)) return 'https://' + t;
    return null;
  };
  V.res = function (w, h) {
    const p = Math.min(w || 0, h || 0);
    if (!p) return '';
    if (p >= 2000) return '4K'; if (p >= 1400) return '1440p'; if (p >= 1000) return '1080p'; if (p >= 700) return '720p';
    if (p >= 470) return '480p'; if (p >= 350) return '360p'; return p + 'p';
  };
  V.fileUrl = (p) => (p ? 'file://' + p.split('/').map(encodeURIComponent).join('/') : '');

  // ---------------------------------------------------------------- toast
  let toastT;
  V.toast = function (msg) {
    let t = $('#toast');
    if (!t) { t = document.createElement('div'); t.id = 'toast'; t.className = 'toast'; document.body.appendChild(t); }
    t.textContent = msg; t.classList.add('show');
    clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove('show'), 2400);
  };

  // ---------------------------------------------------------------- bottom sheet
  function ensureSheet() {
    if (!$('#sheet')) {
      document.body.insertAdjacentHTML('beforeend', '<div class="scrim" id="scrim" hidden></div><div class="sheet" id="sheet" hidden role="dialog" aria-modal="true"></div>');
      $('#scrim').addEventListener('click', () => V.closeSheet());
    }
  }
  let sheetClose = null;
  V.openSheet = function (html, onClick, onClose) {
    ensureSheet();
    const sh = $('#sheet');
    sh.innerHTML = '<div class="sheet-grip"></div>' + html;
    sh.scrollTop = 0;
    sh.hidden = false; $('#scrim').hidden = false;
    requestAnimationFrame(() => { sh.classList.add('in'); $('#scrim').classList.add('in'); });
    sheetClose = onClose || null;
    sh.onclick = (e) => {
      const b = e.target.closest('[data-act]');
      if (b && onClick) { const r = onClick(b.dataset.act, b); if (r !== false) V.closeSheet(); }
    };
    return sh;
  };
  V.closeSheet = function () {
    const sh = $('#sheet'); if (!sh || sh.hidden) return;
    sh.classList.remove('in'); $('#scrim').classList.remove('in');
    const f = sheetClose; sheetClose = null;
    setTimeout(() => { sh.hidden = true; $('#scrim').hidden = true; sh.innerHTML = ''; }, 180);
    if (f) f();
  };
  V.sheetOpen = () => { const sh = $('#sheet'); return !!sh && !sh.hidden && sh.classList.contains('in'); };
  V.opt = (act, icon, label, sub, cls) => `<button class="opt ${cls || ''}" data-act="${act}"><span class="ck">${icon ? V.svg(icon) : ''}</span><span class="o-l">${V.esc(label)}</span>${sub ? `<span class="o-s">${V.esc(sub)}</span>` : ''}</button>`;
  V.confirm = function (title, text, yes, danger) {
    return new Promise((res) => {
      V.openSheet(`<div class="sheet-t">${V.esc(title)}</div>${text ? `<div class="sheet-s">${V.esc(text)}</div>` : ''}
        <div class="sheet-btns"><button class="btn" data-act="no">Cancel</button><button class="btn ${danger ? 'danger' : 'primary'}" data-act="yes">${V.esc(yes)}</button></div>`,
      (a) => { res(a === 'yes'); }, () => res(false));
    });
  };

  // ---------------------------------------------------------------- ad-block level slider (4 stops)
  V.AD_LEVELS = [
    { name: 'Off', text: 'No filtering. Sites load exactly as they are.' },
    { name: 'Basic', text: 'Blocks ads, trackers and malware sites.' },
    { name: 'Optimal', text: 'Basic + hides leftover ad boxes, stops pop-ups, redirects and YouTube ads. Recommended.' },
    { name: 'Complete', text: 'Optimal + cookie banners, overlays, social widgets and other annoyances. A few sites may break.' },
  ];
  /** Segmented level control: four labels in one rounded track, a sliding highlight you can tap or drag. */
  /** Full-width level slider: drag the round thumb along the track, it snaps to the nearest labelled stop. */
  V.adSlider = function (el, level, onChange) {
    el.classList.add('lvl');
    el.innerHTML = `<div class="lvl-sl" role="slider" tabindex="0" aria-label="Ad blocking level" aria-valuemin="0" aria-valuemax="3">
        <div class="lvl-rail"><i class="lvl-track"></i><i class="lvl-fill"></i>${[0, 1, 2, 3].map((i) => `<b class="lvl-dot" style="left:${(i / 3) * 100}%"></b>`).join('')}<span class="lvl-thumb"></span></div>
      </div>
      <div class="lvl-labels">${[0, 1, 2, 3].map((i) => `<button data-l="${i}" style="left:${(i / 3) * 100}%">${V.AD_LEVELS[i].name}</button>`).join('')}</div>
      <div class="lvl-text"></div>`;
    const sl = el.querySelector('.lvl-sl'), rail = el.querySelector('.lvl-rail'), thumb = el.querySelector('.lvl-thumb'), fill = el.querySelector('.lvl-fill');
    const dots = el.querySelectorAll('.lvl-dot'), labels = el.querySelectorAll('[data-l]');
    let cur = level | 0;
    function place(frac) { const p = Math.max(0, Math.min(1, frac)) * 100; thumb.style.left = p + '%'; fill.style.width = p + '%'; }
    function mark(l) {
      dots.forEach((d, i) => d.classList.toggle('on', i <= l));
      labels.forEach((b, i) => b.classList.toggle('sel', i === l));
      el.classList.toggle('off', l === 0);
    }
    function paint() {
      place(cur / 3); mark(cur);
      sl.setAttribute('aria-valuenow', cur); sl.setAttribute('aria-valuetext', V.AD_LEVELS[cur].name);
      el.querySelector('.lvl-text').innerHTML = `<b>${V.AD_LEVELS[cur].name}.</b> ${V.esc(V.AD_LEVELS[cur].text)}`;
      el.dataset.level = cur;
    }
    function set(l, fire) {
      l = Math.max(0, Math.min(3, l));
      const changed = l !== cur;
      cur = l; paint();
      if (fire && changed && onChange) onChange(cur);
    }
    const frac = (x) => { const r = rail.getBoundingClientRect(); return Math.max(0, Math.min(1, (x - r.left) / r.width)); };
    let dragging = false;
    sl.addEventListener('pointerdown', (e) => {
      dragging = true; sl.setPointerCapture(e.pointerId); el.classList.add('drag');
      const f = frac(e.clientX); place(f); mark(Math.round(f * 3));
    });
    sl.addEventListener('pointermove', (e) => { if (!dragging) return; const f = frac(e.clientX); place(f); mark(Math.round(f * 3)); });
    const end = (e) => {
      if (!dragging) return; dragging = false; el.classList.remove('drag');
      if (e.type === 'pointercancel') { paint(); return; }
      set(Math.round(frac(e.clientX) * 3), true);
    };
    sl.addEventListener('pointerup', end); sl.addEventListener('pointercancel', end);
    sl.addEventListener('keydown', (e) => { if (e.key === 'ArrowRight' || e.key === 'ArrowUp') { set(cur + 1, true); e.preventDefault(); } else if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') { set(cur - 1, true); e.preventDefault(); } });
    el.querySelector('.lvl-labels').addEventListener('click', (e) => { const b = e.target.closest('[data-l]'); if (b) set(+b.dataset.l, true); });
    paint();
    return { set: (l) => { if (!dragging) set(l, false); }, get: () => cur };
  };
  // ---------------------------------------------------------------- drag guard
  // When a swipe / drag ends, the browser still fires a click on whatever is under the finger — that is how a
  // slide used to press the button underneath. Swallow that click.
  let gdX = 0, gdY = 0, gdMoved = false, gdAt = 0;
  document.addEventListener('pointerdown', (e) => { gdX = e.clientX; gdY = e.clientY; gdMoved = false; }, true);
  document.addEventListener('pointermove', (e) => { if (Math.abs(e.clientX - gdX) + Math.abs(e.clientY - gdY) > 9) { gdMoved = true; gdAt = performance.now(); } }, true);
  document.addEventListener('pointerup', () => { if (gdMoved) gdAt = performance.now(); }, true);
  document.addEventListener('pointercancel', () => { gdMoved = false; }, true);
  document.addEventListener('click', (e) => {
    if (gdMoved && performance.now() - gdAt < 340) { gdMoved = false; e.stopPropagation(); e.preventDefault(); }
  }, true);
  // scrolling a list must not press the row either
  document.addEventListener('scroll', () => { gdAt = performance.now(); gdMoved = true; }, true);

  V.compact = (n) => (n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e4 ? Math.round(n / 1e3) + 'K' : n >= 1e3 ? (n / 1e3).toFixed(1) + 'K' : String(n || 0));

  // ---------------------------------------------------------------- state shared by views
  V.settings = {}; V.engine = { state: 'init' }; V.history = []; V.signedIn = []; V.state = {};
  V.jobs = new Map();
  V.cardJob = new Map();
  V.isActive = (j) => ['queued', 'starting', 'running', 'merging', 'saving'].includes(j.state);

  V.loadState = function () {
    let s = {};
    try { s = JSON.parse(V.call('getState') || '{}'); } catch (e) { /* */ }
    V.settings = s.settings || V.settings; V.engine = s.engine || V.engine; V.history = s.history || []; V.signedIn = s.signedIn || []; V.state = s;
    try { V.applyTheme(V.call('isDark')); } catch (e) { /* */ }
    for (const j of s.jobs || []) { V.jobs.set(j.id, j); if (j.req && j.req.vkey) V.cardJob.set(j.req.vkey, j.id); }
    return s;
  };

  // ---------------------------------------------------------------- appearance
  V.ACCENTS = [
    ['red', 'Red', '#dc2626'], ['rose', 'Rose', '#e11d48'], ['orange', 'Orange', '#ea580c'], ['amber', 'Amber', '#d97706'],
    ['green', 'Green', '#16a34a'], ['teal', 'Teal', '#0d9488'], ['blue', 'Blue', '#2563eb'], ['indigo', 'Indigo', '#4f46e5'],
    ['violet', 'Violet', '#7c3aed'], ['pink', 'Pink', '#db2777'], ['graphite', 'Graphite', '#3f3f46'],
  ];
  const hex = (h) => [1, 3, 5].map((i) => parseInt(h.substr(i, 2), 16));
  const toHex = (c) => '#' + c.map((x) => Math.round(Math.max(0, Math.min(255, x))).toString(16).padStart(2, '0')).join('');
  const mix = (a, b, t) => toHex(hex(a).map((x, i) => x + (hex(b)[i] - x) * t));
  V.applyTheme = function (dark) {
    const s = V.settings, root = document.documentElement;
    if (dark === undefined) dark = s.theme === 'dark' || (s.theme === 'system' && window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches);
    root.classList.toggle('dark', !!dark);
    const acc = (V.ACCENTS.find((a) => a[0] === s.accent) || V.ACCENTS[0])[2];
    let p = acc;
    if (dark && s.accent === 'graphite') p = '#a1a1aa';
    else if (dark) p = mix(acc, '#ffffff', 0.12);
    const bg = dark ? '#0f0f10' : '#ffffff';
    root.style.setProperty('--primary', p);
    root.style.setProperty('--primary2', mix(p, '#000000', 0.16));
    root.style.setProperty('--primary-c', mix(p, bg, dark ? 0.84 : 0.93));
    root.style.setProperty('--on-primary-c', dark ? mix(p, '#ffffff', 0.3) : mix(p, '#000000', 0.18));
    document.body.classList.toggle('compact', !!s.compact);
  };

  // ---------------------------------------------------------------- opening downloaded files
  V.openItem = function (id) {
    const r = V.call('openFile', id);
    if (r === 'missing') V.toast('The file was moved or deleted');
    else if (r === 'noapp') V.toast('This file can\'t be played');
    else if (r === 'music') V.toast('Playing in Music');
  };
  V.shareItem = function (id) { if (V.call('shareFile', id) === 'missing') V.toast('The file was moved or deleted'); };

  // ---------------------------------------------------------------- download progress
  V.renderProgress = function (job, prog) {
    const bar = prog.querySelector('.bar'), fill = bar.querySelector('i'), info = prog.querySelector('.pinfo'), btns = prog.querySelector('.pbtns');
    bar.className = 'bar'; info.className = 'pinfo';
    const pct = job.percent >= 0 ? Math.floor(job.percent) : null;
    let text = '';
    const sz = job.total > 0 ? `${V.bytes(job.downloaded)} of ${V.bytes(job.total)}` : V.bytes(job.downloaded);
    switch (job.state) {
      case 'queued': bar.classList.add('indeterminate'); text = 'Waiting…'; break;
      case 'starting': bar.classList.add('indeterminate'); text = 'Starting…'; break;
      case 'running':
        if (pct == null) bar.classList.add('indeterminate');
        fill.style.width = (pct || 0) + '%';
        text = [pct != null ? `<b>${pct}%</b>` : '', sz, V.speed(job.speed), job.phase === 'Audio' ? 'audio' : ''].filter(Boolean).join(' · ');
        break;
      case 'merging': bar.classList.add('indeterminate'); text = V.esc(job.phase || 'Merging audio and video') + '…'; break;
      case 'saving': fill.style.width = '100%'; text = 'Saving to your phone…'; break;
      case 'paused': bar.classList.add('paused'); fill.style.width = (pct || 0) + '%'; text = ['Paused', pct != null ? pct + '%' : '', V.bytes(job.downloaded)].filter(Boolean).join(' · '); break;
      case 'done': bar.classList.add('done'); text = `<b>Saved</b> · ${V.bytes(job.result ? job.result.size : job.total)}${job.result && job.result.shown ? ' · ' + V.esc(job.result.shown) : ''}`; break;
      case 'error': { bar.classList.add('err'); fill.style.width = (pct || 0) + '%'; const fe = F.friendlyError(job.error); info.classList.add('err'); text = V.esc(fe.msg) + (fe.hint ? ' ' + V.esc(fe.hint) : ''); break; }
      default: text = V.esc(job.state);
    }
    info.innerHTML = text;
    const b = [];
    if (['queued', 'starting', 'running'].includes(job.state)) b.push(['pause', 'Pause']);
    if (job.state === 'paused') b.push(['play', 'Resume']);
    if (job.state === 'error') b.push(['retry', 'Try again']);
    if (job.state === 'done') b.push(['play', 'Play'], ['share', 'Share']);
    if (V.isActive(job) && job.state !== 'saving' && job.state !== 'merging') b.push(['cancel', 'Cancel']);
    if (job.state === 'paused' || job.state === 'error') b.push(['trash', 'Remove']);
    const sig = job.state + ':' + b.map((x) => x[0]).join(',');
    if (btns.dataset.sig !== sig) {
      btns.dataset.sig = sig;
      btns.innerHTML = b.map(([ic, label]) => `<button class="pb" data-pb="${ic}" aria-label="${label}" title="${label}">${V.svg(ic)}</button>`).join('');
    }
    btns.onclick = (e) => {
      const x = e.target.closest('[data-pb]'); if (!x) return;
      e.stopPropagation();
      V.jobAction(job.id, x.dataset.pb);
    };
  };
  V.jobAction = function (id, act) {
    const job = V.jobs.get(id); if (!job) return;
    if (act === 'pause') V.call('pause', id);
    else if (act === 'retry' || (act === 'play' && job.state === 'paused')) V.call('resume', id);
    else if (act === 'cancel' || act === 'trash') V.call('cancel', id);
    else if (act === 'play' && job.result) V.openItem(job.result.id);
    else if (act === 'share' && job.result) V.shareItem(job.result.id);
  };

  // ---------------------------------------------------------------- "grab": analyze a link → result cards → download
  V.grab = function (root, opts) {
    opts = opts || {};
    root.innerHTML = `<div class="loading" hidden><div class="sk-thumb"><img class="pk-img" alt="" hidden></div><div class="sk-col"><div class="pk-t" hidden></div><div class="pk-a" hidden></div><div class="sk-lines"><span class="spinner"></span><span class="lt">Reading link…</span></div></div></div>
      <div class="problem" hidden><div class="problem-t"></div><div class="problem-h"></div><div class="problem-raw"></div>
        <div class="problem-a"><button class="btn small" data-p="browser">${V.svg('globe')}Open in browser</button><button class="btn small" data-p="signin">${V.svg('user')}Sign in to site</button><button class="btn small" data-p="retry">${V.svg('retry')}Try again</button></div></div>
      <div class="results"></div>`;
    const els = { loading: $('.loading', root), lt: $('.lt', root), problem: $('.problem', root), results: $('.results', root) };
    let videos = [], analyzing = 0, lastUrl = '', lastExtra = null, lastSource = null, lastProblem = null;

    function changed() { if (opts.onChange) opts.onChange(); }
    function setLoading(text) { els.loading.hidden = !text; if (text) els.lt.textContent = text; else peekShow(null); changed(); }
    /** Instant preview (title + thumbnail from the page's meta tags) while the formats are still being read. */
    function peekShow(p) {
      const img = $('.pk-img', root), t = $('.pk-t', root), au = $('.pk-a', root);
      if (!p) { img.hidden = true; img.removeAttribute('src'); t.hidden = true; au.hidden = true; els.loading.classList.remove('peek'); return; }
      if (p.thumb && img.getAttribute('src') !== p.thumb) {
        img.onload = () => { img.hidden = false; els.loading.classList.add('peek'); changed(); };
        img.onerror = () => { img.hidden = true; };
        img.src = p.thumb;
      }
      if (p.title) { t.textContent = p.title; t.hidden = false; els.loading.classList.add('peek'); }
      if (p.author) { au.textContent = p.author; au.hidden = false; }
      changed();
    }
    function peek(url, token, base) {
      if (base && (base.title || base.thumb)) peekShow(base);
      V.callAsync('peek', url).then((p) => {
        if (token !== analyzing || els.loading.hidden || !p || !p.ok) return;
        peekShow({ thumb: p.thumb || (base && base.thumb), title: p.title || (base && base.title), author: p.author });
        if (els.lt.textContent === 'Reading link…') els.lt.textContent = 'Reading qualities…';
      }).catch(() => {});
    }
    function problem(p) {
      lastProblem = p;
      if (!p) { els.problem.hidden = true; changed(); return; }
      els.problem.hidden = false;
      $('.problem-t', root).textContent = p.msg;
      $('.problem-h', root).textContent = p.hint || '';
      $('.problem-raw', root).textContent = p.raw && p.raw !== p.msg ? p.raw : '';
      const show = { browser: (p.action === 'browser' || p.action === 'retry') && !!lastUrl && !!opts.onBrowser, signin: p.action === 'signin' && !!opts.onSignin, retry: (p.action === 'retry' || p.action === 'signin') && !!lastUrl };
      root.querySelectorAll('[data-p]').forEach((b) => { b.hidden = !show[b.dataset.p]; });
      $('.problem-a', root).hidden = !show.browser && !show.signin && !show.retry;
      changed();
    }
    root.addEventListener('click', (e) => {
      const b = e.target.closest('[data-p]'); if (!b) return;
      if (b.dataset.p === 'browser') opts.onBrowser(lastUrl);
      else if (b.dataset.p === 'signin') { let o = ''; try { o = new URL(lastUrl).origin; } catch (x) { /* */ } opts.onSignin(o || lastUrl); }
      else if (b.dataset.p === 'retry') { if (lastSource) fromBrowser(lastSource); else analyze(lastUrl, lastExtra); }
    });

    async function analyze(url, extra) {
      const token = ++analyzing;
      lastUrl = url; lastExtra = extra; lastSource = null;
      videos = []; render(); problem(null);
      setLoading(V.engine.state === 'init' ? 'Preparing the engine, then reading the link…' : 'Reading link…');
      peek(url, token);
      const r = await V.callAsync('analyze', url, extra ? JSON.stringify(extra) : '');
      if (token !== analyzing) return;
      setLoading('');
      if (!r || !r.ok) { problem(F.friendlyError(r && r.error)); return; }
      const vs = r.items.map((it) => F.toVideo(it, V.settings, { aid: r.aid, url }));
      if (extra) vs.forEach((v) => { v.extra = extra; });
      show(vs, r.playlistTitle);
    }

    function show(vs, groupTitle) {
      const usable = vs.filter((v) => v.qualities.length || v.drm);
      if (!usable.length) { problem(F.friendlyError('no video formats')); return; }
      videos = usable;
      videos.groupTitle = groupTitle && usable.length > 1 ? groupTitle : '';
      problem(null);
      render();
    }

    /** Page from the browser: the page itself first (yt-dlp knows 1,800 sites), then each detected stream. */
    async function fromBrowser(res, only) {
      const token = ++analyzing;
      lastSource = res; lastUrl = res.pageUrl;
      videos = []; render(); problem(null);
      const extra = { ua: res.ua || '' };
      const cands = only ? [only] : (res.candidates || []).slice(0, 4);
      setLoading(only ? 'Reading the video stream…' : 'Reading the page…');
      if (res.pageUrl) peek(res.pageUrl, token, { title: res.title, thumb: res.thumb });
      // the page and the detected streams are read in parallel — whichever answers first with a video wins
      const fromStream = (c) => {
        const ex = { referer: res.pageUrl, ua: res.ua || '' };
        return V.callAsync('analyze', c.url, JSON.stringify(ex)).then((rr) => {
          if (!rr || !rr.ok) return { err: rr && rr.error, list: [] };
          const list = [];
          for (const it of rr.items) {
            const v = F.toVideo(it, V.settings, { aid: rr.aid, url: c.url, pageTitle: res.title });
            v.pageUrl = res.pageUrl; v.streamUrl = c.url; v.host = F.hostOf(res.pageUrl) || v.host; v.extra = ex;
            if (v.qualities.length) list.push(v);
          }
          return { list, manifest: c.kind !== 'file' };
        });
      };
      const pageP = (!only && res.pageUrl) ? V.callAsync('analyze', res.pageUrl, JSON.stringify(extra)).then((r) => {
        if (!r || !r.ok) return { err: r && r.error, list: [] };
        const list = r.items.map((it) => F.toVideo(it, V.settings, { aid: r.aid, url: res.pageUrl, pageTitle: res.title })).filter((v) => v.qualities.length || v.drm);
        list.forEach((v) => { v.extra = extra; });
        return { list };
      }) : Promise.resolve({ list: [] });
      const streamPs = cands.map(fromStream);
      // race: show whatever answers first with a video; the page result (full info) replaces a stream result if it comes later
      let lastErr = '', shownFrom = '';
      const dedupe = (list) => { const seen = new Set(); return list.filter((v) => { const k = v.title + '|' + (v.qualities[0] && v.qualities[0].label) + '|' + Math.round(v.duration || 0); if (seen.has(k)) return false; seen.add(k); return true; }); };
      const started = () => videos.some((v) => V.cardJob.has(v.key));
      const streamsP = streamPs.length ? Promise.all(streamPs).then((all) => {
        let found = [];
        for (const r of all) { if (r.err) lastErr = lastErr || r.err; found = found.concat(r.list); }
        // a manifest lists every quality: drop plain files that duplicate it
        if (all.some((r) => r.manifest && r.list.length)) found = all.filter((r) => r.manifest).flatMap((r) => r.list);
        return found;
      }) : Promise.resolve([]);
      // a single stream that answers quickly is enough — don't wait for slower siblings
      streamPs.forEach((p) => p.then((r) => {
        if (token !== analyzing || shownFrom || !r.list.length || !r.manifest) return;
        shownFrom = 'stream'; setLoading(''); show(dedupe(r.list));
      }));
      streamsP.then((found) => {
        if (token !== analyzing || shownFrom === 'page' || !found.length) return;
        if (shownFrom === 'stream' && started()) return;
        shownFrom = shownFrom || 'stream'; setLoading(''); show(dedupe(found));
      });
      const page = await pageP;
      if (token !== analyzing) return;
      if (page.err) lastErr = page.err;
      if (page.list.length) {
        if (!(shownFrom === 'stream' && started())) { shownFrom = 'page'; setLoading(''); show(dedupe(page.list)); }
        return;
      }
      if (shownFrom) return;
      if (streamPs.length) setLoading(cands.length > 1 ? `Checking ${cands.length} detected videos…` : 'Reading the video stream…');
      const found = await streamsP;
      if (token !== analyzing || shownFrom) return;
      if (!found.length) {
        setLoading('');
        problem(cands.length ? F.friendlyError(lastErr) : (lastErr ? F.friendlyError(lastErr) : { msg: 'No video found on this page yet.', hint: 'Open the video and press play — Danav detects it while it plays. The download button turns red when a video is detected.' }));
      }
    }

    // ---------- cards
    function thumbHtml(v, h) {
      const a = v.aspect > 0 ? v.aspect : 16 / 9;
      const w = Math.round(Math.max(h * 0.75, Math.min(h * 1.78, h * a)));
      const img = v.thumb;
      return `<div class="thumb" style="width:${w}px;height:${h}px">${img ? `<img src="${V.esc(img)}" alt="" referrerpolicy="no-referrer" onload="VB.imgFit(this)" onerror="VB.imgErr(this)">` : `<span class="ph">${V.svg('video')}</span>`}${v.live ? '<span class="tag live">LIVE</span>' : ''}${v.duration ? `<span class="tag">${V.dur(v.duration)}</span>` : ''}</div>`;
    }
    function qText(q) {
      if (!q) return { l: '—', s: '' };
      const ext = (q.ext || '').toUpperCase();
      const s = q.size ? V.bytes(q.size) + (q.noAudio ? ' · no audio' : '') : (q.label !== ext ? ext : '') + (q.noAudio ? ' · no audio' : '');
      return { l: q.label, s: s.replace(/^ · /, '') };
    }
    function render() {
      els.results.innerHTML = '';
      if (videos.groupTitle) els.results.insertAdjacentHTML('beforeend', `<div class="group-title">${V.esc(videos.groupTitle)} · ${videos.length} videos</div>`);
      for (const v of videos) els.results.appendChild(card(v));
      changed();
    }
    function card(v) {
      const el = document.createElement('article');
      el.className = 'video'; el.dataset.key = v.key;
      const meta = [v.host, v.uploader].filter(Boolean).join(' · ');
      el.innerHTML = `<div class="v-row">${thumbHtml(v, 76)}<div class="v-info"><div class="title">${V.esc(v.title)}</div><div class="meta">${V.esc(meta)}</div></div></div>
        <div class="v-actions">
          <button class="q"><span class="q-l"></span><span class="q-s"></span><span class="q-c">${V.svg('expand')}</span></button>
          <button class="btn sq copy" aria-label="Copy link" title="Copy link">${V.svg('copy')}</button>
          <button class="btn primary dl">${V.svg('download')}<span>Download</span></button>
        </div>
        <div class="v-note"></div><div class="v-err"></div>
        <div class="prog" hidden><div class="bar"><i></i></div><div class="p-row"><div class="pinfo"></div><div class="pbtns"></div></div></div>`;
      el.querySelector('.q').addEventListener('click', () => qualitySheet(v));
      el.querySelector('.copy').addEventListener('click', () => copyLink(v, el));
      el.querySelector('.dl').addEventListener('click', () => download(v));
      updateCard(v, el);
      return el;
    }
    function setBtn(b, label, disabled) { b.querySelector('span').textContent = label; b.disabled = !!disabled; b.classList.toggle('alt', label !== 'Download'); }
    function updateCard(v, el) {
      el = el || root.querySelector(`.video[data-key="${CSS.escape(v.key)}"]`);
      if (!el) return;
      const q = v.qualities[v.sel];
      const t = qText(q);
      el.querySelector('.q-l').textContent = t.l;
      el.querySelector('.q-s').textContent = t.s;
      el.querySelector('.q').disabled = !v.qualities.length;
      el.querySelector('.copy').hidden = !q || !q.url;
      const dl = el.querySelector('.dl'), err = el.querySelector('.v-err'), note = el.querySelector('.v-note');
      err.textContent = ''; note.textContent = '';
      if (v.drm && !v.qualities.length) { err.textContent = 'This video is DRM-protected and can\'t be downloaded.'; dl.disabled = true; }
      else if (v.live) { note.textContent = 'This is a live stream. It can be downloaded after it ends.'; dl.disabled = true; }
      else if (q && q.noAudio) note.textContent = 'This quality has no sound track on the site.';
      const jid = V.cardJob.get(v.key), job = jid && V.jobs.get(jid);
      const prog = el.querySelector('.prog');
      if (!job) { prog.hidden = true; setBtn(dl, 'Download', !!(v.live || (v.drm && !v.qualities.length))); return; }
      prog.hidden = false;
      V.renderProgress(job, prog);
      if (V.isActive(job)) setBtn(dl, job.state === 'merging' ? 'Merging…' : job.state === 'saving' ? 'Saving…' : job.percent >= 0 && job.state === 'running' ? Math.floor(job.percent) + '%' : 'Starting…', true);
      else if (job.state === 'done') setBtn(dl, 'Save again', false);
      else if (job.state === 'paused') setBtn(dl, 'Paused', true);
      else setBtn(dl, 'Download', false);
      if (job.state === 'done' && job.notes && job.notes.length) note.textContent = job.notes.join(' ');
    }
    function qualitySheet(v) {
      const row = (q, i) => {
        const tags = [];
        if (q.convert) tags.push('<span class="o-t">CONVERT</span>');
        else tags.push(`<span class="o-t">${V.esc((q.ext || '').toUpperCase())}</span>`);
        if (q.noAudio) tags.push('<span class="o-t warn">NO AUDIO</span>');
        const on = i === v.sel;
        return `<button class="qopt${on ? ' sel' : ''}" data-act="${i}"><span class="qo-r"></span><span class="qo-l">${V.esc(q.label)}</span><span class="qo-t">${tags.join('')}</span><span class="qo-s">${V.esc(V.bytes(q.size) || '')}</span></button>`;
      };
      const vids = [], auds = [];
      v.qualities.forEach((q, i) => (q.audioOnly ? auds : vids).push(row(q, i)));
      V.openSheet(`<div class="sheet-h">Quality</div>
        ${vids.length ? `<div class="q-h">Video${v.qualities.some((q) => q.merged) ? '<span>with best audio</span>' : ''}</div><div class="qlist">${vids.join('')}</div>` : ''}
        ${auds.length ? `<div class="q-h">Audio only</div><div class="qlist">${auds.join('')}</div>` : ''}`, (act) => { v.sel = +act; updateCard(v); });
    }
    function copyLink(v, el) {
      const q = v.qualities[v.sel];
      if (!q || !q.url) return;
      V.call('copy', q.url);
      const b = el.querySelector('.copy'); b.classList.add('ok'); b.innerHTML = V.svg('check');
      setTimeout(() => { b.classList.remove('ok'); b.innerHTML = V.svg('copy'); }, 1400);
      V.toast('Direct link for ' + q.label + ' copied');
    }
    function download(v) {
      const q = v.qualities[v.sel];
      if (!q) return;
      if (v.live) { V.toast('Live streams can be downloaded after they end'); return; }
      const req = {
        vkey: v.key, aid: v.aid, idx: v.idx, url: v.pageUrl || lastUrl, title: v.title, thumb: v.thumb, host: v.host,
        pageUrl: v.pageUrl || lastUrl, label: q.label, format: q.format, streams: q.streams, ext: q.ext,
        duration: v.duration, aspect: v.aspect, live: v.live, audio: !!q.audioOnly, convert: q.convert || '', kbps: q.kbps || 0, extra: v.extra || null,
      };
      if (v.extra && v.extra.referer) req.url = v.streamUrl || req.url;
      const id = V.call('download', JSON.stringify(req));
      if (!id) { V.toast('Couldn\'t start the download'); return; }
      V.cardJob.set(v.key, id);
      V.jobs.set(id, { id, state: 'queued', percent: -1, downloaded: 0, total: -1, req });
      updateCard(v);
      if (opts.onDownload) opts.onDownload(id);
    }
    function onJob(j) {
      const v = j.req && videos.find((x) => x.key === j.req.vkey);
      if (v) updateCard(v);
      if (j.removed) videos.forEach((x) => updateCard(x));
    }
    return {
      analyze, fromBrowser, onJob,
      clear() { ++analyzing; videos = []; render(); problem(null); setLoading(''); lastSource = null; },
      refresh() { videos.forEach((v) => updateCard(v)); },
      get count() { return videos.length; },
      get busy() { return !els.loading.hidden; },
      get hasProblem() { return !els.problem.hidden; },
      get lastUrl() { return lastUrl; },
    };
  };

  // job events update the shared map first
  V.on('job', (j) => {
    if (j.history) return;
    if (j.removed) { V.jobs.delete(j.id); for (const [k, v] of V.cardJob) if (v === j.id) V.cardJob.delete(k); return; }
    V.jobs.set(j.id, j);
    if (j.req && j.req.vkey && !V.cardJob.has(j.req.vkey)) V.cardJob.set(j.req.vkey, j.id);
  });
  V.on('engine', (d) => { V.engine = Object.assign(V.engine, d); });
})();
