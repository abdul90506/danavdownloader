/* Danav AI — chat, history, thinking, web search, attachments, voices and the live voice call.
   All model/key handling is native (Ai.java / LiveCall.java); this file never sees a key or a model name. */
'use strict';
(function () {
  const V = window.V; if (!V) return;
  const $ = (s, r) => (r || document).querySelector(s);
  const esc = V.esc, ic = (n, c) => V.svg(n, c);
  const LS = { on: 'danav.aiMode', chats: 'danav.ai.chats', level: 'danav.ai.level2', voice: 'danav.ai.voice', cc: 'danav.ai.cc', ovl: 'danav.ai.overlayAsked' };
  const LOGO = '<svg viewBox="0 0 48 48" aria-hidden="true"><rect width="48" height="48" rx="8" fill="#dc2626"/><path d="M15 13.5h7.5a10.5 10.5 0 0 1 0 21H15z" stroke="#fff" stroke-width="4.4" fill="none" stroke-linejoin="round"/><path d="M20.5 19.6v8.8l7-4.4z" fill="#fff"/></svg>';
  const MENU = '<svg class="ic" viewBox="0 -960 960 960" fill="currentColor" aria-hidden="true"><path d="M150-620q-13 0-21.5-8.5T120-650q0-13 8.5-21.5T150-680h660q13 0 21.5 8.5T840-650q0 13-8.5 21.5T810-620H150Zm0 340q-13 0-21.5-8.5T120-310q0-13 8.5-21.5T150-340h420q13 0 21.5 8.5T600-310q0 13-8.5 21.5T570-280H150Z"/></svg>';
  const LEVELS = [
    { id: 'auto', name: 'Auto' },
    { id: 'minimal', name: 'Minimal' },
    { id: 'low', name: 'Low' },
    { id: 'medium', name: 'Medium' },
    { id: 'high', name: 'High' },
  ];
  const VOICES = [['Zephyr', 'Bright'], ['Puck', 'Upbeat'], ['Charon', 'Informative'], ['Kore', 'Firm'], ['Fenrir', 'Excitable'], ['Leda', 'Youthful'], ['Orus', 'Firm'], ['Aoede', 'Breezy'], ['Callirrhoe', 'Easy-going'], ['Autonoe', 'Bright'], ['Enceladus', 'Breathy'], ['Iapetus', 'Clear'], ['Umbriel', 'Easy-going'], ['Algieba', 'Smooth'], ['Despina', 'Smooth'], ['Erinome', 'Clear'], ['Algenib', 'Gravelly'], ['Rasalgethi', 'Informative'], ['Laomedeia', 'Upbeat'], ['Achernar', 'Soft'], ['Alnilam', 'Firm'], ['Schedar', 'Even'], ['Gacrux', 'Mature'], ['Pulcherrima', 'Forward'], ['Achird', 'Friendly'], ['Zubenelgenubi', 'Casual'], ['Vindemiatrix', 'Gentle'], ['Sadachbia', 'Lively'], ['Sadaltager', 'Knowledgeable'], ['Sulafat', 'Warm']];
  const fav = (host) => 'https://www.google.com/s2/favicons?sz=64&domain=' + encodeURIComponent(host || '');
  const favImg = (host, cls) => `<img class="fav ${cls || ''}" src="${fav(host)}" alt="" loading="lazy" onerror="this.style.visibility='hidden'">`;

  // ---------------------------------------------------------------- state
  let chats = load();
  let cur = null;                 // current chat object (null = new, unsaved)
  let pend = [];                  // attachments waiting in the composer
  let level = localStorage.getItem(LS.level) || 'auto';
  let voice = localStorage.getItem(LS.voice) || 'Puck';
  const streams = new Map();      // reqId -> { chat, msg }
  let root = null, built = false;

  function load() {
    try {
      const a = JSON.parse(localStorage.getItem(LS.chats) || '[]');
      a.forEach((c) => c.messages.forEach((m) => { if (m.st && m.st !== 'done' && m.st !== 'error') { m.st = 'done'; if (!m.text) m.stopped = true; } }));
      return a;
    } catch (e) { return []; }
  }
  let saveT = 0;
  const clean = (m) => { const o = Object.assign({}, m); delete o.open; delete o.ck; return o; };
  function save(now) {
    clearTimeout(saveT);
    const run = () => {
      let data = chats.filter((c) => c.messages.length);
      for (let tries = 0; tries < 20; tries++) {
        try { localStorage.setItem(LS.chats, JSON.stringify(data.map((c) => ({ id: c.id, title: c.title, at: c.at, messages: c.messages.map(clean) })))); return; } catch (e) {
          if (data.length <= 1) return;                      // storage full: drop the oldest chat and retry
          const old = data.reduce((a, b) => (a.at < b.at ? a : b));
          data = data.filter((x) => x !== old); chats = chats.filter((c) => c !== old); delAtts(old);
        }
      }
    };
    if (now) run(); else saveT = setTimeout(run, 400);
  }
  const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
  const attUrl = (id) => 'https://ai.danav/att/' + encodeURIComponent(id);
  function delAtts(chat) {
    const ids = []; chat.messages.forEach((m) => (m.att || []).forEach((a) => ids.push(a.id)));
    if (ids.length) V.call('aiDeleteAtt', JSON.stringify(ids));
  }

  // ---------------------------------------------------------------- markdown
  function inline(s) {
    const codes = [];
    s = s.replace(/`([^`\n]+)`/g, (_, c) => { codes.push(c); return '\u0001' + (codes.length - 1) + '\u0001'; });
    s = esc(s);
    s = s.replace(/\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2">$1</a>');
    s = s.replace(/(^|[\s(])(https?:\/\/[^\s<)]+[^\s<).,;:!?'"])/g, '$1<a href="$2">$2</a>');
    s = s.replace(/\*\*\*([^*\n]+?)\*\*\*/g, '<b><i>$1</i></b>')
      .replace(/\*\*([^\n]+?)\*\*/g, '<b>$1</b>')
      .replace(/(^|[^\w])__([^_\n]+?)__(?!\w)/g, '$1<b>$2</b>')
      .replace(/(^|[^*\w])\*(?!\s)([^*\n]+?)\*(?!\w)/g, '$1<i>$2</i>')
      .replace(/(^|[^_\w])_(?!\s)([^_\n]+?)_(?!\w)/g, '$1<i>$2</i>')
      .replace(/~~([^~\n]+)~~/g, '<s>$1</s>');
    return s.replace(/\u0001(\d+)\u0001/g, (_, n) => '<code>' + esc(codes[n]) + '</code>');
  }
  const KW = new Set('abstract and as async await break case catch class const continue def default defer del delete do elif else enum except export extends false False final finally fn for from func function go if impl implements import in instanceof interface is lambda let loop match mod mut namespace new nil None not null or package pass private protected pub public raise return select self Self static struct super switch template this throw throws trait true True try type typeof undefined union unsafe use using val var void when where while with yield int float double char bool boolean string str long short byte unsigned signed auto echo print println'.split(' '));
  function hl(code, lang) {
    const hash = /^(py|python|sh|bash|shell|zsh|console|rb|ruby|ya?ml|r|perl|pl|toml|dockerfile|make|makefile|conf|ini|ps1|powershell|elixir|nim)$/i.test(lang);
    const html = /^(html|xml|svg|vue)$/i.test(lang);
    const re = new RegExp([
      '(' + (html ? '<!--[\\s\\S]*?-->' : '\\/\\*[\\s\\S]*?\\*\\/|' + (lang && /^(sql|lua|haskell|hs)$/i.test(lang) ? '--[^\\n]*' : '\\/\\/[^\\n]*') + (hash ? '|#[^\\n]*' : '')) + ')',
      '("(?:\\\\.|[^"\\\\\\n])*"|\'(?:\\\\.|[^\'\\\\\\n])*\'|`(?:\\\\.|[^`\\\\])*`)',
      '(\\b(?:0x[\\da-fA-F]+|\\d+(?:\\.\\d+)?(?:e[+-]?\\d+)?)\\b)',
      '([A-Za-z_$][\\w$]*)(?=\\s*\\()',
      '([A-Za-z_$][\\w$]*)',
    ].join('|'), 'g');
    let out = '', last = 0, m;
    while ((m = re.exec(code))) {
      out += esc(code.slice(last, m.index)); last = re.lastIndex;
      if (m[1]) out += '<span class="hl-c">' + esc(m[1]) + '</span>';
      else if (m[2]) out += '<span class="hl-s">' + esc(m[2]) + '</span>';
      else if (m[3]) out += '<span class="hl-n">' + esc(m[3]) + '</span>';
      else if (m[4]) out += KW.has(m[4]) ? '<span class="hl-k">' + esc(m[4]) + '</span>' : '<span class="hl-f">' + esc(m[4]) + '</span>';
      else if (m[5]) out += KW.has(m[5]) ? '<span class="hl-k">' + esc(m[5]) + '</span>' : esc(m[5]);
      if (m[0] === '') re.lastIndex++;
    }
    return out + esc(code.slice(last));
  }
  function codeBlock(code, lang) {
    return `<div class="cb"><div class="cb-h"><span>${esc(lang || 'code')}</span><button data-cc="1">${ic('copy')}<span>Copy</span></button></div><pre><code>${hl(code, lang)}</code></pre></div>`;
  }
  function cells(l) { return l.trim().replace(/^\|/, '').replace(/\|$/, '').split(/(?<!\\)\|/).map((c) => c.trim()); }
  function list(lines, i) {
    const items = [];
    while (i < lines.length) {
      const l = lines[i], m = l.match(/^(\s*)([-*+•]|\d+[.)])\s+(.*)$/);
      if (m) { items.push({ ind: m[1].replace(/\t/g, '    ').length, ord: /\d/.test(m[2]), start: parseInt(m[2], 10), text: m[3] }); i++; continue; }
      if (/^\s*$/.test(l)) { if (i + 1 < lines.length && /^(\s{2,}\S|\s*([-*+•]|\d+[.)])\s+)/.test(lines[i + 1])) { i++; continue; } break; }
      if (/^\s{2,}\S/.test(l) && !/^\s*(```|~~~)/.test(l) && items.length) { items[items.length - 1].text += '\n' + l.trim(); i++; continue; }
      break;
    }
    let h = ''; const st = [];
    for (const it of items) {
      while (st.length > 1 && it.ind < st[st.length - 1].ind) h += '</li></' + st.pop().tag + '>';
      const top = st[st.length - 1];
      if (!top || it.ind > top.ind + 1) { const tag = it.ord ? 'ol' : 'ul'; st.push({ ind: it.ind, tag }); h += `<${tag}${it.ord && it.start > 1 ? ` start="${it.start}"` : ''}>`; } else h += '</li>';
      h += '<li>' + inline(it.text).replace(/\n/g, '<br>');
    }
    while (st.length) h += '</li></' + st.pop().tag + '>';
    return [h, i];
  }
  function md(src) {
    const lines = String(src || '').replace(/\r/g, '').split('\n');
    let h = '', i = 0; const para = [];
    const flush = () => { if (para.length) { h += '<p>' + inline(para.join('\n')).replace(/\n/g, '<br>') + '</p>'; para.length = 0; } };
    while (i < lines.length) {
      const l = lines[i]; let m;
      if ((m = l.match(/^\s*(`{3,}|~{3,})\s*([\w+#.-]*).*$/))) {
        flush(); const fence = m[1][0], lang = m[2], buf = []; i++;
        while (i < lines.length && !new RegExp('^\\s*\\' + fence + '{3,}\\s*$').test(lines[i])) buf.push(lines[i++]);
        i++; h += codeBlock(buf.join('\n'), lang); continue;
      }
      if (/^\s*$/.test(l)) { flush(); i++; continue; }
      if ((m = l.match(/^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/))) { flush(); const n = Math.min(6, m[1].length); h += `<h${n}>${inline(m[2])}</h${n}>`; i++; continue; }
      if (/^\s{0,3}([-*_])(\s*\1){2,}\s*$/.test(l)) { flush(); h += '<hr>'; i++; continue; }
      if (/^\s*>/.test(l)) { flush(); const b = []; while (i < lines.length && /^\s*>/.test(lines[i])) b.push(lines[i++].replace(/^\s*>\s?/, '')); h += '<blockquote>' + md(b.join('\n')) + '</blockquote>'; continue; }
      if (/\|/.test(l) && i + 1 < lines.length && /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/.test(lines[i + 1])) {
        flush(); const head = cells(l), al = cells(lines[i + 1]).map((c) => (/^:-+:$/.test(c) ? 'center' : /-:$/.test(c) ? 'right' : ''));
        i += 2; let t = '<div class="tbl"><table><thead><tr>' + head.map((c, k) => `<th${al[k] ? ` style="text-align:${al[k]}"` : ''}>${inline(c)}</th>`).join('') + '</tr></thead><tbody>';
        while (i < lines.length && /\|/.test(lines[i]) && !/^\s*$/.test(lines[i])) { t += '<tr>' + cells(lines[i]).map((c, k) => `<td${al[k] ? ` style="text-align:${al[k]}"` : ''}>${inline(c)}</td>`).join('') + '</tr>'; i++; }
        h += t + '</tbody></table></div>'; continue;
      }
      if (/^\s*([-*+•]|\d+[.)])\s+/.test(l)) { flush(); const r = list(lines, i); h += r[0]; i = r[1]; continue; }
      para.push(l); i++;
    }
    flush();
    return h;
  }

  // ---------------------------------------------------------------- smooth streaming (fade-in reveal)
  // Text that arrives is revealed word by word at a pace that always catches up quickly; every newly revealed piece fades in.
  // Each re-render recreates the fading spans with a negative animation-delay equal to their age, so the fade never restarts.
  const FADE = 520;
  const SA = '\uE000';
  const rv = new WeakMap();       // msg -> { shown, marks:[{p,t}] }
  function revealOf(m) { let r = rv.get(m); if (!r) { r = { shown: 0, marks: [] }; rv.set(m, r); } return r; }
  function adjust(text, p, end) {
    for (let guard = 0; guard < 4; guard++) {
      if (!(p === 0 || text[p - 1] === '\n')) return p;
      const nl = text.indexOf('\n', p), line = text.slice(p, nl < 0 ? end : nl);
      if (/^\s*(`{3,}|~{3,})/.test(line) || /^\s*\|?\s*:?-{2,}/.test(line)) { if (nl < 0 || nl + 1 > end) return end; p = nl + 1; continue; }
      const m = line.match(/^(\s*(?:#{1,6}\s+|[-*+•]\s+|\d+[.)]\s+|>\s?|\|\s*)*)/);
      return Math.min(end, p + (m ? m[0].length : 0));
    }
    return p;
  }
  function liveMd(m, r, now) {
    const text = m.text.slice(0, r.shown);
    if (!r.marks.length) return md(text);
    let src = '', last = 0;
    const ages = [];
    r.marks.forEach((mk, k) => {
      const p = Math.max(last, adjust(text, Math.min(mk.p, text.length), text.length));
      src += text.slice(last, p) + SA + String.fromCharCode(0xE100 + k);
      ages.push(now - mk.t); last = p;
    });
    src += text.slice(last);
    const html = md(src);
    let out = '', chunk = -1;
    const re = /(<[^>]*>)|([^<]+)/g; let mm;
    while ((mm = re.exec(html))) {
      if (mm[1]) {
        let t = mm[1];
        if (t.indexOf(SA) >= 0) { t = t.replace(/\uE000([\uE100-\uE1FF])/g, (_, c) => { chunk = c.charCodeAt(0) - 0xE100; return ''; }); }
        out += t; continue;
      }
      const parts = mm[2].split(SA);
      parts.forEach((pc, i) => {
        if (i > 0) { chunk = pc.charCodeAt(0) - 0xE100; pc = pc.slice(1); }
        if (!pc) return;
        out += chunk >= 0 && /\S/.test(pc) ? `<span class="fd" style="animation-delay:-${Math.round(ages[chunk])}ms">${pc}</span>` : pc;
      });
    }
    return out;
  }
  let revRaf = 0, revLast = 0;
  const revealing = new Set();
  function kickReveal(m) { revealing.add(m); if (!revRaf) { revLast = performance.now(); revRaf = requestAnimationFrame(revealTick); } }
  function revealTick(ts) {
    revRaf = 0;
    const dt = Math.min(0.1, (ts - revLast) / 1000); revLast = ts;
    const now = Date.now();
    revealing.forEach((m) => {
      const r = revealOf(m), len = m.text.length;
      if (r.shown > len) { r.shown = len; r.marks = []; }
      let changed = false;
      if (r.shown < len) {
        const backlog = len - r.shown;
        let to = r.shown + Math.max(1, Math.min(420, 60 + backlog * 1.4) * dt);   // smooth even when the whole answer arrives at once
        to = Math.min(len, Math.ceil(to));
        // finish the word
        const ws = m.text.slice(to, to + 24).search(/\s/);
        if (ws > 0) to = Math.min(len, to + ws); else if (ws < 0 && m.st === 'text' && to + 24 > len) to = len;
        if (to > r.shown) { r.marks.push({ p: r.shown, t: now }); r.shown = to; changed = true; }
      }
      while (r.marks.length && now - r.marks[0].t > FADE) r.marks.shift();
      const finished = r.shown >= len && m.st !== 'text' && m.st !== 'think' && m.st !== 'wait' && m.st !== 'tool';
      if (finished && !r.marks.length) { revealing.delete(m); paint(m); return; }
      if (changed || r.marks.length) paintText(m, r, now);
    });
    if (revealing.size) revRaf = requestAnimationFrame(revealTick);
    else if (stick) scrollEnd(false);
  }
  let lastPaint = 0;
  function paintText(m, r, now) {
    if (!cur) return;
    const i = cur.messages.indexOf(m); if (i < 0) return;
    const el = root.querySelector(`.m-ai[data-i="${i}"]`); if (!el) return;
    let box = el.querySelector('.md.ans');
    if (!box) { paint(m); box = el.querySelector('.md.ans'); if (!box) return; }
    if (now - lastPaint < 30 && r.shown < m.text.length) return;
    lastPaint = now;
    box.innerHTML = liveMd(m, r, now);
    if (stick) scrollEnd(false);
  }

  // ---------------------------------------------------------------- DOM
  function build() {
    if (built) return; built = true;
    root = document.createElement('div');
    root.id = 'ai'; root.hidden = true;
    root.innerHTML = `
      <div class="ai-mid">
        <div class="ai-bar">
          <button class="icon-btn" data-a="side" aria-label="Menu">${MENU}</button>
          <div class="ai-bar-t" id="aiTitle"></div>
          <button class="icon-btn" data-a="new" aria-label="New chat">${ic('editSquare')}</button>
        </div>
        <div class="ai-scroll" id="aiScroll"><div class="ai-empty" id="aiEmpty">${LOGO}</div><div class="ai-thread" id="aiThread"></div></div>
        <button class="ai-down" id="aiDown" data-a="down" aria-label="Scroll to the latest message">${ic('arrowDown')}</button>
      </div>
      <div class="ai-comp">
        <div class="comp-box">
          <div class="comp-atts" id="aiAtts"></div>
          <textarea id="aiIn" rows="1" placeholder="Ask anything" enterkeyhint="enter"></textarea>
          <div class="comp-row">
            <button class="c-btn" data-a="attach" aria-label="Add photos and files">${ic('plus')}</button>
            <button class="c-btn lvl" data-a="level" id="aiLvl" aria-label="Thinking level"></button>
            <span class="sp"></span>
            <button class="c-go" id="aiGo" data-a="go"></button>
          </div>
        </div>
      </div>
      <div class="ai-scrim" id="aiScrim" data-a="sideClose"></div>
      <aside class="ai-side" id="aiSide">
        <div class="side-top"><label class="side-search">${ic('search')}<input id="aiFind" placeholder="Search chats" autocomplete="off"></label></div>
        <div class="side-nav">
          <button data-a="new">${ic('editSquare')}<span>New chat</span></button>
          <button data-a="call">${ic('call')}<span>Voice call</span><i class="side-v" id="sideCall"></i></button>
          <button data-a="phone">${ic('touchApp')}<span>Phone control</span><i class="side-v" id="sidePhone"></i></button>
          <button data-a="memory">${ic('bulb')}<span>What Danav remembers</span><i class="side-v" id="sideMem"></i></button>
          <button data-a="voices">${ic('voice')}<span>Voice</span><i class="side-v" id="sideVoice"></i></button>
        </div>
        <div class="side-list" id="aiList"></div>
        <div class="side-set">
          <button data-a="settings">${ic('settings')}<span>Settings</span><i class="side-v" id="sideLook"></i></button>
        </div>
        <div class="side-foot">${LOGO}<div><b>Danav AI</b> <span id="sideVer"></span><br>Developed by Dilhan</div></div>
      </aside>`;
    document.body.appendChild(root);
    root.addEventListener('click', onClick);
    const inp = $('#aiIn', root);
    inp.addEventListener('input', () => { grow(); syncGo(); });
    $('#aiFind', root).addEventListener('input', renderList);
    const sc = $('#aiScroll', root);
    sc.addEventListener('scroll', () => {
      const gap = sc.scrollHeight - sc.scrollTop - sc.clientHeight;
      $('#aiDown', root).classList.toggle('show', gap > 220);
      if (!autoScrolling) stick = gap < 80;
      $('#aiTitle', root).classList.toggle('show', sc.scrollTop > 40 && !!(cur && cur.title));
    }, { passive: true });
    // a finger on the list stops auto-follow at once (so reading while it streams is easy)
    sc.addEventListener('touchstart', () => { autoScrolling = false; }, { passive: true });
    let lpT = 0;
    $('#aiThread', root).addEventListener('touchstart', (e) => {
      const b = e.target.closest('.m-user .bub'); if (!b) return;
      lpT = setTimeout(() => { V.call('copy', b.textContent); V.toast('Copied'); }, 550);
    }, { passive: true });
    ['touchend', 'touchmove', 'touchcancel'].forEach((t) => $('#aiThread', root).addEventListener(t, () => clearTimeout(lpT), { passive: true }));
    renderLevel(); syncGo();
  }
  let stick = true, autoScrolling = false, autoT = 0;
  function grow() { const t = $('#aiIn', root); t.style.height = 'auto'; t.style.height = Math.min(t.scrollHeight, innerHeight * 0.38) + 'px'; }
  const busy = () => [...streams.values()].some((s) => s.chat === cur);
  function syncGo() {
    const go = $('#aiGo', root); if (!go) return;
    const has = $('#aiIn', root).value.trim() || pend.some((p) => !p.busy);
    const mode = busy() ? 'stop' : has ? 'send' : 'call';
    if (go.dataset.m === mode) return;
    go.dataset.m = mode;
    go.className = 'c-go is-' + mode;
    go.innerHTML = mode === 'stop' ? ic('stop') : mode === 'send' ? ic('arrowUpward') : ic('wave');
    go.setAttribute('aria-label', mode === 'stop' ? 'Stop' : mode === 'send' ? 'Send' : 'Voice call');
  }
  function renderLevel() { const l = LEVELS.find((x) => x.id === level) || LEVELS[0]; $('#aiLvl', root).innerHTML = ic('brain') + '<span>' + l.name + '</span>'; }

  // ---------------------------------------------------------------- thread rendering
  function attHtml(list, big) {
    if (!list || !list.length) return '';
    const imgs = list.filter((a) => a.kind === 'image');
    return '<div class="m-atts">' + list.map((a) => a.kind === 'image'
      ? `<img src="${attUrl(a.id)}" class="${big && imgs.length === 1 ? 'one' : ''}" alt="" loading="lazy">`
      : `<span class="f-chip"><i class="fi">${ic(a.kind === 'pdf' || a.kind === 'text' ? 'fileDoc' : 'file')}</i><span>${esc(a.name)}</span></span>`).join('') + '</div>';
  }
  function secs(ms) { const s = Math.max(1, Math.round(ms / 1000)); return s < 60 ? s + (s === 1 ? ' second' : ' seconds') : Math.floor(s / 60) + 'm ' + (s % 60) + 's'; }
  const isLive = (m) => m.st === 'wait' || m.st === 'think' || m.st === 'tool';
  function logHtml(m) {
    const log = m.log || (m.thoughts ? [{ t: 'th', s: m.thoughts }] : []);
    return log.map((e) => {
      if (e.t === 'th') return e.s ? `<div class="th-t">${md(e.s)}</div>` : '';
      if (e.t === 'search') {
        const items = (e.items || []).slice(0, 8);
        return `<div class="stp">${ic('search')}<span>${esc(e.q)}</span></div>${items.length ? `<div class="stp-favs">${items.map((it) => `<a data-a="openSrc" data-u="${esc(it.url)}" title="${esc(it.title)}">${favImg(it.host)}<span>${esc(it.host)}</span></a>`).join('')}</div>` : (e.done ? '<div class="stp-none">No results</div>' : '')}`;
      }
      if (e.t === 'read') return `<div class="stp">${favImg(e.host, 'sm')}<span>Read ${esc(e.host || e.url)}</span></div>`;
      return '';
    }).join('');
  }
  function liveLabel(m) {
    const log = m.log || [], last = log[log.length - 1];
    if (m.st === 'tool' && last) return last.t === 'search' ? 'Searching the web' : last.t === 'read' ? 'Reading ' + (last.host || 'the page') : 'Thinking';
    return 'Thinking';
  }
  function aiInner(m, i) {
    let h = '';
    const live = isLive(m);
    const hasLog = (m.log && m.log.length) || m.thoughts;
    const tools = (m.log || []).some((e) => e.t !== 'th');
    if (hasLog || live) {
      const label = live ? liveLabel(m) : (tools ? 'Searched the web · ' : 'Thought for ') + secs(m.thinkMs || 1000);
      const hosts = [];
      (m.log || []).forEach((e) => { (e.t === 'search' ? (e.items || []).map((x) => x.host) : e.t === 'read' ? [e.host] : []).forEach((h0) => { if (h0 && hosts.indexOf(h0) < 0) hosts.push(h0); }); });
      const favs = hosts.length ? `<span class="fav-stack th-favs">${hosts.slice(0, 5).map((h0) => favImg(h0)).join('')}</span>` : '';
      h += `<div class="think${live ? ' live' : ''}${m.open ? ' open' : ''}"><div class="think-h" data-a="think" data-i="${i}">${ic(live && m.st === 'tool' ? 'webSearch' : 'brain')}<b>${esc(label)}</b>${favs}${live ? '' : ic('chev', 'cv')}</div>${hasLog ? `<div class="think-b">${logHtml(m)}</div>` : ''}</div>`;
    }
    const r = rv.get(m);
    if (m.text) h += `<div class="md ans">${r && revealing.has(m) ? liveMd(m, r, Date.now()) : md(m.text)}</div>`;
    else if (!live && !m.text && m.st !== 'error' && !m.stopped) h += '<div class="md ans"></div>';
    if (m.st === 'error') h += `<div class="err">${ic('info')}<span>${esc(m.err || 'Something went wrong. Please try again.')}</span></div>`;
    else if (m.stopped && !m.text) h += '<div class="md"><p style="color:var(--faint)">Stopped</p></div>';
    const done = !live && m.st !== 'text' && !(r && revealing.has(m));
    const src = m.src || [];
    h += `<div class="m-act${done ? ' show' : ''}">${m.text ? `<button data-a="copyMsg" data-i="${i}" aria-label="Copy">${ic('copy')}</button>` : ''}<button data-a="regen" data-i="${i}" aria-label="Regenerate">${ic('retry')}</button>${src.length ? `<button class="srcs" data-a="srcs" data-i="${i}"><span class="fav-stack">${src.slice(0, 4).map((s) => favImg(s.host)).join('')}</span><span>Sources</span></button>` : ''}</div>`;
    return h;
  }
  function msgHtml(m, i) {
    if (m.role === 'user') return `<div class="m-user" data-i="${i}">${attHtml(m.att, true)}${m.text ? `<div class="bub">${esc(m.text)}</div>` : ''}</div>`;
    return `<div class="m-ai" data-i="${i}">${aiInner(m, i)}</div>`;
  }
  function renderThread() {
    const th = $('#aiThread', root), msgs = cur ? cur.messages : [];
    th.innerHTML = msgs.map(msgHtml).join('');
    $('#aiEmpty', root).hidden = msgs.length > 0;
    $('#aiTitle', root).textContent = cur && cur.title ? cur.title : '';
    fixLast();
  }
  function fixLast() {
    const els = root.querySelectorAll('.m-ai'); els.forEach((e, k) => { const b = e.querySelector('[data-a="regen"]'); if (b) b.hidden = k !== els.length - 1; });
  }
  function scrollEnd(smooth) {
    const sc = $('#aiScroll', root);
    autoScrolling = true; clearTimeout(autoT);
    sc.scrollTo({ top: sc.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
    stick = true;
    autoT = setTimeout(() => { autoScrolling = false; }, smooth ? 600 : 80);
  }
  /** Re-draws the parts of an assistant message other than the streaming text (thinking, steps, actions). */
  function paint(m) {
    if (!cur) return;
    const i = cur.messages.indexOf(m); if (i < 0) return;
    const el = root.querySelector(`.m-ai[data-i="${i}"]`); if (!el) return;
    const keep = el.querySelector('.think-b'), st = keep ? keep.scrollTop : 0;
    el.innerHTML = aiInner(m, i);
    const nb = el.querySelector('.think-b'); if (nb) nb.scrollTop = st;
    fixLast();
    if (stick) scrollEnd(false);
  }
  let paintQ = new Set(), paintT = 0;
  function queuePaint(m) { paintQ.add(m); if (!paintT) paintT = setTimeout(() => { paintT = 0; paintQ.forEach(paint); paintQ.clear(); }, 60); }

  // ---------------------------------------------------------------- chat flow
  function history(upto) {
    const out = [];
    for (const m of upto) {
      if (m.role === 'model' && (m.st === 'error' || !m.text)) continue;
      const it = { role: m.role, text: m.text || '', att: (m.att || []).map((a) => ({ id: a.id, name: a.name, mime: a.mime, kind: a.kind })) };
      const prev = out[out.length - 1];
      if (prev && prev.role === it.role) { prev.text = [prev.text, it.text].filter(Boolean).join('\n\n'); prev.att = prev.att.concat(it.att); } else out.push(it);
    }
    while (out.length && out[0].role !== 'user') out.shift();
    return out;
  }
  function ask(chat) {
    const msg = { role: 'model', text: '', log: [], st: 'wait', t0: Date.now() };
    const hist = history(chat.messages);
    chat.messages.push(msg);
    const id = 'q' + uid();
    streams.set(id, { chat, msg });
    if (chat === cur) {
      const th = $('#aiThread', root), i = chat.messages.length - 1;
      th.insertAdjacentHTML('beforeend', msgHtml(msg, i)); fixLast(); scrollEnd(true);
    }
    syncGo();
    V.call('aiSend', id, JSON.stringify({ messages: hist, level }));
    if (!window.Vidra || !window.Vidra.aiSend) onAi({ id, t: 'error', d: 'Danav AI needs the Danav app.' });
  }
  function send() {
    const inp = $('#aiIn', root), text = inp.value.trim();
    if (pend.some((p) => p.busy)) { V.toast('Wait for the files to finish adding'); return; }
    if (!text && !pend.length) return;
    // Danav is on a call the user placed: what they type is said out loud on that call
    if (text && !pend.length && V.call('liveSay', text)) {
      inp.value = ''; grow(); syncGo();
      V.toast('Said it on the call');
      return;
    }
    if (!cur) { cur = { id: uid(), title: '', at: Date.now(), messages: [] }; chats.unshift(cur); }
    const last = cur.messages[cur.messages.length - 1];
    if (last && last.role === 'model' && last.st === 'error') cur.messages.pop();
    const um = { role: 'user', text, att: pend.map(({ id, name, mime, kind, size }) => ({ id, name, mime, kind, size })) };
    cur.messages.push(um); cur.at = Date.now();
    pend = []; renderAtts(); inp.value = ''; grow();
    renderThread(); scrollEnd(false);
    ask(cur); save(); renderList();
  }
  function lastTh(m) { const l = m.log[m.log.length - 1]; if (l && l.t === 'th') return l; const e = { t: 'th', s: '' }; m.log.push(e); return e; }
  function onAi(e) {
    const s = streams.get(e.id); if (!s) return;
    const m = s.msg, now = Date.now();
    if (!m.log) m.log = [];
    switch (e.t) {
      case 'thought': lastTh(m).s += e.d || ''; if (m.st === 'wait' || m.st === 'tool') m.st = 'think'; queuePaint(m); break;
      case 'tool': {
        const d = e.d || {};
        m.ck = { text: m.text.length, log: JSON.stringify(m.log) };
        m.log.push(d.k === 'search' ? { t: 'search', q: d.q || '', items: [] } : { t: 'read', url: d.url || '', host: d.host || '' });
        m.st = 'tool'; queuePaint(m); break;
      }
      case 'found': {
        const d = e.d || {};
        for (let k = m.log.length - 1; k >= 0; k--) if (m.log[k].t === 'search' && m.log[k].q === d.q && !m.log[k].done) { m.log[k].items = d.items || []; m.log[k].done = true; break; }
        m.ck = { text: m.text.length, log: JSON.stringify(m.log) };
        queuePaint(m); break;
      }
      case 'src': m.src = Array.isArray(e.d) ? e.d : []; break;
      case 'text': {
        const first = !m.text;
        revealOf(m); revealing.add(m);
        if (first && (m.log.length || m.st === 'think')) m.thinkMs = now - m.t0;
        m.text += e.d || '';
        const was = m.st; m.st = 'text';
        if (first || was !== 'text') paint(m);
        kickReveal(m);
        break;
      }
      case 'reset':
        if (m.ck) { m.text = m.text.slice(0, m.ck.text); try { m.log = JSON.parse(m.ck.log); } catch (x) { m.log = []; } }
        else { m.text = ''; m.log = []; m.t0 = now; }
        m.st = m.log.length ? 'tool' : 'wait';
        { const r = rv.get(m); if (r) { r.shown = Math.min(r.shown, m.text.length); r.marks = []; } }
        paint(m); break;
      case 'done': case 'stopped':
        if ((m.log.length) && !m.thinkMs) m.thinkMs = now - m.t0;
        m.st = 'done'; if (e.t === 'stopped') m.stopped = true;
        if (e.t === 'stopped') { const r = rv.get(m); if (r) { r.shown = m.text.length; } }
        streams.delete(e.id); delete m.ck; finish(s.chat);
        if (revealing.has(m)) kickReveal(m); else paint(m);
        break;
      case 'error':
        m.st = 'error'; m.err = e.d; streams.delete(e.id); delete m.ck; finish(s.chat); paint(m); break;
    }
  }
  function finish(chat) {
    save(true); syncGo();
    if (!chat.title && !chat.naming) {
      const first = chat.messages.find((x) => x.role === 'user');
      const ans = chat.messages.find((x) => x.role === 'model' && x.text);
      if (!first) return;
      const fallback = (first.text || (first.att && first.att[0] && first.att[0].name) || 'New chat').replace(/\s+/g, ' ').slice(0, 40);
      if (!ans) { chat.title = fallback; renderList(); save(); return; }
      chat.naming = true;
      const seed = 'User: ' + (first.text || '[attachment: ' + (first.att || []).map((a) => a.name).join(', ') + ']').slice(0, 600) + '\nAssistant: ' + ans.text.slice(0, 400);
      V.callAsync('aiTitle', seed).then((r) => {
        chat.naming = false;
        chat.title = (r && r.title) ? String(r.title).replace(/^["'\s]+|["'.\s]+$/g, '').slice(0, 60) : fallback;
        chat.fresh = true; renderList(); save();
        if (chat === cur) $('#aiTitle', root).textContent = chat.title;
      });
    }
  }
  function stop() { streams.forEach((s, id) => { if (s.chat === cur) V.call('aiStop', id); }); }
  function regen(i) {
    if (!cur || busy()) return;
    const m = cur.messages[i]; if (!m || m.role !== 'model') return;
    cur.messages.splice(i); renderThread(); ask(cur); save();
  }
  function showSources(m) {
    const src = m.src || [];
    V.openSheet(`<div class="sheet-h">Sources</div><div class="src-list">${src.map((s, k) => `<button class="src-i" data-act="s${k}">${favImg(s.host)}<span class="src-t"><b>${esc(s.title || s.host)}</b><small>${esc(s.host)}</small></span></button>`).join('')}</div>`, (a) => {
      const s = src[+a.slice(1)]; if (s) V.call('openBrowser', s.url, 'detect');
    });
  }

  // ---------------------------------------------------------------- attachments
  function renderAtts() {
    const box = $('#aiAtts', root);
    box.innerHTML = pend.map((a, k) => `<div class="c-att${a.busy ? ' busy' : ''}">${a.kind === 'image' && !a.busy ? `<img src="${attUrl(a.id)}" alt="">` : `<span class="f-chip"><i class="fi">${ic(a.kind === 'image' ? 'image' : 'fileDoc')}</i><span>${esc(a.name)}</span></span>`}<button class="x" data-a="unatt" data-k="${k}" aria-label="Remove">${ic('close')}</button></div>`).join('');
    syncGo();
  }
  function pick(kind) {
    if (pend.length >= 10) { V.toast('You can add up to 10 files'); return; }
    V.call('aiPick', kind);
  }
  V.on('aiAtt', (r) => {
    (r.items || []).forEach((a) => { if (pend.length < 10) pend.push(a); });
    renderAtts();
    if (r.errors && r.errors.length) V.toast(r.errors[0]);
  });

  // ---------------------------------------------------------------- popovers
  let pop = null;
  function closePop() { if (!pop) return false; if (pop.cleanup) pop.cleanup(); pop.remove(); pop.veil.remove(); pop = null; return true; }
  function openPop(anchor, html, onPick, above, cls) {
    closePop();
    const veil = document.createElement('div'); veil.className = 'ai-veil';
    const p = document.createElement('div'); p.className = 'ai-pop ' + (cls || ''); p.innerHTML = html;
    document.body.append(veil, p);
    // where is it now? follow the anchor while the screen changes (keyboard opening / closing, scrolling)
    const place = () => {
      if (!p.isConnected) return;
      const r = anchor.getBoundingClientRect(), pw = p.offsetWidth, ph = p.offsetHeight;
      // the visible part of the page: the keyboard takes the bottom away, so measure against that
      const vv = window.visualViewport;
      const vh = vv ? vv.height : innerHeight, vw = vv ? vv.width : innerWidth, vtop = vv ? vv.offsetTop : 0, vleft = vv ? vv.offsetLeft : 0;
      let left = Math.min(Math.max(vleft + 8, r.left), vleft + vw - pw - 8);
      if (above !== false && r.top - ph - 8 > vtop + 8) { p.style.top = (r.top - ph - 8) + 'px'; p.style.transformOrigin = 'bottom left'; }
      else {
        p.style.top = Math.min(r.bottom + 6, vtop + vh - ph - 8) + 'px';
        p.style.transformOrigin = 'top right';
        left = Math.min(Math.max(vleft + 8, r.right - pw), vleft + vw - pw - 8);
      }
      p.style.left = left + 'px';
    };
    place();
    // the input keeps its text and gets focus back the moment you pick something, so the keyboard
    // stays where it was instead of the menu hanging in the air
    const wasIn = document.activeElement === $('#aiIn', root);
    const onMove = () => place();
    window.addEventListener('resize', onMove);
    window.addEventListener('scroll', onMove, true);
    if (window.visualViewport) { visualViewport.addEventListener('resize', onMove); visualViewport.addEventListener('scroll', onMove); }
    p.cleanup = () => {
      window.removeEventListener('resize', onMove);
      window.removeEventListener('scroll', onMove, true);
      if (window.visualViewport) { visualViewport.removeEventListener('resize', onMove); visualViewport.removeEventListener('scroll', onMove); }
    };
    veil.onclick = closePop;
    p.onclick = (e) => {
      const b = e.target.closest('[data-p]'); if (!b) return;
      closePop(); onPick(b.dataset.p);
      if (wasIn) { const t = $('#aiIn', root); if (t) t.focus(); }
    };
    pop = p; pop.veil = veil;
  }

  // ---------------------------------------------------------------- sidebar & history
  function group(t) {
    const d = new Date(t), now = new Date(), day = 864e5;
    const start = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    if (t >= start) return 'Today';
    if (t >= start - day) return 'Yesterday';
    if (t >= start - 7 * day) return 'Previous 7 days';
    if (t >= start - 30 * day) return 'Previous 30 days';
    return d.toLocaleDateString(undefined, { month: 'long', year: d.getFullYear() === now.getFullYear() ? undefined : 'numeric' });
  }
  function renderList() {
    if (!root) return;
    const q = ($('#aiFind', root).value || '').trim().toLowerCase();
    const list = chats.filter((c) => c.messages.length).sort((a, b) => b.at - a.at)
      .filter((c) => !q || (c.title || '').toLowerCase().includes(q) || c.messages.some((m) => (m.text || '').toLowerCase().includes(q)));
    let h = '', g = '';
    list.forEach((c) => {
      const gg = group(c.at);
      if (gg !== g) { g = gg; h += `<div class="side-h">${esc(g)}</div>`; }
      h += `<button class="chat-i${c === cur ? ' on' : ''}${c.fresh ? ' fresh' : ''}" data-a="openChat" data-id="${c.id}"><span>${esc(c.title || 'New chat')}</span><i class="more" data-a="chatMenu" data-id="${c.id}">${ic('more')}</i></button>`;
      c.fresh = false;
    });
    $('#aiList', root).innerHTML = h || `<div class="side-empty">${q ? 'No chats found' : 'Your chats will show up here'}</div>`;
  }
  let sideOpen = false;
  function renderSide() {
    if (!root) return;
    const st = phoneState(), w = st.wake || {}, l = (() => { try { return JSON.parse(V.call('liveInfo') || '{}'); } catch (e) { return {}; } })();
    const set = (id, t) => { const el = $('#' + id, root); if (el) el.textContent = t; };
    set('sideCall', l.active ? (l.muted ? 'Muted' : 'On a call') : '');
    set('sidePhone', st.access ? (st.overlay ? 'Ready' : 'Overlay needed') : 'Off');
    set('sideMem', (st.memory || []).length ? (st.memory || []).length + ' notes' : '');
    set('sideVoice', localStorage.getItem('danav.ai.voice') || 'Puck');
    set('sideLook', V.setLabel() + (V.SET.compact ? ' · Compact' : ''));
    set('sideVer', V.state.version ? 'v' + V.state.version : '');
  }
  function side(on) {
    sideOpen = on;
    if (on) { renderList(); $('#aiIn', root).blur(); }
    $('#aiSide', root).classList.toggle('show', on);
    if (on) renderSide();
    $('#aiScrim', root).classList.toggle('show', on);
  }
  function openChat(id) {
    const c = chats.find((x) => x.id === id); if (!c) return;
    cur = c; side(false); renderThread(); requestAnimationFrame(() => scrollEnd(false)); syncGo();
  }
  function newChat() {
    side(false);
    if (cur && !cur.messages.length) return;
    cur = null; pend = []; renderAtts(); renderThread(); syncGo();
    setTimeout(() => $('#aiIn', root).focus(), 250);
  }
  function rename(c) {
    const sh = V.openSheet(`<div class="sheet-t">Rename chat</div><div style="padding:4px 20px 8px"><input id="aiRen" maxlength="80" style="width:100%;height:44px;border:1px solid var(--line2);border-radius:10px;padding:0 12px;background:var(--bg);color:var(--text);font-size:15px;outline:0"></div>
      <div class="sheet-btns"><button class="btn" data-act="no">Cancel</button><button class="btn primary" data-act="yes">Save</button></div>`, (a) => {
      if (a === 'yes') { const v = $('#aiRen').value.trim(); if (v) { c.title = v; save(); renderList(); if (c === cur) $('#aiTitle', root).textContent = v; } }
    });
    const inp = $('#aiRen', sh); inp.value = c.title || ''; setTimeout(() => { inp.focus(); inp.select(); }, 200);
  }
  async function remove(c) {
    if (!(await V.confirm('Delete chat?', `"${c.title || 'New chat'}" will be deleted.`, 'Delete', true))) return;
    streams.forEach((s, id) => { if (s.chat === c) { V.call('aiStop', id); streams.delete(id); } });
    delAtts(c); chats = chats.filter((x) => x !== c); save(true);
    if (c === cur) { cur = null; renderThread(); syncGo(); }
    renderList();
  }

  // ---------------------------------------------------------------- voices (one at a time, swipe for the next)
  let page = null;
  const pv = { voice: null, state: '', a: new Float32Array(16), s: new Float32Array(16), raf: 0, idx: 0, t: 0 };
  function closePage() {
    if (!page) return false;
    const p = page; page = null; p.classList.remove('show'); V.call('voicePreviewStop'); pv.voice = null; pv.state = '';
    cancelAnimationFrame(pv.raf); pv.raf = 0;
    setTimeout(() => p.remove(), 320); return true;
  }
  function voicesPage() {
    side(false);
    const p = document.createElement('div'); p.className = 'ai-page vp';
    pv.idx = Math.max(0, VOICES.findIndex((v) => v[0] === voice));
    p.innerHTML = `<div class="ai-page-h"><button class="icon-btn" data-v="back" aria-label="Back">${ic('back')}</button><h3>Voice</h3></div>
      <div class="vp-body">
        <canvas class="vp-orb"></canvas>
        <div class="vp-track" id="vpTrack">${VOICES.map(([n, d]) => `<div class="vp-card" data-n="${n}"><div class="vp-name"><b>${n}</b><span>${d}</span></div></div>`).join('')}</div>
      </div>
      <div class="vp-foot">
        <div class="vp-dots" id="vpDots"></div>
        <div class="vp-act">
          <button class="vp-nav" data-v="prev" aria-label="Previous voice">${ic('chevLeft')}</button>
          <button class="vp-play" data-v="play" id="vpPlay" aria-label="Play sample">${ic('play')}</button>
          <button class="vp-nav" data-v="next" aria-label="Next voice">${ic('chev')}</button>
        </div>
        <button class="vp-sel" data-v="pick" id="vpSel"></button>
        <button class="vp-clear" data-v="clear">Delete all chats</button>
      </div>`;
    document.body.appendChild(p); page = p;
    const tr = $('#vpTrack', p);
    requestAnimationFrame(() => { tr.scrollLeft = pv.idx * tr.clientWidth; syncVp(); requestAnimationFrame(() => p.classList.add('show')); });
    let st = 0;
    tr.addEventListener('scroll', () => {
      clearTimeout(st);
      st = setTimeout(() => {
        const i = Math.round(tr.scrollLeft / Math.max(1, tr.clientWidth));
        if (i !== pv.idx) { pv.idx = i; syncVp(); playVoice(VOICES[i][0]); }
      }, 140);
    }, { passive: true });
    p.onclick = async (e) => {
      const b = e.target.closest('[data-v]'); if (!b) return;
      const a = b.dataset.v;
      if (a === 'back') closePage();
      else if (a === 'play') { const n = VOICES[pv.idx][0]; if (pv.voice === n && (pv.state === 'playing' || pv.state === 'loading')) { V.call('voicePreviewStop'); pv.voice = null; pv.state = ''; syncVp(); } else playVoice(n); }
      else if (a === 'prev' || a === 'next') { const i = Math.max(0, Math.min(VOICES.length - 1, pv.idx + (a === 'next' ? 1 : -1))); tr.scrollTo({ left: i * tr.clientWidth, behavior: 'smooth' }); }
      else if (a === 'pick') { voice = VOICES[pv.idx][0]; localStorage.setItem(LS.voice, voice); syncVp(); V.toast(voice + ' selected'); }
      else if (a === 'clear') {
        if (!(await V.confirm('Delete all chats?', 'All Danav AI chats and their files will be deleted from this phone.', 'Delete all', true))) return;
        streams.forEach((s, id) => V.call('aiStop', id)); streams.clear();
        chats.forEach(delAtts); chats = []; cur = null; save(true); renderThread(); renderList(); syncGo();
        V.toast('All chats deleted');
      }
    };
    pv.raf = requestAnimationFrame(drawOrb);
  }
  function playVoice(n) {
    pv.voice = n; pv.state = 'loading'; syncVp();
    V.call('voicePreview', n);
    if (!window.Vidra || !window.Vidra.voicePreview) { pv.state = 'error'; syncVp(); }
  }
  function syncVp() {
    if (!page) return;
    const n = VOICES[pv.idx][0], on = n === voice, mine = pv.voice === n;
    const sel = $('#vpSel', page); sel.textContent = on ? 'Selected' : 'Use ' + n; sel.classList.toggle('on', on);
    const pl = $('#vpPlay', page);
    pl.innerHTML = mine && (pv.state === 'playing' || pv.state === 'loading') ? ic('stop') : ic('play');
    $('#vpDots', page).textContent = (pv.idx + 1) + ' / ' + VOICES.length;
    page.querySelectorAll('.vp-nav')[0].disabled = pv.idx === 0;
    page.querySelectorAll('.vp-nav')[1].disabled = pv.idx === VOICES.length - 1;
  }
  V.on('voicePreview', (r) => {
    if (r.voice !== pv.voice) return;
    if (r.state === 'lv') { for (let i = 0; i < 16; i++) pv.a[i] = (r.a && r.a[i]) || 0; return; }
    pv.state = r.state;
    if (r.state === 'done' || r.state === 'error') { pv.voice = null; pv.a.fill(0); }
    syncVp();
    if (r.state === 'error') V.toast('Couldn\'t play this voice. Check your internet connection.');
  });
  // the preview orb: a soft glowing sphere with a ring of light bars that follows the voice sample
  function drawOrb(ts) {
    if (!page) return;
    const cv = page.querySelector('.vp-orb'); if (!cv) return;
    const dpr = Math.min(2, devicePixelRatio || 1), W = cv.clientWidth, H = cv.clientHeight;
    if (cv.width !== Math.round(W * dpr) || cv.height !== Math.round(H * dpr)) { cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr); }
    const g = cv.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, W, H);
    const t = ts / 1000; let e = 0;
    for (let i = 0; i < 16; i++) { pv.s[i] += (pv.a[i] - pv.s[i]) * (pv.a[i] > pv.s[i] ? 0.5 : 0.15); e += pv.s[i]; }
    e /= 16;
    const dark = document.documentElement.classList.contains('dark');
    const cx = W / 2, cy = H * 0.42, R = Math.min(W * 0.3, H * 0.24);
    const loading = pv.state === 'loading';
    const rr = R * (0.62 + e * 0.22 + 0.025 * Math.sin(t * 1.6));
    // body
    const gr = g.createRadialGradient(cx - rr * 0.3, cy - rr * 0.35, rr * 0.1, cx, cy, rr * 1.05);
    gr.addColorStop(0, '#ffd1c4'); gr.addColorStop(0.35, '#ff6b5a'); gr.addColorStop(0.75, '#dc2626'); gr.addColorStop(1, '#7f1d1d');
    g.globalAlpha = 1; g.fillStyle = gr; g.beginPath(); g.arc(cx, cy, rr, 0, Math.PI * 2); g.fill();
    // inner swirl highlights
    g.globalCompositeOperation = 'lighter';
    for (let k = 0; k < 3; k++) {
      const a = t * (0.6 + k * 0.25) + k * 2.1, ox = Math.cos(a) * rr * 0.35, oy = Math.sin(a * 1.3) * rr * 0.3;
      const hg = g.createRadialGradient(cx + ox, cy + oy, 0, cx + ox, cy + oy, rr * 0.7);
      hg.addColorStop(0, `rgba(255,${150 + k * 30},${90 + k * 40},${0.22 + e * 0.35})`); hg.addColorStop(1, 'rgba(0,0,0,0)');
      g.fillStyle = hg; g.beginPath(); g.arc(cx, cy, rr, 0, Math.PI * 2); g.fill();
    }
    g.globalCompositeOperation = 'source-over';
    // ring of bars
    const n = 72;
    g.lineCap = 'round';
    for (let j = 0; j < n; j++) {
      const u = j / n, th = u * Math.PI * 2 - Math.PI / 2 + t * 0.15;
      let k = u * 2; if (k > 1) k = 2 - k;
      const f = k * 15, i0 = Math.floor(f), i1 = Math.min(15, i0 + 1), fr = f - i0;
      let v = pv.s[i0] * (1 - fr) + pv.s[i1] * fr;
      if (loading) v = 0.12 + 0.12 * Math.max(0, Math.sin(t * 6 - u * Math.PI * 4));
      const r0 = rr * 1.16, r1 = r0 + 4 + v * R * 0.55;
      const hue = 350 + 40 * Math.sin(th + t * 0.5);
      g.strokeStyle = `hsla(${hue},90%,${dark ? 62 : 52}%,${0.35 + v * 0.65})`;
      g.lineWidth = 3;
      g.beginPath(); g.moveTo(cx + Math.cos(th) * r0, cy + Math.sin(th) * r0); g.lineTo(cx + Math.cos(th) * r1, cy + Math.sin(th) * r1); g.stroke();
    }
    pv.raf = requestAnimationFrame(drawOrb);
  }

  // ---------------------------------------------------------------- phone control & Hey Danav
  let pc = null;
  function phoneState() { try { return JSON.parse(V.call('phoneState') || '{}'); } catch (e) { return {}; } }
  function phonePage() {
    side(false);
    if (!window.Vidra || !window.Vidra.phoneState) { V.toast('Phone control needs the Danav app'); return; }
    const p = document.createElement('div'); p.className = 'ai-page pc';
    p.innerHTML = `<div class="ai-page-h"><button class="icon-btn" data-v="back" aria-label="Back">${ic('back')}</button><h3>Phone control</h3></div><div class="ai-page-b" id="pcBody"></div>`;
    document.body.appendChild(p); page = p; pc = p;
    renderPhone();
    requestAnimationFrame(() => p.classList.add('show'));
    p.onclick = async (e) => {
      const b = e.target.closest('[data-v]'); if (!b) return;
      const a = b.dataset.v, st = phoneState();
      if (a === 'back') { pc = null; closePage(); }
      else if (a === 'wake') toggleWake(st);
      else if (a === 'memDel') V.call('memoryForget', b.dataset.id);
      else if (a === 'memClear') { if (await V.confirm('Forget everything?', 'All the personal notes Danav saved about you will be deleted.', 'Forget', true)) V.call('memoryClear'); }
      else if (a === 'prove') {
        const t = $('#pcProve', p);
        if (t) t.textContent = 'Testing… Danav is opening Settings now';
        let o = {};
        try { o = JSON.parse((await V.callAsync('phoneProve')) || '{}'); } catch (e) { o = {}; }
        if (t) {
          t.textContent = o.ok
            ? 'Working — Danav opened ' + (o.now || 'the Settings app') + ' and came back here in ' + (o.ms || 0) + ' ms. Screen read: ' + (o.read || 0) + ' characters'
              + (o.shot === 1 ? ' · screenshot ok' : o.shot === 0 ? ' · screenshot NOT available on this phone' : ' · screenshot needs Android 11 or newer')
            : 'Not working — ' + (o.step || o.said || 'the phone gave no answer') + (o.fix ? ' · ' + o.fix : '');
          t.style.userSelect = 'text'; t.style.webkitUserSelect = 'text';
        }
      }
      else if (a === 'ask') {
        const w = b.dataset.w;
        if (w === 'access' && st.sdk >= 33) V.toast('Open “Danav AI phone control” and turn it on');
        V.call('phoneAsk', w);
      }
    };
  }
  function settingsPage() {
    if (page && page.classList.contains('set')) { closePage(); return; }
    const w = phoneState().wake || {};
    const p = document.createElement('div'); p.className = 'ai-page set';
    p.innerHTML = `<div class="ai-page-h"><button class="icon-btn" data-v="back" aria-label="Back">${ic('back')}</button><h3>Settings</h3></div>
      <div class="ai-page-b">
        <div class="sub-h">Theme</div>
        <div class="opts3" id="setTheme">${[['system', 'System', 'contrast'], ['light', 'Light', 'light'], ['dark', 'Dark', 'dark']]
          .map(([k, n, i]) => `<button data-lt="${k}" class="${V.SET.theme === k ? 'on' : ''}">${ic(i)}${n}</button>`).join('')}</div>
        <div class="sub-h">Accent</div>
        <div class="swatches" id="setAccent">${V.ACCENTS.map(([k, n, c]) => `<button data-la="${k}" style="--sw:${c}" aria-label="${n}" title="${n}" class="${(V.SET.accent || 'red') === k ? 'on' : ''}"><i></i></button>`).join('')}</div>
        <div class="sub-h">Text size</div>
        <div class="opts3" id="setSize">${[['s', 'Small', 's'], ['m', 'Normal', 'm'], ['l', 'Large', 'l']]
          .map(([k, n, c]) => `<button data-ls="${k}" class="${(V.SET.size || 'm') === k ? 'on' : ''}"><i class="aa ${c}">A</i>${n}</button>`).join('')}</div>
        <div class="lk-row"><div class="lk-t"><b>Compact</b><small>Tighter rows and close-packed messages — more on the screen at once.</small></div><button class="lk-sw${V.SET.compact ? ' on' : ''}" data-lc="1" role="switch" aria-checked="${V.SET.compact ? 'true' : 'false'}" aria-label="Compact"><i></i></button></div>
        <div class="sub-h">Shortcut</div>
        <div class="lk-row"><div class="lk-t"><b>Volume-Up ×3 anywhere</b><small id="setWakeT">${esc(w.error && !w.running ? w.error : w.on ? (w.status === 'call' ? 'On — in a call now' : 'On — ready anywhere') : 'Off')}</small></div><button class="lk-sw${w.on ? ' on' : ''}" id="setWake" role="switch" aria-checked="${w.on ? 'true' : 'false'}" aria-label="Volume-Up shortcut"><i></i></button></div>
        <div class="sub-h">Danav AI</div>
        <div class="lk-row"><div class="lk-t"><b>Test Danav AI</b><small id="setTest">Checks the connection and tells you the real reason if it isn't working</small></div><button class="lk-go" data-test="1" aria-label="Test">${ic('bolt')}</button></div>
        <div class="lk-row"><div class="lk-t"><b>App info</b><small id="setVer">${V.state.version ? 'Version ' + esc(V.state.version) : 'Version'} · opens your phone's app settings</small></div><button class="lk-go" data-appinfo="1" aria-label="App info">${ic('chev')}</button></div>
        <p class="v-note">System follows your phone's own light / dark setting. The accent colour is used for the buttons, the circle and the highlights.</p>
        <p class="v-note">Danav AI keeps everything on this phone — chats, memory, voices and this look. Nothing is uploaded.</p>
      </div>`;
    document.body.appendChild(p); page = p;
    requestAnimationFrame(() => p.classList.add('show'));
    p.onclick = async (e) => {
      const b = e.target.closest('[data-v],[data-lt],[data-la],[data-ls],[data-lc],[data-wake],[data-appinfo],[data-test]'); if (!b) return;
      if (b.dataset.v === 'back') { closePage(); return; }
      if (b.dataset.lt) { V.setLook({ theme: b.dataset.lt }); p.querySelectorAll('#setTheme button').forEach((x) => x.classList.toggle('on', x === b)); }
      else if (b.dataset.la) { V.setLook({ accent: b.dataset.la }); p.querySelectorAll('#setAccent button').forEach((x) => x.classList.toggle('on', x === b)); }
      else if (b.dataset.ls) { V.setLook({ size: b.dataset.ls }); p.querySelectorAll('#setSize button').forEach((x) => x.classList.toggle('on', x === b)); }
      else if (b.dataset.lc) { V.setLook({ compact: !V.SET.compact }); b.classList.toggle('on', !!V.SET.compact); b.setAttribute('aria-checked', V.SET.compact ? 'true' : 'false'); }
      else if (b.dataset.appinfo) V.call('openAppSettings');
      else if (b.dataset.test) {
        const t = p.querySelector('#setTest');
        if (t) t.textContent = 'Checking…';
        let o = {}, raw = '';
        try {
          raw = (await Promise.race([V.callAsync('testApi'), new Promise((r) => setTimeout(() => r(''), 30000))])) || '';
          o = JSON.parse(raw || '{}');
        } catch (err) { o = {}; }
        if (t) {
          t.textContent = o.ok
            ? 'Working — answered in ' + o.ms + ' ms · ' + (o.model || 'ok') + (o.why ? ' · ' + o.why : '')
            : (o.why ? 'Not working — ' + o.why + (o.code ? ' · HTTP ' + o.code : '')
                     : (raw ? 'Not working — ' + String(raw).slice(0, 300) : 'No reply from the app in 30 s (the request thread did not come back)'));
          t.style.userSelect = 'text'; t.style.webkitUserSelect = 'text';
        }
      }
      renderSide();
    };
    const sw = p.querySelector('#setWake');
    if (sw) sw.onclick = async () => {
      await toggleWake(phoneState());
      const nw = phoneState().wake || {};
      sw.classList.toggle('on', !!nw.on); sw.setAttribute('aria-checked', nw.on ? 'true' : 'false');
      const t = p.querySelector('#setWakeT');
      if (t) t.textContent = nw.error && !nw.running ? nw.error : nw.on ? (nw.status === 'call' ? 'On — in a call now' : 'On — ready anywhere') : 'Off';
    };
  }

  async function toggleWake(st) {
    const w = st.wake || {};
    if (w.on) { V.call('wakeSet', false); renderPhone(); return; }
    if (!st.mic) { V.call('phoneAsk', 'mic'); return; }
    const r = V.call('wakeSet', true);
    if (r === 'blocked') V.toast('Android did not allow the shortcut to start. Try again.');
    if (r === 'mic') V.call('phoneAsk', 'mic');
    if (r === 'ok' && !st.overlay) V.toast('Allow "Show over other apps" so the Danav circle can float there');
    setTimeout(renderPhone, 300);
  }
  function wakeText(w) {
    if (w.error && (w.status === 'error' || !w.running)) return w.error;
    if (!w.on) return 'Off';
    if (w.status === 'call') return 'On — in a call now';
    if (w.status === 'stopped') return 'Stopped by Android — open Danav to resume';
    if (w.status === 'ready' || w.ready) return 'On — ready anywhere';
    return 'On';
  }
  function renderPhone() {
    if (!pc) return;
    const st = phoneState(), w = st.wake || {}, body = $('#pcBody', pc); if (!body) return;
    const row = (icon, t, sub, ok, w0, extra) => `<div class="pc-row">${ic(icon)}<div class="pc-t"><b>${t}</b><small>${sub}</small>${extra || ''}</div>${ok ? `<span class="pc-ok">${ic('check')}</span>` : `<button class="pc-btn" data-v="ask" data-w="${w0}">Allow</button>`}</div>`;
    body.innerHTML = `
      <div class="pc-row">${ic('hearing')}<div class="pc-t"><b>Volume-Up ×3 shortcut</b><small>${esc(wakeText(w))}. Press <b>Volume-Up three times</b> anywhere to bring the Danav circle up, three times again to send it away. It stays until you close it — it never goes away by itself.</small></div>${w.on ? `<span class="pc-ok">${ic('check')}</span>` : `<button class="pc-btn" data-v="wake">Turn on</button>`}</div>
      <div class="sub-h">Permissions</div>
      ${row('accessibility', 'Phone control', 'Lets Danav open apps, tap, type and read the screen when you ask', st.access, 'access',
        st.access || st.sdk < 33 ? '' : `<small class="pc-hint">Setting greyed out or “restricted”? Open <a data-v="ask" data-w="appinfo">App info</a> › ⋮ › Allow restricted settings, then try again.</small>`)}
      ${row('layers', 'Show over other apps', 'The small Danav circle while you use other apps — and it lets Danav open other apps in the background', st.overlay, 'overlay')}
      ${row('apps', 'Open other apps', st.apps ? 'Danav can bring apps to the front and take you to the right screen' : esc(st.why || 'Turn on Phone control above (or “Show over other apps”): Android blocks background apps from opening other apps without it'), st.apps, st.access ? 'overlay' : 'access')}
      <div class="lk-row"><div class="lk-t"><b>Test phone control</b><small id="pcProve">Opens Settings for a moment and tells you honestly whether Danav really got it on screen — then comes back here.</small></div><button class="lk-go" data-v="prove" aria-label="Test phone control">${ic('bolt')}</button></div>
      ${row('mic', 'Microphone', 'Talk to Danav', st.mic, 'mic')}
      ${row('battery', 'No battery limits', 'Keeps the volume-up shortcut working in the background', st.battery, 'battery')}
      <div class="sub-h">What Danav remembers</div>
      ${(st.memory || []).length ? (st.memory || []).map((m) => `<div class="pc-row pc-mem"><div class="pc-t"><small>${esc(m.t || '')}</small></div><button class="pc-btn" data-v="memDel" data-id="${esc(m.id || '')}">${ic('trash')}</button></div>`).join('')
          + `<p class="v-note"><a data-v="memClear">Forget everything</a> — Danav saves these itself while you chat or talk, and uses them to answer in your own way.</p>`
        : `<p class="v-note">Nothing yet. While you chat or talk, Danav quietly saves the things that matter (your name, language, people, habits) and uses them to be more personal. You can delete any note here.</p>`}
      <p class="v-note">Danav acts on your phone only when you ask it. Nothing runs on its own in the background — the circle appears when you press Volume-Up three times (or start a call yourself).</p>`;
  }
  V.on('phone', renderPhone);
  V.on('wake', () => { renderPhone(); renderSide(); });
  V.on('resume', () => { renderPhone(); renderSide(); adoptCall(); });
  // a call started outside the page (Hey Danav while the app was closed / in the background) shows up minimized
  function adoptCall() {
    if (call || !window.Vidra || !window.Vidra.liveInfo) return;
    let i = {}; try { i = JSON.parse(V.call('liveInfo') || '{}'); } catch (e) { /* */ }
    if (i.active) { openCall({ adopt: true, muted: i.muted }); if (i.wake) setTimeout(minimizeCall, 30); }
  }
  V.on('callExternal', () => setTimeout(adoptCall, 50));

  // ---------------------------------------------------------------- voice call (+ camera and screen sharing)
  let call = null, wantCall = false, wantCam = false;
  function startCall() {
    if (call) { restoreCall(); return; }
    if (!window.Vidra || !window.Vidra.liveStart) { V.toast('Calls need the Danav app'); return; }
    if (V.call('micPerm') !== 'granted') { wantCall = true; V.call('requestMic'); return; }
    openCall();
  }
  V.on('mic', (r) => {
    if (!wantCall) return; wantCall = false;
    if (r.ok) openCall();
    else V.toast(r.blocked ? 'Allow the microphone for Danav in phone Settings › Apps' : 'Microphone permission is needed for calls');
  });
  V.on('cam', (r) => {
    if (!wantCam) return; wantCam = false;
    if (r.ok) camOn();
    else V.toast(r.blocked ? 'Allow the camera for Danav in phone Settings › Apps' : 'Camera permission is needed for video');
  });
  function outIcon(o) { return o === 'speaker' ? 'speaker' : o === 'earpiece' ? 'earpiece' : 'headphones'; }
  function openCall(o) {
    o = o || {};
    if (root) $('#aiIn', root).blur();
    const el = document.createElement('div'); el.className = 'call';
    const cc = localStorage.getItem(LS.cc) !== '0';
    el.innerHTML = `<canvas class="call-cv"></canvas><video class="call-vid" playsinline muted autoplay></video><div class="call-shade"></div>
      <div class="call-top"><button class="icon-btn" data-c="min" aria-label="Minimize">${ic('arrowDown')}</button><div class="call-id"><b>Danav AI</b><span id="callSt">Connecting…</span></div><button class="icon-btn${cc ? ' on' : ''}" data-c="cc" aria-label="Captions">${ic('cc')}</button></div>
      <canvas class="call-orb"></canvas>
      <button class="call-flip" data-c="flip" aria-label="Switch camera">${ic('flipCam')}</button>
      <div class="call-share" id="callShare"><i></i><span>Sharing your screen</span><button data-c="screen">Stop</button></div>
      <div class="call-tool" id="callTool" hidden>${ic('touchApp')}<span></span></div>
      <div class="call-cap" id="callCap"${cc ? '' : ' hidden'}><span></span></div>
      <div class="call-ctl">
        <button data-c="cam" id="callCam" aria-label="Camera">${ic('videocamOff')}</button>
        <button data-c="screen" id="callScr" aria-label="Share screen">${ic('screenShare')}</button>
        <button data-c="spk" id="callSpk" class="on" aria-label="Audio output">${ic('speaker')}</button>
        <button data-c="mute" id="callMute" aria-label="Mute">${ic('mic')}</button>
        <button data-c="end" class="end" aria-label="End call">${ic('callEnd')}</button>
      </div>`;
    document.body.appendChild(el);
    const bub = document.createElement('div'); bub.className = 'call-bub'; bub.hidden = true; bub.innerHTML = '<canvas></canvas>';
    document.body.appendChild(bub);
    call = { el, bub, cv: el.querySelector('.call-cv'), ocv: el.querySelector('.call-orb'), bcv: bub.querySelector('canvas'), vid: el.querySelector('.call-vid'),
      m: new Float32Array(16), a: new Float32Array(16), sm: new Float32Array(16), sa: new Float32Array(16),
      st: 'connecting', t0: 0, muted: false, out: 'speaker', cc, cap: '', pre: '', who: '', rot: 0, run: true, timer: 0, min: false,
      cam: false, facing: 'user', stream: null, frameT: 0, screen: false, screenAsk: false };
    el.onclick = (e) => {
      const b = e.target.closest('[data-c]'); if (!b) return;
      const a = b.dataset.c;
      if (a === 'end') endCall();
      else if (a === 'min') minimizeCall();
      else if (a === 'mute') { setMute(!call.muted); V.call('liveMute', call.muted); }
      else if (a === 'spk') V.call('liveSpeaker', call.out !== 'speaker');
      else if (a === 'cam') { if (call.cam) camOff(); else camOn(); }
      else if (a === 'flip') flipCam();
      else if (a === 'screen') { if (call.screen) V.call('liveScreen', false); else shareScreen(); }
      else if (a === 'cc') { call.cc = !call.cc; localStorage.setItem(LS.cc, call.cc ? '1' : '0'); b.classList.toggle('on', call.cc); $('#callCap').hidden = !call.cc; }
    };
    dragBubble(bub);
    call.timer = setInterval(setSt, 1000);
    if (o.adopt) {
      // already running natively: show it as the small bubble, the call screen opens on tap
      call.st = 'listening'; call.t0 = Date.now(); if (o.muted) setMute(true);
      call.min = true; el.hidden = true; bub.hidden = false; bub.classList.add('in');
    } else {
      V.call('callFull', true);
      V.call('liveStart', voice);
      if (window.Vidra && window.Vidra.phoneState && !localStorage.getItem('danav.ai.pcHint') && !phoneState().access) {
        localStorage.setItem('danav.ai.pcHint', '1');
        setTimeout(() => { if (call) V.toast('Tip: turn on Phone control in the Danav AI menu so Danav can use your phone for you'); }, 2500);
      }
    }
    requestAnimationFrame(draw);
  }
  // camera: preview on the call screen, one JPEG frame a second goes to the live session
  async function camOn() {
    const c = call; if (!c) return;
    if (V.call('camPerm') !== 'granted') { wantCam = true; V.call('requestCam'); return; }
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) { V.toast('The camera isn\'t available'); return; }
    if (c.screen) V.call('liveScreen', false);
    let s;
    try { s = await navigator.mediaDevices.getUserMedia({ audio: false, video: { facingMode: c.facing, width: { ideal: 1280 }, height: { ideal: 720 } } }); }
    catch (e) { V.toast('Couldn\'t open the camera'); return; }
    if (call !== c) { s.getTracks().forEach((t) => t.stop()); return; }
    if (c.stream) c.stream.getTracks().forEach((t) => t.stop());
    c.stream = s; c.cam = true;
    c.vid.srcObject = s; c.vid.play().catch(() => {});
    c.vid.classList.toggle('mirror', c.facing === 'user');
    clearInterval(c.frameT); c.frameT = setInterval(sendFrame, 1000);
    syncVideoUi();
  }
  function camOff() {
    const c = call; if (!c) return;
    clearInterval(c.frameT); c.frameT = 0;
    if (c.stream) c.stream.getTracks().forEach((t) => t.stop());
    c.stream = null; c.cam = false; c.vid.srcObject = null;
    syncVideoUi();
  }
  function flipCam() { if (!call || !call.cam) return; call.facing = call.facing === 'user' ? 'environment' : 'user'; camOn(); }
  const frameCv = document.createElement('canvas');
  function sendFrame() {
    const c = call; if (!c || !c.cam || c.vid.readyState < 2 || !c.vid.videoWidth) return;
    const vw = c.vid.videoWidth, vh = c.vid.videoHeight, k = Math.min(1, 768 / Math.max(vw, vh));
    frameCv.width = Math.round(vw * k); frameCv.height = Math.round(vh * k);
    frameCv.getContext('2d').drawImage(c.vid, 0, 0, frameCv.width, frameCv.height);
    const d = frameCv.toDataURL('image/jpeg', 0.6);
    V.call('liveVideo', d.slice(d.indexOf(',') + 1));
  }
  function shareScreen() {
    if (!call) return;
    if (call.cam) camOff();
    call.screenAsk = true;
    V.call('liveScreen', true);
  }
  function syncVideoUi() {
    const c = call; if (!c) return;
    c.el.classList.toggle('vid', c.cam);
    c.el.classList.toggle('sharing', c.screen);
    const cb = $('#callCam'); cb.classList.toggle('on', c.cam); cb.innerHTML = ic(c.cam ? 'videocam' : 'videocamOff');
    const sb = $('#callScr'); sb.classList.toggle('on', c.screen); sb.innerHTML = ic(c.screen ? 'screenStop' : 'screenShare');
  }
  function setMute(on) {
    if (!call) return;
    call.muted = on;
    const b = $('#callMute'); if (b) { b.classList.toggle('on', on); b.innerHTML = ic(on ? 'micOff' : 'mic'); }
    setSt();
  }
  function minimizeCall() {
    if (!call || call.min) return;
    call.min = true;
    V.call('callFull', false);
    call.el.classList.add('out');
    setTimeout(() => { if (call && call.min) { call.el.hidden = true; call.el.classList.remove('out'); } }, 280);
    call.bub.hidden = false; call.bub.classList.remove('in'); void call.bub.offsetWidth; call.bub.classList.add('in');
    if (!V.call('canOverlay') && !localStorage.getItem(LS.ovl) && window.Vidra && window.Vidra.askOverlay) {
      localStorage.setItem(LS.ovl, '1');
      V.confirm('Keep the call on top?', 'Allow Danav to show a small call bubble over other apps, so you can keep talking while you use them.', 'Allow').then((ok) => { if (ok) V.call('askOverlay'); });
    }
  }
  function restoreCall() {
    if (!call || !call.min) return;
    call.min = false; call.bub.hidden = true;
    V.call('callFull', true);
    call.el.hidden = false; call.el.classList.remove('out');
    call.el.style.animation = 'none'; void call.el.offsetWidth; call.el.style.animation = '';
    if (call.cam && call.vid.paused) call.vid.play().catch(() => {});
  }
  V.on('callOpen', () => { if (!call) adoptCall(); if (call) restoreCall(); });
  function dragBubble(b) {
    let sx = 0, sy = 0, ox = 0, oy = 0, moved = false, id = null;
    b.addEventListener('pointerdown', (e) => { id = e.pointerId; try { b.setPointerCapture(id); } catch (x) { /* */ } const r = b.getBoundingClientRect(); sx = e.clientX; sy = e.clientY; ox = r.left; oy = r.top; moved = false; b.style.transition = 'none'; });
    b.addEventListener('pointermove', (e) => {
      if (e.pointerId !== id) return;
      const dx = e.clientX - sx, dy = e.clientY - sy;
      if (!moved && Math.abs(dx) + Math.abs(dy) < 8) return;
      moved = true;
      b.style.left = Math.max(6, Math.min(innerWidth - b.offsetWidth - 6, ox + dx)) + 'px';
      b.style.top = Math.max(6, Math.min(innerHeight - b.offsetHeight - 6, oy + dy)) + 'px';
      b.style.right = 'auto'; b.style.bottom = 'auto';
    });
    b.addEventListener('pointerup', (e) => {
      if (e.pointerId !== id) return; id = null;
      b.style.transition = '';
      if (!moved) { restoreCall(); return; }
      const r = b.getBoundingClientRect();
      b.style.left = (r.left + r.width / 2 < innerWidth / 2 ? 10 : innerWidth - r.width - 10) + 'px';
    });
  }
  function fmt(s) { s = Math.floor(s); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); }
  function setSt(msg) {
    if (!call) return;
    const el = $('#callSt'); if (!el) return;
    const t = call.t0 ? fmt((Date.now() - call.t0) / 1000) : '';
    const s = call.st;
    el.textContent = s === 'connecting' ? 'Connecting…' : s === 'reconnecting' ? 'Reconnecting…' : s === 'error' ? (msg || call.err || 'Call failed')
      : s === 'bridge' ? 'On the call — Danav is talking for you' + (t ? ' · ' + t : '')
        : (call.muted ? 'Muted' : s === 'speaking' ? 'Speaking' : 'Listening') + (t ? ' · ' + t : '');
  }
  function endCall() {
    if (!call) return;
    const c = call; call = null; c.run = false; clearInterval(c.timer); clearInterval(c.frameT); clearTimeout(c.toolT);
    if (c.stream) c.stream.getTracks().forEach((t) => t.stop());
    V.call('liveStop');
    V.call('callFull', false);
    c.bub.remove();
    if (c.el.hidden) c.el.remove(); else { c.el.classList.add('out'); setTimeout(() => c.el.remove(), 300); }
  }
  V.on('live', (e) => {
    if (!call) return;
    if (e.t === 'lv') { for (let i = 0; i < 16; i++) { call.m[i] = e.m[i] || 0; call.a[i] = e.a[i] || 0; } return; }
    if (e.t === 'route') { call.out = e.out; const b = $('#callSpk'); if (b) { b.innerHTML = ic(outIcon(e.out)); b.classList.toggle('on', e.out === 'speaker'); b.setAttribute('aria-label', e.out === 'speaker' ? 'Speaker on' : e.out === 'earpiece' ? 'Phone earpiece' : 'Headphones'); } return; }
    if (e.t === 'mute') { setMute(!!e.on); return; }
    if (e.t === 'screen') { call.screen = !!e.on; if (call.screen) { if (call.cam) camOff(); V.toast('Danav AI can see your screen now'); } call.screenAsk = false; syncVideoUi(); return; }
    if (e.t === 'toast') { V.toast(e.msg); return; }
    if (e.t === 'tool') {
      const t = $('#callTool'); if (!t) return;
      clearTimeout(call.toolT);
      if (e.label) { t.lastChild.textContent = e.label; t.hidden = false; call.toolT = setTimeout(() => { t.hidden = true; }, 20000); }
      else call.toolT = setTimeout(() => { t.hidden = true; }, 1200);
      return;
    }
    if (e.t === 'state') {
      if (e.s === 'ended') { endCall(); return; }
      call.st = e.s; if (e.s === 'error') call.err = e.msg;
      if ((e.s === 'listening' || e.s === 'speaking') && !call.t0) call.t0 = Date.now();
      setSt(e.msg);
      if (e.s === 'error') { V.toast(e.msg || 'Call failed'); setTimeout(() => { if (call && call.st === 'error') endCall(); }, 2600); }
      return;
    }
    if (e.t === 'cap') {
      if (call.who !== e.who) { call.cap = ''; call.who = e.who; call.pre = ''; }
      call.cap = (call.cap + e.text).replace(/\s+/g, ' ').slice(-240);
      if (!(call.pre && call.pre.length > call.cap.length)) {
        const box = $('#callCap'); if (box) { box.classList.toggle('you', e.who === 'you'); box.firstChild.textContent = call.cap.trim(); }
      }
      return;
    }
    if (e.t === 'hear') {                       // words the call is hearing right now, before the sentence is finished
      call.who = 'you';
      call.pre = String(e.text || '').replace(/\s+/g, ' ').slice(-240);
      const box = $('#callCap'); if (box) { box.classList.add('you'); box.firstChild.textContent = call.pre.trim(); }
      return;
    }
    if (e.t === 'turn') { call.who = ''; call.pre = ''; }
  });

  function fit(cv) {
    const dpr = Math.min(2, devicePixelRatio || 1), W = cv.clientWidth, H = cv.clientHeight;
    if (cv.width !== Math.round(W * dpr) || cv.height !== Math.round(H * dpr)) { cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr); }
    const g = cv.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0);
    return [g, W, H];
  }
  // 3D spectrum: two counter-rotating rings of light bars seen in perspective — the outer warm ring is Danav AI's
  // voice, the inner cool ring is yours — around a glowing core, with a soft reflection on the floor.
  function draw(ts) {
    const c = call; if (!c || !c.run) return;
    let eA = 0, eM = 0;
    for (let i = 0; i < 16; i++) {
      c.sa[i] += (c.a[i] - c.sa[i]) * (c.a[i] > c.sa[i] ? 0.45 : 0.14);
      c.sm[i] += ((c.muted ? 0 : c.m[i]) - c.sm[i]) * (c.m[i] > c.sm[i] ? 0.45 : 0.14);
      eA += c.sa[i]; eM += c.sm[i];
    }
    eA /= 16; eM /= 16;
    const t = ts / 1000;
    c.rot += 0.0025 + eA * 0.012;
    if (c.min) { drawBub(c, c.bcv, eA, eM, t); requestAnimationFrame(draw); return; }
    if (c.cam) { if (!document.hidden) drawBub(c, c.ocv, eA, eM, t); requestAnimationFrame(draw); return; }
    if (document.hidden) { requestAnimationFrame(draw); return; }
    const [g, W, H] = fit(c.cv);
    const bg = g.createRadialGradient(W / 2, H * 0.45, 0, W / 2, H * 0.45, Math.max(W, H) * 0.75);
    bg.addColorStop(0, `rgba(${30 + eA * 60 | 0},${14 + eM * 30 | 0},${28 + eM * 50 | 0},1)`);
    bg.addColorStop(1, '#040405');
    g.globalCompositeOperation = 'source-over'; g.fillStyle = bg; g.fillRect(0, 0, W, H);
    const cx = W / 2, cy = H * 0.46, R = Math.min(W, H * 0.8) * 0.36;
    const tilt = 1.08, ct = Math.cos(tilt), st = Math.sin(tilt), D = R * 3.4;
    const band = (arr, k) => { const f = k * 15, i0 = Math.floor(f), i1 = Math.min(15, i0 + 1), fr = f - i0; return arr[i0] * (1 - fr) + arr[i1] * fr; };
    const bars = [];
    const ring = (n, rad, arr, rot, warm, idle) => {
      for (let j = 0; j < n; j++) {
        const u = j / n, th = u * Math.PI * 2 + rot;
        let k = (u * 2) % 2; if (k > 1) k = 2 - k;
        const v = Math.min(1, band(arr, k) * 1.25 + idle * (0.5 + 0.5 * Math.sin(t * 2.2 + th * 3)));
        const x = rad * Math.cos(th), z = rad * Math.sin(th);
        const zc = z * ct, f = D / (D + zc);
        const hue = warm ? 340 + 45 * (0.5 + 0.5 * Math.sin(th * 2 + t * 0.6)) : 185 + 75 * (0.5 + 0.5 * Math.sin(th * 2 - t * 0.5));
        bars.push({ sx: cx + x * f, sy: cy + z * st * f, f, zc, h: (6 + v * R * 0.95) * f, v, hue: hue % 360, warm });
      }
    };
    ring(64, R, c.sa, c.rot, true, 0.05 + (c.st === 'connecting' || c.st === 'reconnecting' ? 0.12 : 0));
    ring(48, R * 0.66, c.sm, -c.rot * 1.3, false, 0.04);
    bars.sort((p, q) => q.zc - p.zc);
    g.globalCompositeOperation = 'lighter'; g.lineCap = 'round';
    for (const b of bars) {
      const a = 0.10 * (0.4 + b.v) * (b.f * b.f);
      g.strokeStyle = `hsla(${b.hue},95%,60%,${a})`; g.lineWidth = (b.warm ? 4.2 : 3.4) * b.f;
      g.beginPath(); g.moveTo(b.sx, b.sy + 2); g.lineTo(b.sx, b.sy + b.h * 0.55); g.stroke();
    }
    const core = R * (0.26 + eA * 0.16 + eM * 0.10);
    const cg = g.createRadialGradient(cx, cy - R * 0.08, 0, cx, cy - R * 0.08, core * 2.6);
    const wa = Math.min(1, eA * 3 + 0.25), ma = Math.min(1, eM * 3);
    cg.addColorStop(0, `rgba(255,255,255,${0.55 + eA * 0.4})`);
    cg.addColorStop(0.18, `rgba(${255 - ma * 120 | 0},${90 + ma * 120 | 0},${90 + ma * 160 | 0},${0.55 * (wa + ma * 0.6)})`);
    cg.addColorStop(0.5, `rgba(${220 - ma * 140 | 0},${40 + ma * 80 | 0},${120 + ma * 100 | 0},${0.18 * (wa + ma)})`);
    cg.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = cg; g.beginPath(); g.arc(cx, cy - R * 0.08, core * 2.6, 0, Math.PI * 2); g.fill();
    for (const b of bars) {
      const light = 52 + b.v * 22, a = (0.35 + 0.65 * Math.min(1, b.v * 1.6 + 0.25)) * Math.min(1, b.f * b.f);
      g.strokeStyle = `hsla(${b.hue},95%,${light}%,${a})`; g.lineWidth = (b.warm ? 4.6 : 3.6) * b.f;
      g.beginPath(); g.moveTo(b.sx, b.sy); g.lineTo(b.sx, b.sy - b.h); g.stroke();
      if (b.v > 0.15) { g.fillStyle = `hsla(${b.hue},100%,85%,${a * 0.9})`; g.beginPath(); g.arc(b.sx, b.sy - b.h, 2.4 * b.f, 0, Math.PI * 2); g.fill(); }
    }
    requestAnimationFrame(draw);
  }
  // minimized: a small dark circle with a mini spectrum ring (AI warm, you cool)
  function drawBub(c, cv, eA, eM, t) {
    const [g, W, H] = fit(cv);
    const cx = W / 2, cy = H / 2, R = Math.min(W, H) / 2;
    g.clearRect(0, 0, W, H);
    g.globalCompositeOperation = 'source-over';
    g.fillStyle = '#0b0b0f'; g.beginPath(); g.arc(cx, cy, R, 0, Math.PI * 2); g.fill();
    const cr = R * (0.24 + eA * 0.25 + eM * 0.12);
    const cg = g.createRadialGradient(cx, cy, 0, cx, cy, cr * 1.9);
    cg.addColorStop(0, 'rgba(255,255,255,.95)'); cg.addColorStop(0.35, eA >= eM ? 'rgba(255,77,94,.8)' : 'rgba(77,195,255,.8)'); cg.addColorStop(1, 'rgba(0,0,0,0)');
    g.globalCompositeOperation = 'lighter';
    g.fillStyle = cg; g.beginPath(); g.arc(cx, cy, cr * 1.9, 0, Math.PI * 2); g.fill();
    const n = 28; g.lineCap = 'round'; g.lineWidth = Math.max(2, R * 0.075);
    for (let j = 0; j < n; j++) {
      const u = j / n, th = u * Math.PI * 2 + c.rot * 3;
      let k = u * 2; if (k > 1) k = 2 - k;
      const b = Math.min(15, Math.round(k * 15)), warm = c.sa[b] >= c.sm[b], v = Math.max(c.sa[b], c.sm[b]) + 0.04 * (1 + Math.sin(t * 3 + u * 12));
      const r0 = R * 0.52, r1 = r0 + R * (0.05 + Math.min(1, v) * 0.36);
      g.strokeStyle = warm ? `hsla(${(345 + 40 * k) % 360},92%,62%,.95)` : `hsla(${190 + 60 * k},92%,62%,.95)`;
      g.beginPath(); g.moveTo(cx + Math.cos(th) * r0, cy + Math.sin(th) * r0); g.lineTo(cx + Math.cos(th) * r1, cy + Math.sin(th) * r1); g.stroke();
    }
    g.globalCompositeOperation = 'source-over';
  }

  // ---------------------------------------------------------------- events
  V.on('ai', onAi);
  function onClick(e) {
    const cc = e.target.closest('[data-cc]');
    if (cc) {
      const code = cc.closest('.cb').querySelector('pre code');
      V.call('copy', code ? code.textContent : '');
      cc.innerHTML = ic('check') + '<span>Copied</span>';
      setTimeout(() => { if (cc.isConnected) cc.innerHTML = ic('copy') + '<span>Copy</span>'; }, 1500);
      return;
    }
    const a0 = e.target.closest('a[href]');
    if (a0 && root.contains(a0)) { e.preventDefault(); V.call('openBrowser', a0.getAttribute('href'), 'detect'); return; }
    const b = e.target.closest('[data-a]'); if (!b) return;
    const a = b.dataset.a;
    if (a === 'side') side(true);
    else if (a === 'sideClose') side(false);
    else if (a === 'new') newChat();
    else if (a === 'down') scrollEnd(true);
    else if (a === 'voices') voicesPage();
    else if (a === 'phone' || a === 'memory') phonePage();
    else if (a === 'call') { side(false); if (call) restoreCall(); else startCall(); }
    else if (a === 'settings') { side(false); settingsPage(); }
    else if (a === 'go') { const m = b.dataset.m; if (m === 'stop') stop(); else if (m === 'send') send(); else startCall(); }
    else if (a === 'attach') openPop(b, `<button data-p="image">${ic('image')}<span class="t">Photos</span></button><button data-p="file">${ic('attach')}<span class="t">Files</span></button>`, pick);
    else if (a === 'level') openPop(b, LEVELS.map((l) => `<button data-p="${l.id}" class="${l.id === level ? 'on' : ''}">${ic('brain')}<span class="t">${l.name}</span>${ic('check', 'ck')}</button>`).join(''), (id) => { level = id; localStorage.setItem(LS.level, id); renderLevel(); }, true, 'small');
    else if (a === 'unatt') { const it = pend.splice(+b.dataset.k, 1)[0]; if (it) V.call('aiDeleteAtt', JSON.stringify([it.id])); renderAtts(); }
    else if (a === 'think') { const i = +b.dataset.i, m = cur && cur.messages[i]; if (!m || isLive(m)) return; m.open = !m.open; b.parentElement.classList.toggle('open', m.open); }
    else if (a === 'copyMsg') { const m = cur && cur.messages[+b.dataset.i]; if (m) { V.call('copy', m.text); b.innerHTML = ic('check'); setTimeout(() => { if (b.isConnected) b.innerHTML = ic('copy'); }, 1500); } }
    else if (a === 'regen') regen(+b.dataset.i);
    else if (a === 'srcs') { const m = cur && cur.messages[+b.dataset.i]; if (m) showSources(m); }
    else if (a === 'openSrc') V.call('openBrowser', b.dataset.u, 'detect');
    else if (a === 'openChat') openChat(b.dataset.id);
    else if (a === 'chatMenu') {
      const c = chats.find((x) => x.id === b.dataset.id); if (!c) return;
      openPop(b, `<button data-p="rename">${ic('edit')}<span class="t">Rename</span></button><button data-p="delete" class="danger">${ic('trash')}<span class="t">Delete</span></button>`, (p) => (p === 'rename' ? rename(c) : remove(c)), false);
    }
  }

  V.backStack.push(() => {
    if (call && !call.min) { minimizeCall(); return true; }
    if (!root || root.hidden) return false;
    if (closePop()) return true;
    if (closePage()) return true;
    if (sideOpen) { side(false); return true; }
    return false;                       // AI mode is the app: let back leave as usual
  });

  // ---------------------------------------------------------------- open / close
  function syncSwitch(on) { const s = document.getElementById('aiSwitch'); if (s) { s.classList.toggle('on', on); s.setAttribute('aria-checked', on); } }
  function open(anim) {
    build();
    localStorage.setItem(LS.on, '1'); syncSwitch(true);
    document.body.classList.add('ai-on');
    root.hidden = false; root.classList.remove('leave');
    if (anim) { root.classList.remove('enter'); void root.offsetWidth; root.classList.add('enter'); }
    renderThread(); renderList(); syncGo();
    requestAnimationFrame(() => scrollEnd(false));
  }
  function close(anim) {
    localStorage.setItem(LS.on, '0'); syncSwitch(false);
    if (call && !call.min) minimizeCall();
    closePage(); closePop(); side(false);
    const done = () => { root.hidden = true; root.classList.remove('leave'); document.body.classList.remove('ai-on'); };
    if (anim) { root.classList.add('leave'); setTimeout(done, 230); } else done();
  }
  window.DanavAI = { open, close, md, phone: () => phonePage(), voice: () => voicesPage(), call: () => startCallFromApp(), adopt: () => adoptCall() };
  function startCallFromApp() { build(); if (document.body.classList.contains('ai-on')) root.hidden = false; else open(true); setTimeout(() => openCall({}), 120); }

  function init() {
    open(false);                       // this page *is* Danav AI: it always comes up open
    setTimeout(adoptCall, 400);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();
