const puppeteer = require('puppeteer');
const fs = require('fs');
const H = process.env.HOME;
const S = {}; for (const n of ['yt', 'dash', 'w3', 'hls']) S[n] = JSON.parse(fs.readFileSync(`${H}/testenv/samples/c_${n}.json`));
const APP = `file://${H}/vidra-android/app/src/main/assets/app/index.html`;
const mock = fs.readFileSync(__dirname + '/mock.js', 'utf8');
const HIST = [
  { id: 'h1', title: 'Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film', label: '1080p60', size: 267891149, host: 'youtube.com', finishedAt: Date.now() - 3600e3, thumbFile: `${H}/testenv/th/v1.jpg`, duration: 635, mime: 'video/mp4', shown: 'Download/Vidra/Big Buck Bunny (1080p60).mp4', pageUrl: 'https://youtube.com/watch?v=x' },
  { id: 'h2', title: 'Morning reel — city timelapse', label: '720p', size: 8123456, host: 'instagram.com', finishedAt: Date.now() - 86400e3 * 2, thumbFile: `${H}/testenv/th/v0.jpg`, duration: 14, mime: 'video/mp4' },
  { id: 'h3', title: 'Podcast episode 12', label: 'Audio only', size: 10271496, host: 'youtube.com', finishedAt: Date.now() - 86400e3 * 3, thumb: '', duration: 1840, mime: 'audio/mp4' },
];
const only = process.argv.slice(2);
(async () => {
  const b = await puppeteer.launch({ headless: true, args: ['--no-sandbox', '--allow-file-access-from-files'], ignoreDefaultArgs: ['--disable-extensions', '--mute-audio'] });
  const shot = async (name, setup, act, vp) => {
    if (only.length && !only.includes(name)) return;
    const p = await b.newPage();
    await p.setViewport(vp || { width: 390, height: 820, deviceScaleFactor: 2 });
    p.on('pageerror', e => console.log('PAGEERROR', name, e.message));
    p.on('console', m => { if (m.type() === 'error') console.log('CONSOLE', name, m.text()); });
    await p.evaluateOnNewDocument(`window.__SAMPLES=${JSON.stringify(S)};window.__TH='${H}/testenv/th';window.__HISTORY=${JSON.stringify(HIST)};${setup || ''};\n${mock}`);
    await p.goto(APP); await new Promise(r => setTimeout(r, 400));
    if (act) await act(p);
    await new Promise(r => setTimeout(r, 900));
    await p.screenshot({ path: `/tmp/ui_${name}.png` });
    await p.close();
  };
  const wait = (ms) => new Promise(r => setTimeout(r, ms));
  const type = async (p, u) => { await p.evaluate(u => { const i = document.querySelector('#url'); i.value = u; i.dispatchEvent(new Event('input')); }, u); await p.click('#goBtn'); await wait(700); };
  await shot('home', '');
  await shot('home2', '', async p => { await p.evaluate(() => document.querySelector('#main').scrollTop = 700); });
  await shot('yt', '', async p => { await type(p, 'https://www.youtube.com/watch?v=aqz-KE-bpKQ'); });
  await shot('peek', `window.__DELAY=99999`, async p => { await type(p, 'https://www.youtube.com/watch?v=aqz-KE-bpKQ'); });
  await shot('player2', `window.__MUSIC={speed:1.5,sleep:900000}`, async p => { await p.click('#mini'); await wait(900); }, { width: 390, height: 860, deviceScaleFactor: 2 });
  await shot('progress', `window.__STEPS=[{state:'running',percent:42.3,downloaded:112e6,speed:3.4e6}]`, async p => { await type(p, 'https://www.youtube.com/watch?v=aqz-KE-bpKQ'); await p.click('.dl'); });
  await shot('videos', '', async p => { await p.click('.nav-b[data-view=videos]'); await wait(500); });
  await shot('videoslist', '', async p => { await p.click('.nav-b[data-view=videos]'); await wait(300); await p.click('#vMode'); await wait(400); });
  await shot('folders', '', async p => { await p.click('.nav-b[data-view=videos]'); await wait(300); await p.click('#vSeg [data-s=folders]'); await wait(400); });
  await shot('vdetails', '', async p => { await p.click('.nav-b[data-view=videos]'); await wait(600); await p.click('.vmore'); await wait(300); });
  await shot('perm', `window.__PERM='denied'`, async p => { await p.click('.nav-b[data-view=videos]'); });
  await shot('music', `window.__MUSIC={}`, async p => { await p.click('.nav-b[data-view=music]'); await wait(500); });
  await shot('albums', '', async p => { await p.click('.nav-b[data-view=music]'); await wait(300); await p.click('#mSeg [data-s=albums]'); await wait(400); });
  await shot('player', `window.__MUSIC={}`, async p => { await p.click('#mini'); await wait(900); });
  await shot('downloads', `window.__STEPS=[{state:'running',percent:63,downloaded:9e6,total:-1,speed:1.2e6}]`, async p => { await type(p, 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8'); await p.click('.dl'); await wait(400); await p.click('.nav-b[data-view=downloads]'); });
  await shot('settings', `window.__SIGNED=['instagram.com']`, async p => { await p.click('#openSettings'); });
  await shot('settings2', `window.__SIGNED=['instagram.com']`, async p => { await p.click('#openSettings'); await p.evaluate(() => document.querySelector('#main').scrollTop = 700); });
  await shot('tablet', `window.__MUSIC={}`, async p => { await p.click('.nav-b[data-view=videos]'); await wait(500); }, { width: 900, height: 600, deviceScaleFactor: 1 });
  await b.close();
})();
