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
      if (VDrawer.isOpen()) { VDrawer.close(); return true; }
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

  // ---------------------------------------------------------------- theme (follows the phone's own setting)
  V.state = {};
  V.applyTheme = function (dark) {
    if (dark === undefined) dark = !!(V.state && V.state.dark);
    document.documentElement.classList.toggle('dark', !!dark);
  };
  function loadState() {
    try { V.state = JSON.parse(V.call('getState') || '{}'); } catch (e) { V.state = {}; }
    V.applyTheme(V.state.dark);
  }
  V.on('theme', (d) => { if (d && typeof d.dark === 'boolean') { V.state.dark = d.dark; V.applyTheme(d.dark); } });

  // ---------------------------------------------------------------- tools sidebar (3-line button, right side)
  const DRAWER = [
    { g: 'tools', a: 'call', i: 'call', t: 'Voice call', s: () => live().active ? (live().muted ? 'Muted · tap to open' : 'On a call · tap to open') : 'Start talking' },
    { g: 'tools', a: 'phone', i: 'touchApp', t: 'Phone control', s: () => { const s = st(); return s.access ? (s.overlay ? 'Ready' : 'Overlay needed') : 'Off · allow accessibility'; } },
    { g: 'tools', a: 'memory', i: 'bulb', t: 'What Danav remembers', s: () => { const n = (st().memory || []).length; return n + (n === 1 ? ' note' : ' notes'); } },
    { g: 'tools', a: 'voice', i: 'voice', t: 'Voice', s: () => voiceName() },
    { g: 'tools', a: 'chats', i: 'history', t: 'Chats', s: () => 'Search & old chats' },
    { g: 'more', a: 'wake', i: 'hearing', t: 'Volume-Up ×3 shortcut', s: () => { const w = st().wake || {}; if (w.error && !w.running) return w.error; if (!w.on) return 'Off'; return w.status === 'call' ? 'On · in a call' : (w.ready || w.status === 'ready' ? 'On · ready anywhere' : 'On'); } },
    { g: 'more', a: 'battery', i: 'battery', t: 'No battery limits', s: () => st().battery ? 'Allowed' : 'Tap to allow' },
    { g: 'more', a: 'appinfo', i: 'settings', t: 'App info', s: () => V.state.version ? 'v' + V.state.version : '' },
  ];
  const drEl = $('#dr'), drScrim = $('#drScrim');
  let drOpen = false, clockT = 0;

  const st = () => { try { return JSON.parse(V.call('phoneState') || '{}'); } catch (e) { return {}; } };
  const live = () => { try { return JSON.parse(V.call('liveInfo') || '{}'); } catch (e) { return {}; } };
  const voiceName = () => localStorage.getItem('danav.ai.voice') || 'Puck';
  const rowsWanted = (g) => DRAWER.filter((r) => r.g === g && !(r.a === 'call' && !window.Vidra.liveStart));

  function renderDrawer() {
    const on = live().active;
    $('#drTools').innerHTML = rowsWanted('tools').map((r) => row(r, r.a === 'call' && on)).join('');
    $('#drMore').innerHTML = rowsWanted('more').map((r) => row(r, false)).join('');
    $('#drSub').textContent = on ? 'Danav is on the line' : 'Voice & phone assistant';
    $('#drVer').textContent = V.state.version ? 'v' + V.state.version : '';
    const p = st();
    if (typeof p.level === 'number') V.state.battery = p.level;
    $('#wgBatt').textContent = (typeof V.state.battery === 'number' ? V.state.battery + '%' : '—');
    const n = (p.memory || []).length;
    $('#wgNotes').textContent = n + (n === 1 ? ' note' : ' notes');
    $('#wgVoice').textContent = voiceName();
    tickClock();
  }
  const row = (r, on) => `<button class="dr-i${on ? ' on' : ''}" data-dr="${r.a}"><span class="di">${V.svg(r.i)}</span><b>${esc(r.t)}</b><span class="dr-s">${esc(r.s() || '')}</span></button>`;
  function tickClock() {
    const d = new Date();
    const el = $('#wgClock'); if (el) el.textContent = d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  }
  function drawer(on) {
    if (!drEl) return;
    drOpen = on;
    drEl.classList.toggle('show', on);
    drScrim.classList.toggle('show', on);
    drEl.setAttribute('aria-hidden', on ? 'false' : 'true');
    clearInterval(clockT); clockT = 0;
    if (on) { renderDrawer(); clockT = setInterval(tickClock, 15000); }
  }
  function openPage(fn) {
    const b = document.querySelector('.ai-page [data-v="back"]');
    if (b) b.click();
    setTimeout(fn, b ? 280 : 60);
  }
  async function drawerAction(a) {
    drawer(false);
    const D = window.DanavAI || {};
    if (a === 'call') {
      if (!window.Vidra.liveStart) { V.toast('Calls need this phone to allow the microphone'); return; }
      if (live().active) { if (D.open) D.open(true); if (D.adopt) D.adopt(); return; }
      if (V.call('micPerm') !== 'granted') { V.call('requestMic'); V.toast('Allow the microphone, then try again'); return; }
      if (D.call) D.call(); else if (D.open) D.open(true);
      return;
    }
    if (a === 'phone' || a === 'memory') { if (D.open) D.open(true); openPage(() => D.phone && D.phone()); return; }
    if (a === 'voice') { if (D.open) D.open(true); openPage(() => D.voice && D.voice()); return; }
    if (a === 'chats') { const b = document.querySelector('#ai [data-a="side"]'); if (b) b.click(); return; }
    if (a === 'wake') {
      const w = st().wake || {};
      const r = V.call('wakeSet', !w.on);
      if (r === 'mic') { V.call('phoneAsk', 'mic'); V.toast('Allow the microphone first'); }
      else if (r === 'blocked') V.toast("Android didn't allow the shortcut to start. Try again.");
      else V.toast(w.on ? 'Volume-Up ×3 shortcut turned off' : 'Volume-Up ×3 anywhere now wakes Danav');
      setTimeout(renderDrawer, 400);
      return;
    }
    if (a === 'battery') { V.call('phoneAsk', 'battery'); return; }
    if (a === 'appinfo') { V.call('openAppSettings'); return; }
  }
  if (drScrim) drScrim.addEventListener('click', () => drawer(false));
  if ($('#drClose')) $('#drClose').addEventListener('click', () => drawer(false));
  if (drEl) drEl.addEventListener('click', (e) => {
    const b = e.target.closest('[data-dr]');
    if (b) drawerAction(b.dataset.dr);
  });
  const VDrawer = window.VDrawer = { open: () => drawer(true), close: () => drawer(false), isOpen: () => drOpen, render: renderDrawer };
  V.on('phone', () => { if (drOpen) renderDrawer(); });
  V.on('wake', () => { if (drOpen) renderDrawer(); });
  V.on('resume', () => { loadState(); if (drOpen) renderDrawer(); });
  V.on('callExternal', () => { if (drOpen) renderDrawer(); });

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
    renderDrawer();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
})();
