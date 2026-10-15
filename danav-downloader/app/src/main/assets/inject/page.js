/* Vidra page script — injected at document start into every frame of the in-app browser.
   1) detects the videos a page actually loads/plays (network, <video>, page data)
   2) hides ad elements (uBlock Origin Lite cosmetic filters, level Optimal+)
   3) removes YouTube's in-stream ads (level Optimal+) */
(function () {
  'use strict';
  if (window.__vidraPage) return;
  window.__vidraPage = 1;
  var A = window.VidraPage;
  if (!A) return;
  var cfg = {};
  try { cfg = JSON.parse(A.init(location.href, window === window.top) || '{}'); } catch (e) { cfg = {}; }
  var LVL = cfg.lvl | 0;

  // ------------------------------------------------------------------ 1. video detection
  var seen = Object.create(null), nSeen = 0;
  var MRX = /\.(m3u8|mpd)(\?|#|$)/i, VRX = /\.(mp4|webm|m4v|mov|mkv|flv|3gp|ogv)(\?|#|$)/i;
  var SEG = /\.(ts|m4s|aac|m4a|vtt|webvtt|srt|key|jpg|jpeg|png|gif|webp)(\?|#|$)|\/(seg|segment|chunk|frag)[-_]?\d+|[?&](range|bytestart)=/i;
  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return ''; } }
  function classify(u, ct) {
    ct = (ct || '').toLowerCase();
    if (/mpegurl/.test(ct) || /\.m3u8(\?|#|$)/i.test(u)) return 'hls';
    if (/dash\+xml/.test(ct) || /\.mpd(\?|#|$)/i.test(u)) return 'dash';
    if (/^video\//.test(ct) || VRX.test(u)) return SEG.test(u) && !VRX.test(u.split('?')[0]) ? '' : 'file';
    return '';
  }
  function report(u, kind, how) {
    if (!u || typeof u !== 'string') return;
    if (u.indexOf('blob:') === 0 || u.indexOf('data:') === 0) return;
    u = abs(u);
    if (!/^https?:/i.test(u)) return;
    if (seen[u] || nSeen > 300) return;
    seen[u] = 1; nSeen++;
    try { A.report(u, kind || '', how || '', location.href); } catch (e) { /* */ }
  }
  function scanText(t) {
    if (!t || t.length > 6e6) return;
    if (t.indexOf('m3u8') < 0 && t.indexOf('.mpd') < 0 && t.indexOf('.mp4') < 0 && t.indexOf('.webm') < 0) return;
    t = t.replace(/\\u002[fF]/g, '/').replace(/\\u0026/g, '&').replace(/\\\//g, '/');
    var re = /https?:\/\/[^\s"'<>\\()]+?\.(?:m3u8|mpd|mp4|webm|m4v|mov)(?:\?[^\s"'<>\\()]*)?(?=[\s"'<>\\()]|$)/gi, m, n = 0;
    while ((m = re.exec(t)) && n < 25) { n++; var k = classify(m[0]); if (k) report(m[0], k, 'data'); }
  }
  function inspect(u, ct, getText) {
    u = abs(u || '');
    var k = classify(u, ct);
    if (k) { report(u, k, 'net'); return; }
    ct = (ct || '').toLowerCase();
    if (/json|javascript|text\/plain|text\/html|xml/.test(ct) && getText) {
      try { Promise.resolve(getText()).then(scanText, function () {}); } catch (e) { /* */ }
    }
  }
  // fetch
  var of = window.fetch;
  if (of) {
    window.fetch = function (input) {
      var p = of.apply(window, arguments);
      try {
        var u = typeof input === 'string' ? input : (input && input.url) || String(input);
        p.then(function (r) {
          try {
            var ct = r.headers.get('content-type') || '';
            var len = +(r.headers.get('content-length') || 0);
            inspect(r.url || u, ct, len > 6e6 || /video|audio|octet|image|font/.test(ct) ? null : function () { return r.clone().text(); });
          } catch (e) { /* */ }
        }, function () {});
      } catch (e) { /* */ }
      return p;
    };
    try { window.fetch.toString = function () { return of.toString(); }; } catch (e) { /* */ }
  }
  // XHR
  var XP = XMLHttpRequest.prototype, oOpen = XP.open, oSend = XP.send;
  XP.open = function (m, u) { try { this.__vu = u; } catch (e) { /* */ } return oOpen.apply(this, arguments); };
  XP.send = function () {
    var x = this;
    try {
      x.addEventListener('load', function () {
        try {
          var ct = x.getResponseHeader('content-type') || '';
          var rt = x.responseType;
          inspect(x.responseURL || x.__vu, ct, function () {
            if (rt === '' || rt === 'text') return x.responseText;
            if (rt === 'json') return JSON.stringify(x.response);
            return '';
          });
        } catch (e) { /* */ }
      });
    } catch (e) { /* */ }
    return oSend.apply(this, arguments);
  };
  // <video>/<audio> sources
  try {
    var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
    if (d && d.set) {
      Object.defineProperty(HTMLMediaElement.prototype, 'src', {
        configurable: true, enumerable: d.enumerable,
        get: function () { return d.get.call(this); },
        set: function (v) { try { if (this.tagName === 'VIDEO') report(String(v), classify(String(v)) || 'file', 'el'); } catch (e) { /* */ } return d.set.call(this, v); },
      });
    }
  } catch (e) { /* */ }
  var mse = false;
  try {
    if (window.MediaSource) {
      var oAdd = MediaSource.prototype.addSourceBuffer;
      MediaSource.prototype.addSourceBuffer = function (type) { if (!mse && /video/.test(type)) { mse = true; try { A.signal('mse', location.href); } catch (e) { /* */ } } return oAdd.apply(this, arguments); };
    }
  } catch (e) { /* */ }
  function onMedia(e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO') return;
    var s = v.currentSrc || v.src;
    if (s && s.indexOf('blob:') !== 0) report(s, classify(s) || 'file', 'play');
    if (e.type === 'playing') { try { A.signal('playing', location.href); } catch (x) { /* */ } }
    var srcs = v.querySelectorAll ? v.querySelectorAll('source[src]') : [];
    for (var i = 0; i < srcs.length; i++) report(srcs[i].src, classify(srcs[i].src, srcs[i].type) || 'file', 'el');
  }
  document.addEventListener('loadedmetadata', onMedia, true);
  document.addEventListener('playing', onMedia, true);
  function scanDom() {
    try {
      var vs = document.querySelectorAll('video');
      for (var i = 0; i < vs.length; i++) {
        var s = vs[i].currentSrc || vs[i].src;
        if (s && s.indexOf('blob:') !== 0) report(s, classify(s) || 'file', 'el');
        var ss = vs[i].querySelectorAll('source[src]');
        for (var j = 0; j < ss.length; j++) report(ss[j].src, classify(ss[j].src, ss[j].type) || 'file', 'el');
      }
      if (window === window.top) {
        var m = document.querySelector('meta[property="og:video:secure_url"],meta[property="og:video:url"],meta[property="og:video"],meta[name="twitter:player:stream"]');
        if (m && m.content && classify(m.content)) report(m.content, classify(m.content), 'meta');
      }
    } catch (e) { /* */ }
  }
  document.addEventListener('DOMContentLoaded', scanDom);
  window.addEventListener('load', scanDom);
  setInterval(scanDom, 3000);

  // ------------------------------------------------------------------ 2. element hiding
  if (LVL >= 2) {
    var style = null, queue = [];
    var rules = function (sels) {
      if (!sels) return '';
      var out = [], list = sels.split('\n');
      for (var i = 0; i < list.length; i++) if (list[i]) out.push(list[i] + '{display:none!important}');
      return out.join('\n');
    };
    var addCss = function (sels) {
      var css = rules(sels);
      if (!css) return;
      if (!style || !style.isConnected) {
        style = document.createElement('style');
        style.setAttribute('data-vidra', '');
        (document.head || document.documentElement).appendChild(style);
      }
      style.appendChild(document.createTextNode(css + '\n'));
    };
    if (cfg.css) {
      if (document.documentElement) addCss(cfg.css);
      else document.addEventListener('readystatechange', function f() { document.removeEventListener('readystatechange', f); addCss(cfg.css); });
    }
    var tokens = Object.create(null), pending = [], timer = 0;
    var collect = function (el) {
      if (!el || el.nodeType !== 1) return;
      var id = el.id;
      if (id && typeof id === 'string' && !tokens['#' + id]) { tokens['#' + id] = 1; pending.push('#' + id); }
      var cl = el.classList;
      if (cl) for (var i = 0; i < cl.length; i++) { var t = '.' + cl[i]; if (!tokens[t]) { tokens[t] = 1; pending.push(t); } }
    };
    var flush = function () {
      timer = 0;
      if (!pending.length) return;
      var batch = pending.splice(0, 2000);
      try { addCss(A.low(batch.join(' '))); } catch (e) { /* */ }
      if (pending.length) timer = setTimeout(flush, 50);
    };
    var walk = function (root) {
      collect(root);
      var all = root.querySelectorAll ? root.querySelectorAll('[id],[class]') : [];
      for (var i = 0; i < all.length; i++) collect(all[i]);
      if (!timer) timer = setTimeout(flush, 30);
    };
    var mo = new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var m = muts[i];
        if (m.type === 'attributes') collect(m.target);
        else for (var j = 0; j < m.addedNodes.length; j++) { var n = m.addedNodes[j]; if (n.nodeType === 1) walk(n); }
      }
      if (!timer && pending.length) timer = setTimeout(flush, 60);
    });
    var start = function () {
      if (!document.documentElement) return;
      walk(document.documentElement);
      mo.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['id', 'class'] });
    };
    if (document.documentElement) start(); else document.addEventListener('DOMContentLoaded', start);
  }

  // ------------------------------------------------------------------ 3. YouTube ads
  if (LVL >= 2 && /(^|\.)youtube(-nocookie)?\.com$/.test(location.hostname)) {
    var KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
    var prune = function (o) {
      try {
        if (o && typeof o === 'object') {
          for (var i = 0; i < KEYS.length; i++) if (KEYS[i] in o) delete o[KEYS[i]];
          if (o.playerResponse) prune(o.playerResponse);
          if (o[2] && o[2].playerResponse) prune(o[2].playerResponse);
        }
      } catch (e) { /* */ }
      return o;
    };
    var jp = JSON.parse;
    JSON.parse = function () { return prune(jp.apply(this, arguments)); };
    var rj = Response.prototype.json;
    Response.prototype.json = function () { return rj.apply(this, arguments).then(prune); };
    try {
      var ipr;
      Object.defineProperty(window, 'ytInitialPlayerResponse', { configurable: true, get: function () { return ipr; }, set: function (v) { ipr = prune(v); } });
    } catch (e) { /* */ }
    setInterval(function () {
      try {
        var p = document.querySelector('.ad-showing video, .ad-interrupting video');
        if (p && isFinite(p.duration) && p.duration > 0) { p.muted = true; p.currentTime = p.duration; }
        var b = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytm-skip-ad-button');
        if (b) b.click();
      } catch (e) { /* */ }
    }, 500);
  }
})();
