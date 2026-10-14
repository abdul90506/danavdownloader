const puppeteer = require('puppeteer'); const fs = require('fs'); const H = process.env.HOME;
const mock = fs.readFileSync(__dirname + '/mock.js', 'utf8');
(async () => {
  const b = await puppeteer.launch({ headless: true, args: ['--no-sandbox', '--allow-file-access-from-files'] });
  const p = await b.newPage(); await p.setViewport({ width: 390, height: 820, deviceScaleFactor: 1, hasTouch: true, isMobile: true });
  await p.evaluateOnNewDocument(`window.__TH='${H}/.cache/sh/th';window.__MUSIC={};\n${mock}`);
  await p.goto(`file://${H}/vidra-android/app/src/main/assets/app/index.html`); await new Promise(r => setTimeout(r, 500));
  await p.click('#mini'); await new Promise(r => setTimeout(r, 800));
  const r = await p.evaluate(() => { const out = []; for (const y of [600, 650, 700, 740, 760, 790, 810]) for (const x of [50, 195, 340]) { const e = document.elementFromPoint(x, y); out.push(`${x},${y}: ${e && (e.closest('button,[id]') || e).id || e.className}`); } return out.join('\n') + '\nnp rect ' + JSON.stringify(document.querySelector('#np').getBoundingClientRect()) + ' ih ' + innerHeight; });
  console.log(r); await b.close();
})();
