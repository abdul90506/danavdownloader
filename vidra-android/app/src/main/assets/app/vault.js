/* Danav — Private folder: PIN lock, encrypted photos / videos / files (keys live in Android's hardware keystore). */
'use strict';
(function () {
  const { $, call, callAsync, svg, esc } = V;
  const BASE = 'https://vault.vidra/';
  const st = () => { try { return JSON.parse(call('vaultState') || '{}'); } catch (e) { return {}; } };
  const parse = (s) => { try { return typeof s === 'string' ? JSON.parse(s) : s || {}; } catch (e) { return {}; } };

  // ================================================================ PIN pad (full-screen overlay)
  let pad = null;           // { resolve, verify, pin, busy, timer }
  function padHtml(title, sub) {
    const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '', '0', 'del'];
    return `<div class="pin-ov" id="pinOv" role="dialog" aria-modal="true">
      <button class="icon-btn pin-x" data-k="x" aria-label="Close">${svg('back')}</button>
      <div class="pin-top">
        <div class="pin-ic">${svg('private')}</div>
        <div class="pin-t" id="pinT">${esc(title)}</div>
        <div class="pin-s" id="pinS">${esc(sub || '')}</div>
        <div class="pin-dots" id="pinDots"><i></i><i></i><i></i><i></i></div>
        <div class="pin-e" id="pinE"></div>
      </div>
      <div class="pin-keys" id="pinKeys">${keys.map((k) => k === '' ? '<span></span>' : k === 'del'
        ? `<button class="pk fn" data-k="del" aria-label="Delete">${svg('backspace')}</button>`
        : `<button class="pk" data-k="${k}">${k}</button>`).join('')}</div>
    </div>`;
  }
  /** Shows the pad; verify(pin) → null when accepted, or {error, wait}. Resolves with the PIN, or null if closed. */
  function askPin(title, sub, verify) {
    closePad(null);
    document.body.insertAdjacentHTML('beforeend', padHtml(title, sub));
    const ov = $('#pinOv');
    requestAnimationFrame(() => ov.classList.add('in'));
    return new Promise((resolve) => {
      pad = { resolve, verify, pin: '', busy: false, timer: 0 };
      const w = st().wait || 0;
      if (w > 0 && verify && sub !== 'new') lockout(w);
      ov.addEventListener('click', (e) => { const b = e.target.closest('[data-k]'); if (b) key(b.dataset.k); });
    });
  }
  function closePad(val) {
    const ov = $('#pinOv'); if (!ov) return;
    if (pad) { clearInterval(pad.timer); const r = pad.resolve; pad = null; r(val); }
    ov.id = ''; ov.style.pointerEvents = 'none';
    ov.classList.remove('in');
    setTimeout(() => ov.remove(), 160);
  }
  function dots() { const d = $('#pinDots'); if (d) [...d.children].forEach((x, i) => x.classList.toggle('on', i < pad.pin.length)); }
  function err(msg) {
    $('#pinE').textContent = msg || '';
    const d = $('#pinDots'); d.classList.remove('shake'); void d.offsetWidth; d.classList.add('shake');
    if (navigator.vibrate) navigator.vibrate(60);
  }
  function lockout(sec) {
    const keys = $('#pinKeys'); keys.classList.add('off');
    let left = Math.ceil(sec);
    const tick = () => {
      if (!pad) return;
      if (left <= 0) { clearInterval(pad.timer); keys.classList.remove('off'); $('#pinE').textContent = ''; return; }
      $('#pinE').textContent = `Too many wrong tries. Try again in ${left >= 60 ? Math.ceil(left / 60) + ' min' : left + ' s'}`;
      left--;
    };
    clearInterval(pad.timer); tick(); pad.timer = setInterval(tick, 1000);
  }
  async function key(k) {
    if (!pad) return;
    if (k === 'x') { closePad(null); return; }
    if (pad.busy || $('#pinKeys').classList.contains('off')) return;
    if (k === 'del') { pad.pin = pad.pin.slice(0, -1); dots(); return; }
    if (pad.pin.length >= 4) return;
    pad.pin += k; dots();
    if (pad.pin.length < 4) { $('#pinE').textContent = ''; return; }
    const pin = pad.pin;
    if (!pad.verify) { setTimeout(() => closePad(pin), 120); return; }
    pad.busy = true; $('#pinDots').classList.add('busy');
    const r = await pad.verify(pin);
    if (!pad) return;
    pad.busy = false; $('#pinDots').classList.remove('busy');
    if (!r) { closePad(pin); return; }
    pad.pin = ''; dots(); err(r.error || 'Wrong PIN');
    if (r.wait > 0) lockout(r.wait);
  }
  document.addEventListener('keydown', (e) => { if (pad && /^[0-9]$/.test(e.key)) key(e.key); else if (pad && e.key === 'Backspace') key('del'); });
  V.backStack.push(() => { if (!$("#pinOv")) return false; closePad(null); return true; });

  async function createPin() {
    for (;;) {
      const a = await askPin('Create a PIN', 'Choose 4 digits to lock your private folder', null);
      if (!a) return false;
      let mismatch = false;
      const b = await askPin('Confirm your PIN', 'Enter the same 4 digits again', async (p) => {
        if (p !== a) { mismatch = true; return null; }
        const r = parse(await callAsync('vaultSetup', p));
        return r.ok ? null : { error: r.error || 'Could not create the private folder' };
      });
      if (!b) return false;
      if (!mismatch) return true;
      V.toast("PINs didn't match — try again");
    }
  }
  async function unlock() {
    const p = await askPin('Enter PIN', 'Private folder', async (pin) => {
      const r = parse(await callAsync('vaultUnlock', pin));
      return r.ok ? null : r;
    });
    return !!p;
  }
  /** Runs fn once the folder is unlocked (creating the PIN on first use). */
  async function ensureUnlocked() {
    const s = st();
    if (s.unlocked) return true;
    return s.set ? unlock() : createPin();
  }
  async function changePin() {
    if (!st().set) { V.toast('Set up the private folder first'); return; }
    let old = '';
    const ok = await askPin('Current PIN', 'Enter your current PIN', async (p) => {
      const r = parse(await callAsync('vaultUnlock', p));
      if (!r.ok) return r;
      old = p; return null;
    });
    if (!ok) return;
    for (;;) {
      const a = await askPin('New PIN', 'Choose 4 new digits', null);
      if (!a) return;
      let mismatch = false;
      const b = await askPin('Confirm new PIN', 'Enter the new PIN again', async (p) => {
        if (p !== a) { mismatch = true; return null; }
        const r = parse(await callAsync('vaultChangePin', old, p));
        return r.ok ? null : { error: r.error || 'Could not change the PIN' };
      });
      if (!b) return;
      if (!mismatch) { V.toast('PIN changed'); return; }
      V.toast("PINs didn't match — try again");
    }
  }
  async function erase() {
    if (!(await V.confirm('Erase the private folder?', 'Every private photo, video and file is destroyed for good and the PIN is removed. This can\'t be undone.', 'Erase everything', true))) return;
    const r = parse(await callAsync('vaultErase'));
    V.toast(r.ok === false ? 'Could not erase' : 'Private folder erased');
    if (active) render();
    VaultUI.onState && VaultUI.onState();
  }

  // ================================================================ view
  let active = false, items = [], filter = 'all', selecting = null, progress = null, pendingMove = null;
  const KIND = { photo: 'image', video: 'video', audio: 'music', file: 'file' };
  const view = () => $('#view-private');

  async function enter() {
    active = true;
    call('secure', true);
    render();
    const ok = await ensureUnlocked();
    if (!active) return;
    if (!ok) { VaultUI.exit(); return; }
    load(); render();
  }
  function leave() { active = false; selecting = null; call('secure', false); closeViewer(); }
  function back() {
    if (viewer) { closeViewer(); return true; }
    if (selecting) { selecting = null; render(); return true; }
    if (filter !== 'all') { filter = 'all'; render(); return true; }
    return false;
  }
  function load() { try { items = JSON.parse(call('vaultList') || '[]'); } catch (e) { items = []; } items.sort((a, b) => b.added - a.added); }
  function shown() {
    if (filter === 'all') return items;
    if (filter === 'photo') return items.filter((x) => x.kind === 'photo');
    if (filter === 'video') return items.filter((x) => x.kind === 'video');
    if (filter === 'file') return items.filter((x) => x.kind === 'file' || x.kind === 'audio');
    return items.filter((x) => x.folder === filter.slice(2));
  }
  function render() {
    const el = view(); if (!el) return;
    const s = st();
    if (!s.unlocked) {
      el.innerHTML = `<div class="empty pv-locked"><div class="empty-ic">${svg('lock')}</div><div class="empty-t">Private folder is locked</div>
        <div class="empty-s">Photos, videos and files you add here are encrypted on this phone and hidden from the gallery and other apps.</div>
        <button class="btn primary" data-pv="unlock">${svg('key')}${s.set ? 'Unlock' : 'Set up PIN'}</button></div>`;
      return;
    }
    const folders = [...new Set(items.map((x) => x.folder).filter(Boolean))].sort();
    const chips = [['all', 'All'], ['photo', 'Photos'], ['video', 'Videos'], ['file', 'Files']].concat(folders.map((f) => ['f:' + f, f]));
    if (!chips.some((c) => c[0] === filter)) filter = 'all';
    const list = shown();
    const media = list.filter((x) => x.kind === 'photo' || x.kind === 'video');
    const files = list.filter((x) => x.kind !== 'photo' && x.kind !== 'video');
    const sel = selecting;
    el.innerHTML = `
      ${sel ? `<div class="pv-bar sel"><button class="icon-btn" data-pv="selx" aria-label="Cancel">${svg('close')}</button><b>${sel.size} selected</b><span class="sp"></span>
        <button class="icon-btn" data-pv="selall" aria-label="Select all">${svg('checkCircle')}</button><button class="icon-btn" data-pv="selmove" aria-label="Move">${svg('move')}</button>
        <button class="icon-btn" data-pv="selrestore" aria-label="Restore">${svg('restore')}</button><button class="icon-btn" data-pv="seldel" aria-label="Delete">${svg('trash')}</button></div>`
      : `<div class="pv-bar"><div class="pv-chips">${chips.map(([k, n]) => `<button class="pchip${k === filter ? ' on' : ''}" data-f="${esc(k)}">${k.startsWith('f:') ? svg('folder') : ''}${esc(n)}</button>`).join('')}</div>
        <button class="icon-btn" data-pv="lock" aria-label="Lock">${svg('lock')}</button></div>`}
      ${progress ? `<div class="pv-prog"><div class="pv-prog-t"><span>${progress.busy ? `Adding ${Math.min(progress.done + 1, progress.total)} of ${progress.total}` : 'Finishing…'}</span><span class="nm">${esc(progress.name || '')}</span></div><div class="pv-prog-b"><i style="width:${progress.total ? Math.round(progress.done / progress.total * 100) : 0}%"></i></div></div>` : ''}
      ${list.length ? '' : `<div class="empty"><div class="empty-ic">${svg('private')}</div><div class="empty-t">${items.length ? 'Nothing here' : 'Your private folder is empty'}</div>
        <div class="empty-s">Add photos, videos, files or a whole folder. They're encrypted and removed from their old place, so only your PIN can open them.</div></div>`}
      ${media.length ? `<div class="pv-grid">${media.map(tile).join('')}</div>` : ''}
      ${files.length ? `<div class="pv-files">${files.map(fileRow).join('')}</div>` : ''}
      <div class="pv-note">${svg('lock')}<span>Encrypted with AES-256 · locks when you leave Danav</span></div>
      ${sel ? '' : `<button class="fab-add" data-pv="add" aria-label="Add">${svg('plus')}<span>Add</span></button>`}`;
  }
  function tile(x) {
    const on = selecting && selecting.has(x.id);
    return `<button class="pv-t${on ? ' on' : ''}" data-id="${esc(x.id)}">
      ${x.thumb ? `<img src="${BASE}t/${esc(x.id)}" alt="" loading="lazy" onerror="this.remove()">` : ''}<span class="ph">${svg(KIND[x.kind])}</span>
      ${x.kind === 'video' ? `<span class="pv-dur">${svg('play')}${x.dur ? V.ms(x.dur) : ''}</span>` : ''}
      ${selecting ? `<span class="pv-ck">${on ? svg('checkCircle') : ''}</span>` : ''}</button>`;
  }
  function fileRow(x) {
    const on = selecting && selecting.has(x.id);
    return `<div class="frow pv-f${on ? ' on' : ''}" data-id="${esc(x.id)}"><span class="fic">${on ? svg('checkCircle') : svg(KIND[x.kind])}</span>
      <div class="vrow-i"><div class="vt">${esc(x.name)}</div><div class="vm">${esc([V.bytes(x.size), ext(x.name), x.folder].filter(Boolean).join(' · '))}</div></div>
      <button class="icon-btn" data-more="${esc(x.id)}" aria-label="More">${svg('more')}</button></div>`;
  }
  const ext = (n) => ((/\.([a-z0-9]{1,5})$/i.exec(n || '') || [])[1] || '').toUpperCase();

  // long-press → selection
  let pressT = 0, pressed = false;
  document.addEventListener('pointerdown', (e) => {
    if (!active || selecting) return;
    const it = e.target.closest('#view-private [data-id]'); if (!it || e.target.closest('[data-more]')) return;
    pressed = false; clearTimeout(pressT);
    pressT = setTimeout(() => { pressed = true; selecting = new Set([it.dataset.id]); if (navigator.vibrate) navigator.vibrate(20); render(); }, 480);
  });
  ['pointerup', 'pointercancel', 'pointermove'].forEach((t) => document.addEventListener(t, (e) => { if (t !== 'pointermove' || Math.abs(e.movementX) + Math.abs(e.movementY) > 6) clearTimeout(pressT); }));

  document.addEventListener('click', async (e) => {
    if (!active) return;
    const root = view(); if (!root || !root.contains(e.target)) return;
    if (pressed) { pressed = false; return; }
    const pv = e.target.closest('[data-pv]');
    if (pv) { action(pv.dataset.pv); return; }
    const f = e.target.closest('[data-f]');
    if (f) { filter = f.dataset.f; render(); return; }
    const more = e.target.closest('[data-more]');
    if (more) { itemMenu(more.dataset.more); return; }
    const it = e.target.closest('[data-id]'); if (!it) return;
    const id = it.dataset.id;
    if (selecting) { selecting.has(id) ? selecting.delete(id) : selecting.add(id); if (!selecting.size) selecting = null; render(); return; }
    open(id);
  });

  async function action(a) {
    if (a === 'unlock') { if (await ensureUnlocked()) { load(); render(); } return; }
    if (a === 'lock') { call('vaultLock'); items = []; render(); VaultUI.exit(); V.toast('Private folder locked'); return; }
    if (a === 'add') { addSheet(); return; }
    if (a === 'selx') { selecting = null; render(); return; }
    if (a === 'selall') { const ids = shown().map((x) => x.id); selecting = selecting.size === ids.length ? new Set() : new Set(ids); if (!selecting.size) selecting = null; render(); return; }
    const ids = selecting ? [...selecting] : [];
    if (!ids.length) return;
    if (a === 'seldel') { if (await delIds(ids)) { selecting = null; render(); } }
    else if (a === 'selrestore') { if (await restoreIds(ids)) { selecting = null; render(); } }
    else if (a === 'selmove') { if (await moveIds(ids)) { selecting = null; render(); } }
  }

  function addSheet() {
    const rm = V.settings.vaultRemove !== false;
    V.openSheet(`<div class="sheet-t">Add to Private</div><div class="sheet-s">Files are encrypted on this phone. Nothing is uploaded.</div>
      ${V.opt('media', 'photos', 'Photos & videos', 'From your gallery')}${V.opt('files', 'file', 'Files', 'Documents, music, any file')}${V.opt('folder', 'newFolder', 'Folder', 'Everything inside a folder')}
      <div class="sheet-sep"></div>
      <label class="set row pv-rm"><div><div class="set-t">Remove originals</div><div class="help">Delete them from their old place after they're safely encrypted, so they disappear from the gallery.</div></div><input type="checkbox" class="switch" id="pvRm" ${rm ? 'checked' : ''}></label>`, (a) => {
      const remove = $('#pvRm') ? $('#pvRm').checked : true;
      V.settings.vaultRemove = remove; call('setSettings', JSON.stringify({ vaultRemove: remove }));
      call('vaultPick', a, remove);
    });
    const sw = $('#pvRm'); if (sw) sw.addEventListener('change', () => { V.settings.vaultRemove = sw.checked; call('setSettings', JSON.stringify({ vaultRemove: sw.checked })); });
  }

  V.on('vault', (d) => {
    d = parse(d);
    if (d.error) { V.toast(d.error); return; }
    if (d.canceled) return;
    if ('busy' in d && !d.result) { progress = d; if (active) render(); return; }
    if (d.result) {
      progress = null;
      const r = d.result;
      load();
      if (active) render();
      if (r.ok === false && r.error) V.toast(r.error);
      else V.toast(`${r.added} added to Private` + (r.failed ? ` · ${r.failed} failed` : '') + (r.kept ? ` · ${r.kept} originals kept` : ''));
      if (pendingMove && r.added) { const f = pendingMove; pendingMove = null; f(r); }
      return;
    }
    // state push (resume): locked while away?
    if (d.unlocked === false && active) { items = []; closeViewer(); render(); ensureUnlocked().then((ok) => { if (!active) return; if (ok) { load(); render(); } else VaultUI.exit(); }); }
  });

  // ================================================================ items
  function item(id) { return items.find((x) => x.id === id); }
  function open(id) {
    const x = item(id); if (!x) return;
    if (x.kind === 'photo') { openViewer(id); return; }
    if (x.kind === 'video' || x.kind === 'audio') {
      const vids = shown().filter((y) => y.kind === x.kind);
      call('vaultPlay', JSON.stringify({ ids: vids.map((y) => y.id), index: vids.indexOf(x) }));
      return;
    }
    callAsync('vaultOpen', id).then((r) => { if (!parse(r).ok) V.toast('No app on this phone can open this file'); });
  }
  function itemMenu(id) {
    const x = item(id); if (!x) return;
    V.openSheet(`<div class="sheet-t">${esc(x.name)}</div><div class="sheet-s">${esc([V.bytes(x.size), x.w && x.h ? x.w + ' × ' + x.h : '', x.dur ? V.ms(x.dur) : '', x.folder].filter(Boolean).join(' · '))}</div>
      ${V.opt('open', x.kind === 'video' || x.kind === 'audio' ? 'play' : 'external', x.kind === 'file' ? 'Open with…' : 'Open')}
      ${V.opt('move', 'move', 'Move to folder', x.folder || 'No folder')}
      ${V.opt('restore', 'restore', 'Restore', 'Decrypt back to Download/' + (V.settings.folder || 'Danav'))}
      <div class="sheet-sep"></div>${V.opt('delete', 'trash', 'Delete for good', '', 'danger')}`, (a) => {
      if (a === 'open') open(id);
      else if (a === 'move') moveIds([id]);
      else if (a === 'restore') restoreIds([id]).then(() => render());
      else if (a === 'delete') delIds([id]).then(() => render());
    });
  }
  async function delIds(ids) {
    if (!(await V.confirm(ids.length > 1 ? `Delete ${ids.length} items?` : 'Delete for good?', 'They are wiped from the private folder and can\'t be recovered.', 'Delete', true))) return false;
    const r = parse(await callAsync('vaultDelete', JSON.stringify(ids)));
    load(); V.toast((r.ok || 0) + ' deleted'); return true;
  }
  async function restoreIds(ids) {
    V.toast('Restoring…');
    const r = parse(await callAsync('vaultRestore', JSON.stringify(ids)));
    load();
    V.toast(r.ok ? `${r.ok} restored to ${r.ok === 1 && r.shown ? r.shown : 'Download'}` + (r.failed ? ` · ${r.failed} failed` : '') : 'Could not restore');
    return true;
  }
  function moveIds(ids) {
    const folders = [...new Set(items.map((x) => x.folder).filter(Boolean))].sort();
    return new Promise((res) => {
      V.openSheet(`<div class="sheet-t">Move to folder</div>
        ${folders.map((f, i) => V.opt('f' + i, 'folder', f)).join('')}${V.opt('new', 'newFolder', 'New folder…')}${V.opt('none', 'close', 'No folder')}
        <div class="pv-new" id="pvNew" hidden><input id="pvNewIn" type="text" maxlength="40" placeholder="Folder name" autocomplete="off"><button class="btn primary small" data-act="newok">Move</button></div>`, (a) => {
        let name = null;
        if (a === 'new') { $('#pvNew').hidden = false; $('#pvNewIn').focus(); return false; }
        if (a === 'newok') { name = ($('#pvNewIn').value || '').trim(); if (!name) return false; }
        else if (a === 'none') name = '';
        else name = folders[+a.slice(1)];
        call('vaultMove', JSON.stringify(ids), name);
        load(); if (name) filter = 'f:' + name; render(); res(true);
        return true;
      }, () => res(false));
    });
  }

  // ================================================================ photo viewer
  let viewer = null;   // { list, i }
  function openViewer(id) {
    const list = shown().filter((x) => x.kind === 'photo');
    viewer = { list, i: Math.max(0, list.findIndex((x) => x.id === id)) };
    document.body.insertAdjacentHTML('beforeend', `<div class="pv-view" id="pvView">
      <div class="pv-vtop"><button class="icon-btn" data-v="close" aria-label="Back">${svg('back')}</button><div class="pv-vt"><b id="pvVt"></b><span id="pvVs"></span></div></div>
      <div class="pv-track" id="pvTrack"></div>
      <div class="pv-vbot"><button data-v="restore">${svg('restore')}<span>Restore</span></button><button data-v="move">${svg('move')}<span>Move</span></button><button data-v="delete">${svg('trash')}<span>Delete</span></button></div></div>`);
    const v = $('#pvView');
    v.addEventListener('click', onViewerClick);
    swipe($('#pvTrack'));
    paint();
    requestAnimationFrame(() => v.classList.add('in'));
  }
  function paint() {
    if (!viewer) return;
    const { list, i } = viewer, x = list[i];
    if (!x) { closeViewer(); return; }
    const img = (k, cls) => { const y = list[k]; return y ? `<div class="pv-slide ${cls}"><img src="${BASE}f/${esc(y.id)}" alt="" draggable="false"></div>` : `<div class="pv-slide ${cls}"></div>`; };
    $('#pvTrack').innerHTML = img(i - 1, 'prev') + img(i, 'cur') + img(i + 1, 'next');
    $('#pvTrack').style.transform = '';
    $('#pvVt').textContent = x.name;
    $('#pvVs').textContent = `${i + 1} of ${list.length}` + (x.w ? ` · ${x.w} × ${x.h}` : '');
  }
  function closeViewer() {
    const v = $('#pvView'); viewer = null; if (!v) return;
    v.classList.remove('in'); setTimeout(() => v.remove(), 180);
  }
  async function onViewerClick(e) {
    const b = e.target.closest('[data-v]');
    if (!b) { $('#pvView').classList.toggle('bare'); return; }
    const x = viewer && viewer.list[viewer.i]; if (!x) return;
    const a = b.dataset.v;
    if (a === 'close') { closeViewer(); return; }
    let done = false;
    if (a === 'delete') done = await delIds([x.id]);
    else if (a === 'restore') done = await restoreIds([x.id]);
    else if (a === 'move') { await moveIds([x.id]); render(); return; }
    if (done && viewer) { viewer.list.splice(viewer.i, 1); if (viewer.i >= viewer.list.length) viewer.i--; render(); if (viewer.list.length) paint(); else closeViewer(); }
  }
  function swipe(track) {
    let x0 = 0, y0 = 0, dx = 0, on = false, horiz = null;
    track.addEventListener('touchstart', (e) => { if (e.touches.length !== 1) return; on = true; horiz = null; dx = 0; x0 = e.touches[0].clientX; y0 = e.touches[0].clientY; track.style.transition = 'none'; }, { passive: true });
    track.addEventListener('touchmove', (e) => {
      if (!on) return;
      const mx = e.touches[0].clientX - x0, my = e.touches[0].clientY - y0;
      if (horiz === null && Math.abs(mx) + Math.abs(my) > 8) horiz = Math.abs(mx) > Math.abs(my);
      if (!horiz) return;
      dx = mx;
      const edge = (dx > 0 && viewer.i === 0) || (dx < 0 && viewer.i === viewer.list.length - 1);
      track.style.transform = `translateX(${edge ? dx / 3 : dx}px)`;
    }, { passive: true });
    track.addEventListener('touchend', () => {
      if (!on) return; on = false;
      const w = track.clientWidth, dir = dx < -w * 0.18 ? 1 : dx > w * 0.18 ? -1 : 0;
      const ni = viewer.i + dir;
      track.style.transition = 'transform .22s cubic-bezier(.2,.8,.2,1)';
      if (dir && ni >= 0 && ni < viewer.list.length) {
        track.style.transform = `translateX(${-dir * w}px)`;
        setTimeout(() => { if (!viewer) return; viewer.i = ni; track.style.transition = 'none'; paint(); }, 220);
      } else track.style.transform = '';
    });
  }

  // ================================================================ from Videos / Music
  async function moveIn(uris, onDone) {
    if (!(await ensureUnlocked())) return;
    pendingMove = onDone || null;
    V.toast('Encrypting…');
    call('vaultImportUris', JSON.stringify({ uris, remove: true }));
  }

  window.VaultUI = {
    enter, leave, back, ensureUnlocked, changePin, erase, moveIn, state: st,
    lockNow() { call('vaultLock'); items = []; if (active) render(); },
    exit() { if (V.show) V.show('home'); },
    onState() { },
  };
})();
