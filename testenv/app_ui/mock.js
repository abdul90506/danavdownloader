// Fake native bridge for UI screenshots (mirrors Bridge.java + events) — v8
(function () {
  const S = window.__SAMPLES || {};
  const TH = window.__TH || '';
  let settings = { folder: 'Vidra', quality: 'best', preferMp4: true, clipboard: true, notify: true };
  let history = window.__HISTORY || [];
  const jobs = {};
  const ev = (t, d) => setTimeout(() => window.VB && VB.event(t, d), 0);
  const now = Math.floor(Date.now() / 1000);
  const names = ['Big Buck Bunny 4K', 'Sintel trailer', 'VID_20261004_183522', 'Tears of Steel', 'Screen recording 2026-10-02', 'Family dinner', 'Lecture 07 — Signals', 'Goal highlights', 'Cooking: biryani', 'Drone over Margalla hills', 'WhatsApp Video 2026-09-28', 'Elephants Dream'];
  const folders = [['Camera', 'b1'], ['Vidra', 'b2'], ['WhatsApp Video', 'b3'], ['Screen recordings', 'b4']];
  const videos = Array.from({ length: 24 }, (_, i) => {
    const f = folders[i % 4], port = i % 5 === 0;
    return { id: 100 + i, uri: 'content://media/external/video/media/' + (100 + i), name: names[i % names.length].replace(/ /g, '_') + (i % 3 ? '.mp4' : '.mkv'), title: names[i % names.length] + (i >= 12 ? ' (2)' : ''), dur: (35 + i * 97) * 1000, size: (12 + i * 37) * 1048576, w: port ? 1080 : [1920, 3840, 1280][i % 3], h: port ? 1920 : [1080, 2160, 720][i % 3], mime: i % 3 ? 'video/mp4' : 'video/x-matroska', added: now - i * 86400 * 0.7, mod: now - i * 86400, folder: f[0], bucket: f[1], path: '/storage/emulated/0/DCIM/' + f[0] + '/x' + i + '.mp4' };
  });
  const artists = ['Atif Aslam', 'Coke Studio', 'Nusrat Fateh Ali Khan', 'Hans Zimmer', '', 'Arijit Singh'];
  const albums = ['Season 14', 'Interstellar OST', 'Mustt Mustt', 'Doorie', 'Unknown album'];
  const audio = Array.from({ length: 30 }, (_, i) => ({ id: 500 + i, uri: 'content://media/external/audio/media/' + (500 + i), name: 't' + i + '.mp3', title: ['Pasoori', 'Cornfield Chase', 'Tajdar-e-Haram', 'Tere Bin', 'No Time for Caution', 'Kana Yaari', 'Aadat', 'Day One', 'Voice note 12', 'Afreen Afreen'][i % 10] + (i >= 10 ? ' ' + Math.ceil(i / 10) : ''), artist: artists[i % 6], album: albums[i % 5], albumId: i % 5, track: (i % 9) + 1, dur: (150 + i * 13) * 1000, size: (3 + i % 7) * 1048576, mime: 'audio/mpeg', added: now - i * 40000, mod: now - i * 40000, folder: i % 4 ? 'Music' : 'Recordings', path: '/storage/emulated/0/Music/t' + i + '.mp3' }));
  let music = { active: false };
  const vault = window.__VAULT || { pin: '1234', fails: 0, st: { set: !!window.__VSET, unlocked: !!window.__VOPEN, wait: 0, count: 0 }, items: [] };
  if (window.__VITEMS) { const k = ['photo', 'photo', 'video', 'photo', 'photo', 'video', 'photo', 'photo', 'photo', 'video', 'photo', 'photo']; vault.items = k.map((kind, i) => ({ id: 'a' + i, name: kind === 'video' ? 'VID_2026100' + i + '.mp4' : 'IMG_2026100' + i + '.jpg', mime: kind === 'video' ? 'video/mp4' : 'image/jpeg', size: (2 + i) * 1048576 * (kind === 'video' ? 20 : 1), kind, folder: i > 8 ? 'Trip' : '', added: Date.now() - i * 1e6, thumb: true, w: 1920, h: 1080, dur: kind === 'video' ? 60000 + i * 7000 : 0 })).concat([{ id: 'f1', name: 'Passport scan.pdf', mime: 'application/pdf', size: 845000, kind: 'file', folder: '', added: Date.now() - 5e7 }, { id: 'f2', name: 'Bank statement 2026.xlsx', mime: 'application/vnd.ms-excel', size: 120400, kind: 'file', folder: 'Docs', added: Date.now() - 6e7 }, { id: 'f3', name: 'Voice memo.m4a', mime: 'audio/mp4', size: 3400000, kind: 'audio', folder: '', added: Date.now() - 7e7 }]); vault.st.count = vault.items.length; }
  let queue = [];
  function emitMusic() { ev('music', music); }
  window.Vidra = {
    ready() { if (window.__MUSIC) { queue = audio.slice(0, 10).map((m) => ({ uri: m.uri, title: m.title, artist: m.artist, art: TH + '/a' + (m.albumId) + '.jpg', dur: m.dur, id: m.id })); music = Object.assign({ active: true, playing: true, want: true, buffering: false, index: 0, count: 10, item: queue[0], pos: 61000, buf: 120000, dur: queue[0].dur, shuffle: false, repeat: 2, speed: 1, sleep: 0, error: '' }, window.__MUSIC); ev('musicQueue', queue); emitMusic(); } },
    getState() { return JSON.stringify({ engine: { state: window.__ENGINE || 'ready', version: '2026.09.30', updating: false, message: '' }, settings, jobs: [], history, signedIn: window.__SIGNED || [], sdk: 34, appVersion: '1.1.0', ad: { host: '', level: 2, def: 2, total: 12873, sites: 3, ready: true, rules: 98000 }, music, queue, perm: window.__PERM || 'granted' }); },
    analyze(id, url) {
      const key = /youtu/.test(url) ? 'yt' : /dash/.test(url) ? 'dash' : /w3schools/.test(url) ? 'w3' : /fail/.test(url) ? null : 'hls';
      setTimeout(() => VB.resolve(id, key ? S[key] : { ok: false, error: 'ERROR: [instagram] C1x: Requested content is not available, rate-limit reached or login required. Use --cookies' }), window.__DELAY || 300);
    },
    download(req) {
      const r = JSON.parse(req); const id = 'j' + Math.random().toString(36).slice(2, 7);
      jobs[id] = r;
      const total = r.streams.every((s) => s.size > 0) ? r.streams.reduce((a, s) => a + s.size, 0) : -1;
      const steps = window.__STEPS || [];
      let t = 50;
      const push = (o, d) => { t += d; setTimeout(() => VB.event('job', Object.assign({ id, req: r, total, notes: [], phase: '', speed: 0, error: '', downloaded: 0, percent: -1 }, o)), t); };
      push({ state: 'starting' }, 0);
      for (const p of steps) push(p, 60);
      return id;
    },
    pause() {}, resume() {}, cancel() {}, dismiss() {},
    openFile() { return 'ok'; }, openFileWith() { return 'ok'; }, shareFile() { return 'ok'; }, deleteItem() { return true; }, clearHistory() {},
    setSettings(j) { Object.assign(settings, JSON.parse(j)); },
    copy() {}, paste() { return 'https://www.youtube.com/watch?v=aqz-KE-bpKQ'; }, openBrowser() {}, signOutAll() {}, updateEngine() {}, toast() {}, shareText() {}, openExternal() {},
    mediaPerm() { return window.__PERM || 'granted'; }, requestMediaPerm() {}, openAppSettings() {},
    media(id, kind) { setTimeout(() => VB.resolve(id, kind === 'audio' ? audio : videos), 120); },
    thumbs(j) { for (const o of JSON.parse(j)) setTimeout(() => ev('thumb', { k: o.k, id: o.id, path: o.k === 'v' ? TH + '/v' + (o.id - 100) + '.jpg' : (o.id % 3 ? TH + '/a' + (audio[o.id - 500].albumId) + '.jpg' : '') }), 30); },
    playVideos() {}, deleteMedia() { ev('deleted', { ok: true }); }, shareMedia() {}, openWith() {},
    storage() { return JSON.stringify({ free: 41.3e9, total: 128e9 }); },
    music(cmd, j) { const a = j ? JSON.parse(j) : {}; if (cmd === 'toggle') { music.playing = !music.playing; music.want = music.playing; } if (cmd === 'queue') { queue = a.items; music = { active: true, playing: true, want: true, index: a.index || 0, count: queue.length, item: queue[a.index || 0], pos: 0, buf: 0, dur: queue[a.index || 0].dur, shuffle: !!a.shuffle, repeat: 0, speed: 1, sleep: 0 }; ev('musicQueue', queue); } emitMusic(); },
    musicState() { return JSON.stringify(music); },
    peek(id, url) { setTimeout(() => VB.resolve(id, { ok: true, title: 'Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film', author: 'Blender', thumb: TH + '/v1.jpg' }), 150); },
    prefetch() {},
    adState() { return '{}'; }, setAdLevel() {}, adReset() {},
    // ---- private folder
    vaultState() { return JSON.stringify(vault.st); },
    vaultSetup(id, pin) { setTimeout(() => { vault.pin = pin; vault.st.set = true; vault.st.unlocked = true; VB.resolve(id, { ok: true }); }, 300); },
    vaultUnlock(id, pin) { setTimeout(() => { if (pin === vault.pin) { vault.st.unlocked = true; vault.st.count = vault.items.length; VB.resolve(id, { ok: true }); } else { vault.fails++; VB.resolve(id, { ok: false, error: 'Wrong PIN', wait: vault.fails >= 5 ? 30 : 0 }); } }, 300); },
    vaultChangePin(id, o, n) { setTimeout(() => { vault.pin = n; VB.resolve(id, { ok: true }); }, 300); },
    vaultLock() { vault.st.unlocked = false; }, vaultErase(id) { vault.items = []; vault.st = { set: false, unlocked: false, wait: 0, count: 0 }; VB.resolve(id, { ok: true }); },
    vaultList() { return JSON.stringify(vault.st.unlocked ? vault.items : []); },
    secure() {}, vaultPick() { ev('vault', { busy: true, done: 2, total: 7, name: 'IMG_20261011_101530.jpg' }); }, vaultPlay() {}, vaultOpen(id) { VB.resolve(id, { ok: true }); },
    vaultRestore(id, j) { VB.resolve(id, { ok: JSON.parse(j).length, failed: 0, shown: 'Download/Vidra/x.jpg' }); }, vaultDelete(id, j) { const ids = JSON.parse(j); vault.items = vault.items.filter((x) => !ids.includes(x.id)); VB.resolve(id, { ok: ids.length }); },
    vaultMove(j, f) { for (const x of vault.items) if (JSON.parse(j).includes(x.id)) x.folder = f; }, vaultImportUris() {},
    setTheme(j) { Object.assign(settings, JSON.parse(j)); return undefined; }, isDark() { return undefined; },
  };
  // VB.resolve in core expects string or object? normalize
})();
