const puppeteer = require('puppeteer');
const fs = require('fs');
const H = process.env.HOME;
const S = {}; for (const n of ['yt', 'dash', 'w3', 'hls']) S[n] = JSON.parse(fs.readFileSync(`${H}/testenv/samples/c_${n}.json`));
const mock = fs.readFileSync(__dirname + '/mock.js', 'utf8');
const D = { pageUrl: 'https://test-streams.mux.dev/x36xhzz/', title: 'Big Buck Bunny — test stream', host: 'test-streams.mux.dev', ua: 'x', candidates: [{ url: 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8', kind: 'hls', how: 'net' }, { url: 'https://cdn.example.com/media/clip_720.mp4?sig=abc', kind: 'file', how: 'dom' }], playing: true, mse: false, desktop: false, canForward: false, fab: true, toolbar: 56, ad: { host: 'test-streams.mux.dev', site: 'mux.dev', level: 3, def: 2, total: 12873, page: 37, ready: true } };
(async () => {
  const b = await puppeteer.launch({ headless: true, args: ['--no-sandbox', '--allow-file-access-from-files'], ignoreDefaultArgs: ['--disable-extensions', '--mute-audio'] });
  const shot = async (name, file, act) => {
    const p = await b.newPage();
    await p.setViewport({ width: 390, height: 820, deviceScaleFactor: 2 });
    p.on('pageerror', e => console.log('PAGEERROR', name, e.message));
    p.on('console', m => { if (m.type() === 'error') console.log('CONSOLE', name, m.text()); });
    await p.evaluateOnNewDocument(`window.__SAMPLES=${JSON.stringify(S)};\n${mock};window.Vidra.panel=function(c,j){console.log('PANEL',c,j)};`);
    await p.goto(`file://${H}/vidra-android/app/src/main/assets/app/${file}`); await new Promise(r => setTimeout(r, 300));
    if (act) await act(p);
    await new Promise(r => setTimeout(r, 1200));
    await p.screenshot({ path: `/tmp/ui_${name}.png` });
    await p.close();
  };
  for (const w of ['download', 'adblock', 'menu']) await shot('p_' + w, 'panel.html', async p => { await p.evaluate((d, w) => VB.event('open', Object.assign({}, d, { panel: w })), D, w); });
  await shot('start', 'start.html');
  await b.close();
})();
