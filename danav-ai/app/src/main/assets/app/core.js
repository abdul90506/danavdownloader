/* Danav AI — app core: the bridge to the phone (window.Vidra.*), toasts, sheets, theme, and the tools
   sidebar that the 3-line button opens. Everything else lives in ai.js. */
'use strict';
(function () {
  const N = window.Vidra;
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
      if (window.VDrawer && VDrawer.isOpen()) { VDrawer.close(); return true; }
      if (V.sheetOpen()) { V.closeSheet(); return true; }
      for (let i = V.backStack.length - 1; i >= 0; i--) if (V.backStack[i]()) return true;
      return false;
    },
  };

  // ---------------------------------------------------------------- helpers
  V.svg = function (name, cls) {
    return `<svg class="ic ${cls || ''}" viewBox="0 -960 960 960" fill="currentColor" aria-hidden="true"><path d="${(window.VICONS || {})[name] || ''}"/></svg>`;
  };
  V.esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const esc = V.esc;

  // ---------------------------------------------------------------- toast
  let toastT = 0;
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

  // ---------------------------------------------------------------- appearance (theme / accent / text size)
  const AC = [['red', 'Red', '#dc2626'], ['rose', 'Rose', '#e11d48'], ['orange', 'Orange', '#ea580c'], ['amber', 'Amber', '#d97706'],
    ['green', 'Green', '#16a34a'], ['teal', 'Teal', '#0d9488'], ['blue', 'Blue', '#2563eb'], ['indigo', 'Indigo', '#4f46e5'],
    ['violet', 'Violet', '#7c3aed'], ['pink', 'Pink', '#db2777'], ['graphite', 'Graphite', '#3f3f46']];
  V.ACCENTS = AC;
  const SET_KEY = 'danav.ai.set';
  V.SET = Object.assign({ theme: 'system', accent: 'red', size: 'm', compact: false }, (() => { try { return JSON.parse(localStorage.getItem(SET_KEY) || '{}'); } catch (e) { return {}; } })());
  const hex = (h) => [1, 3, 5].map((i) => parseInt(h.substr(i, 2), 16));
  const toHex = (c) => '#' + c.map((x) => Math.round(Math.max(0, Math.min(255, x))).toString(16).padStart(2, '0')).join('');
  const mixc = (a, b, t) => toHex(hex(a).map((x, i) => x + (hex(b)[i] - x) * t));
  V.state = {};
  V.applyTheme = function () {
    const t = V.SET.theme || 'system';
    const dark = t === 'dark' || (t === 'system' && !!(V.state && V.state.dark));
    const root = document.documentElement;
    root.classList.toggle('dark', dark);
    const acc = (AC.find((a) => a[0] === V.SET.accent) || AC[0])[2];
    let p = acc;
    if (dark && V.SET.accent === 'graphite') p = '#a1a1aa';
    else if (dark) p = mixc(acc, '#ffffff', 0.12);
    const bg = dark ? '#0f0f10' : '#ffffff';
    root.style.setProperty('--primary', p);
    root.style.setProperty('--primary2', mixc(p, '#000000', 0.16));
    root.style.setProperty('--primary-c', mixc(p, bg, dark ? 0.84 : 0.93));
    root.style.setProperty('--on-primary-c', dark ? mixc(p, '#ffffff', 0.3) : mixc(p, '#000000', 0.18));
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', bg);
    document.body.classList.toggle('compact', !!V.SET.compact);   // tighter lists & messages
  };
  V.setLook = function (patch) {
    Object.assign(V.SET, patch);
    try { localStorage.setItem(SET_KEY, JSON.stringify(V.SET)); } catch (e) { /* */ }
    // Java keeps the same three choices and applies the text size as the phone's own zoom
    const dark = V.call('setTheme', JSON.stringify({ theme: V.SET.theme, accent: V.SET.accent, size: V.SET.size, compact: !!V.SET.compact }));
    if (typeof dark === 'boolean') V.state.dark = dark;
    V.applyTheme();
  };
  V.setLabel = function () {
    const t = { system: 'System', light: 'Light', dark: 'Dark' }[V.SET.theme || 'system'];
    const a = (AC.find((x) => x[0] === V.SET.accent) || AC[0])[1];
    return t + ' · ' + a;
  };
  function loadState() {
    try { V.state = JSON.parse(V.call('getState') || '{}'); } catch (e) { V.state = {}; }
    if (V.state.theme) { V.SET.theme = V.state.theme; V.SET.accent = V.state.accent || V.SET.accent; V.SET.size = V.state.size || V.SET.size; V.SET.compact = V.SET.compact || !!V.state.compact; }
    V.applyTheme();
  }
  V.on('theme', (d) => { if (d && typeof d.dark === 'boolean') { V.state.dark = d.dark; V.applyTheme(); } });

  // ---------------------------------------------------------------- the old right-side drawer is gone
  // ai.js draws the ChatGPT-style sidebar (#aiSide) instead: chats, tools and settings all live there.
  window.VDrawer = { open() {}, close() {}, isOpen: () => false, render() {} };
  V.on('resume', () => loadState());

  // ---------------------------------------------------------------- icons in plain markup ([data-i="name"])
  function renderIcons() {
    V.$$('[data-i]').forEach((el) => { if (!el.dataset.done) { el.innerHTML = V.svg(el.dataset.i); el.dataset.done = '1'; } });
  }

  // ---------------------------------------------------------------- start
  function boot() {
    renderIcons();
    loadState();
    try { V.call('ready'); } catch (e) { /* */ }
    // battery percentage for the widget (added by the phone side on every state push)
    try {
      const s = JSON.parse(V.call('getState') || '{}');
      if (s.phone && typeof s.phone.level === 'number') V.state.battery = s.phone.level;
    } catch (e) { /* */ }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
})();
