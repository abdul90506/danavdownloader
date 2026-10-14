/* Danav — turns yt-dlp format lists into the quality menu (one entry per resolution).
 * Same rules as the extension: exact sizes only, video+audio paired for merging, best
 * compatibility (H.264 MP4) preferred when the user wants it. Works in the app and in node tests. */
(function (root) {
  'use strict';

  const isNone = (c) => c === 'none';
  const AUDIO_EXT = /^(m4a|mp3|opus|ogg|oga|aac|wav|flac|weba)$/i;
  const hasVideo = (f) => (f.vcodec ? !isNone(f.vcodec) : !AUDIO_EXT.test(f.ext || ''));
  const hasAudio = (f) => f.acodec ? !isNone(f.acodec) : true;   // unknown → assume muxed
  const exact = (f) => (typeof f.filesize === 'number' && f.filesize > 0 ? f.filesize : 0);
  const isDirect = (f) => /^https?$/.test(f.protocol || 'https');

  function codecRank(f, preferMp4) {
    const v = (f.vcodec || '').toLowerCase();
    if (!preferMp4) return v.startsWith('av01') ? 3 : /vp0?9/.test(v) ? 2 : /avc|h264/.test(v) ? 1 : 0;
    if (/avc|h264/.test(v)) return 4;
    if (/vp0?9/.test(v)) return 3;
    if (v.startsWith('av01')) return 2;
    if (/hev|hvc|h265/.test(v)) return 1;
    return 0;
  }

  function pOf(f) {
    const w = f.width || 0, h = f.height || 0;
    if (w && h) return Math.min(w, h);
    return h || 0;
  }

  function pickAudio(list, videoExt) {
    const auds = list.filter((f) => !hasVideo(f) && f.acodec && !isNone(f.acodec) && !/drc/i.test(f.format_id || '') && f.protocol !== 'mhtml');
    if (!auds.length) return null;
    // yt-dlp lists formats worst → best, and puts the original / default language higher
    const langPref = (f) => (typeof f.language_preference === 'number' ? f.language_preference : 0);
    const best = (arr) => arr.reduce((a, b) => (langPref(b) > langPref(a) || (langPref(b) === langPref(a) && (b.abr || b.tbr || 0) >= (a.abr || a.tbr || 0)) ? b : a));
    const m4a = auds.filter((f) => f.ext === 'm4a' || /mp4a|aac/.test(f.acodec || ''));
    const webm = auds.filter((f) => f.ext === 'webm' || /opus/.test(f.acodec || ''));
    if (videoExt === 'webm' && webm.length) return best(webm);
    if (m4a.length) return best(m4a);
    return best(auds);
  }

  function fmtLabel(p, f) {
    if (p) {
      const fps = f.fps && f.fps > 32 ? Math.round(f.fps) : '';
      return p + 'p' + fps;
    }
    if (f.format_note && !/dash|hls|default/i.test(f.format_note) && f.format_note.length < 16) return f.format_note;
    return (f.ext || 'video').toUpperCase();
  }

  // must match the yt-dlp option --merge-output-format mp4/webm/mkv
  function container(f, a) {
    const v = (f.ext || '').toLowerCase(), au = (a.ext || '').toLowerCase();
    if ((v === 'mp4' || v === 'm4v' || v === 'mov') && (au === 'm4a' || au === 'mp4' || au === 'aac')) return 'mp4';
    if (v === 'webm' && (au === 'webm' || au === 'weba' || au === 'opus')) return 'webm';
    return 'mkv';
  }

  /**
   * @param entry compact yt-dlp info (see Engine.compact)
   * @param prefs {preferMp4, quality}
   */
  function buildQualities(entry, prefs) {
    prefs = prefs || {};
    const preferMp4 = prefs.preferMp4 !== false;
    let fs = (entry.formats || []).filter((f) => f.protocol !== 'mhtml' && !/storyboard/i.test(f.format_note || '') && f.format_id);
    if (!fs.length && entry.url) fs = [{ format_id: entry.format_id || '0', url: entry.url, ext: entry.ext, vcodec: entry.vcodec, acodec: entry.acodec, width: entry.width, height: entry.height, filesize: entry.filesize, protocol: entry.protocol }];
    const drmAll = fs.length > 0 && fs.every((f) => f.has_drm === true);
    fs = fs.filter((f) => f.has_drm !== true);
    const vids = fs.filter(hasVideo);
    const groups = new Map();
    for (const f of vids) {
      const p = pOf(f);
      const key = p || ('x' + (f.format_note || f.ext || ''));
      const cur = groups.get(key);
      if (!cur || better(f, cur)) groups.set(key, f);
    }
    function better(a, b) {
      // 1) direct file (exact size, resumable) over HLS/DASH  2) codec preference  3) has audio  4) bitrate
      const d = (isDirect(a) ? 1 : 0) - (isDirect(b) ? 1 : 0); if (d) return d > 0;
      const c = codecRank(a, preferMp4) - codecRank(b, preferMp4); if (c) return c > 0;
      const s = ((a.dynamic_range || 'SDR') === 'SDR' ? 1 : 0) - ((b.dynamic_range || 'SDR') === 'SDR' ? 1 : 0); if (s) return s > 0;
      const au = (hasAudio(a) ? 1 : 0) - (hasAudio(b) ? 1 : 0); if (au) return au > 0;
      return (a.tbr || 0) >= (b.tbr || 0);
    }
    const out = [];
    for (const [, f] of groups) {
      const p = pOf(f);
      const q = { key: 'v:' + f.format_id, p, label: fmtLabel(p, f), height: f.height || 0, width: f.width || 0, codec: (f.vcodec || '').split('.')[0], merged: false, noAudio: false };
      if (hasAudio(f)) {
        q.format = f.format_id;
        q.streams = [{ id: f.format_id, size: exact(f), tbr: f.tbr || 0 }];
        q.ext = (f.ext || 'mp4').toLowerCase();
        q.url = isDirect(f) ? f.url || '' : '';
      } else {
        const a = pickAudio(fs, (f.ext || '').toLowerCase());
        if (a) {
          q.format = f.format_id + '+' + a.format_id + '/' + f.format_id + '+bestaudio/' + f.format_id;
          q.streams = [{ id: f.format_id, size: exact(f), tbr: f.tbr || f.vbr || 0 }, { id: a.format_id, size: exact(a), tbr: a.tbr || a.abr || 0 }];
          q.merged = true;
          q.ext = container(f, a);
        } else {
          q.format = f.format_id;
          q.streams = [{ id: f.format_id, size: exact(f), tbr: f.tbr || 0 }];
          q.noAudio = true;
          q.ext = (f.ext || 'mp4').toLowerCase();
          q.url = isDirect(f) ? f.url || '' : '';
        }
      }
      q.size = q.streams.every((s) => s.size > 0) ? q.streams.reduce((n, s) => n + s.size, 0) : 0;
      q.kind = isDirect(f) ? 'file' : /m3u8/.test(f.protocol || '') ? 'hls' : /dash/.test(f.protocol || '') ? 'dash' : 'stream';
      out.push(q);
    }
    out.sort((a, b) => (b.p - a.p) || (preferMp4 ? (b.ext === 'mp4') - (a.ext === 'mp4') : 0) || ((b.streams[0].tbr || 0) - (a.streams[0].tbr || 0)));
    // audio: every distinct quality of the main language, best first, plus MP3 (converted on the phone)
    const auds = fs.filter((f) => !hasVideo(f) && f.acodec && !isNone(f.acodec) && !/drc/i.test(f.format_id || ''));
    const lp = (f) => (typeof f.language_preference === 'number' ? f.language_preference : 0);
    const topLang = auds.reduce((m, f) => Math.max(m, lp(f)), -99);
    const seen = new Set(), alist = [];
    auds.filter((f) => lp(f) === topLang)
      .sort((x, y) => (y.abr || y.tbr || 0) - (x.abr || x.tbr || 0) || (isDirect(y) - isDirect(x)))
      .forEach((f) => {
        const codec = /opus/.test(f.acodec || '') || f.ext === 'webm' ? 'opus' : /mp4a|aac/.test(f.acodec || '') || f.ext === 'm4a' ? 'm4a' : (f.ext || 'audio');
        const kb = Math.round(f.abr || f.tbr || 0);
        const k = codec + ':' + Math.round(kb / 24);
        if (seen.has(k)) return; seen.add(k);
        alist.push({
          key: 'a:' + f.format_id, p: -1, label: (codec === 'opus' ? 'Opus' : codec === 'm4a' ? 'M4A' : codec.toUpperCase()) + (kb ? ' ' + kb + 'k' : ''), audioOnly: true, format: f.format_id,
          streams: [{ id: f.format_id, size: exact(f), tbr: kb }], size: exact(f),
          ext: codec === 'opus' ? ((f.ext || 'webm').toLowerCase()) : (f.ext || 'm4a').toLowerCase(), codec: (f.acodec || '').split('.')[0], url: isDirect(f) ? f.url || '' : '',
          abr: kb, kind: isDirect(f) ? 'file' : 'stream',
        });
      });
    // prefer M4A as the default audio pick (plays everywhere)
    alist.sort((x, y) => (y.abr - x.abr) || ((y.ext === 'm4a') - (x.ext === 'm4a')));
    const top = alist.slice(0, 4);
    const best = pickAudio(fs, 'mp4');
    if (best && !top.some((q) => q.format === best.format_id)) { const q = alist.find((x) => x.format === best.format_id); if (q) top.push(q); }
    const m4aBest = top.find((q) => q.ext === 'm4a');
    if (m4aBest) { top.splice(top.indexOf(m4aBest), 1); top.unshift(m4aBest); }
    out.push(...top);
    if (top.length) {
      const src = top[0];
      out.push({
        key: 'mp3:' + src.format, p: -1, label: 'MP3 192k', audioOnly: true, convert: 'mp3', kbps: 192, format: src.format,
        streams: src.streams, size: 0, ext: 'mp3', codec: 'mp3', url: '', abr: 192, kind: 'convert',
      });
    }
    return { qualities: out, drm: drmAll };
  }

  function defaultIndex(qs, pref) {
    const vids = qs.filter((q) => !q.audioOnly);
    if (pref === 'audio') { const i = qs.findIndex((q) => q.audioOnly); if (i >= 0) return i; }
    if (!vids.length) return 0;
    if (!pref || pref === 'best' || pref === 'audio') return qs.indexOf(vids[0]);
    const lim = parseInt(pref, 10);
    const ok = vids.find((q) => q.p && q.p <= lim);
    return qs.indexOf(ok || vids[vids.length - 1]);
  }

  function hostOf(u) { try { return new URL(u).hostname.replace(/^(www|m)\./, ''); } catch (e) { return ''; } }

  /** compact entry → card model */
  function toVideo(entry, prefs, ctx) {
    ctx = ctx || {};
    const { qualities, drm } = buildQualities(entry, prefs);
    let title = entry.title || '';
    if (!title || /^(manifest|master|index|playlist|videoplayback|media|video)$/i.test(title) || title === entry.id) title = ctx.pageTitle || title || 'Video';
    const best = qualities.find((q) => !q.audioOnly);
    let aspect = 0;
    if (entry.width && entry.height) aspect = entry.width / entry.height;
    else if (best && best.width && best.height) aspect = best.width / best.height;
    const live = entry.is_live === true || entry.live_status === 'is_live';
    return {
      key: (ctx.aid || '') + ':' + (entry.idx || 0),
      aid: ctx.aid || '', idx: entry.idx || 0,
      title, thumb: entry.thumbnail || '', duration: entry.duration || 0,
      uploader: entry.uploader || entry.channel || '',
      site: entry.extractor_key && entry.extractor_key !== 'Generic' ? entry.extractor_key : '',
      host: hostOf(entry.webpage_url || ctx.url || ''),
      pageUrl: entry.webpage_url || entry.original_url || ctx.url || '',
      aspect, live, drm, qualities,
      sel: defaultIndex(qualities, prefs && prefs.quality),
    };
  }

  /** yt-dlp error text → clear message + suggestion */
  function friendlyError(raw) {
    const s = String(raw || '').replace(/^ERROR:\s*/gm, '').trim();
    const t = s.toLowerCase();
    const r = (msg, hint, action) => ({ msg, hint: hint || '', action: action || '', raw: s.split('\n').filter(Boolean).pop() || '' });
    if (/unsupported url/.test(t)) return r('This link isn\'t a supported video page.', 'Open it in Danav\'s browser and play the video — Danav will detect the stream like the extension does.', 'browser');
    if (/drm/.test(t)) return r('This video is DRM-protected.', 'Protected streams (Netflix, Prime, Disney+ and similar) are encrypted and can\'t be downloaded.');
    if (/private video|this video is private/.test(t)) return r('This video is private.', 'Only the owner and people they share it with can watch it. If you have access, sign in to the site in Settings → Accounts.', 'signin');
    if (/sign in to confirm (your age|you)|age.restricted|inappropriate/.test(t)) return r('This video is age-restricted.', 'Sign in to the site in Settings → Accounts, then try again.', 'signin');
    if (/sign in to confirm you.?re not a bot|not a bot/.test(t)) return r('The site asked to confirm you\'re not a bot.', 'Sign in to the site in Settings → Accounts, or try again a bit later.', 'signin');
    if (/login|log in|logged.in|sign in|cookies|authentication|credentials|account/.test(t)) return r('This video needs you to be signed in.', 'Sign in to the site once in Settings → Accounts, then paste the link again.', 'signin');
    if (/members.only|join this channel|premium|subscri/.test(t)) return r('This video is for members or subscribers only.', 'Sign in with an account that has access in Settings → Accounts.', 'signin');
    if (/geo|not available in your country|your country|region/.test(t)) return r('This video isn\'t available in your country.', 'The site blocks it for your region.');
    if (/is live|live event|premieres in|will begin in|this live event/.test(t)) return r('This stream is live or hasn\'t started yet.', 'Live streams can be downloaded after they end.');
    if (/http error 429|too many requests|rate.?limit/.test(t)) return r('The site is limiting requests right now.', 'Wait a few minutes and try again, or sign in to the site in Settings → Accounts.', 'retry');
    if (/http error 404|not found|does not exist|removed|deleted|unavailable|no longer available/.test(t)) return r('This video isn\'t available any more.', 'It may have been removed, or the link is incomplete.');
    if (/http error 403|forbidden/.test(t)) return r('The site refused the download (403).', 'Try again. If it keeps failing, update the engine in Settings or open the link in Danav\'s browser.', 'retry');
    if (/no video formats|no formats|requested format is not available/.test(t)) return r('No downloadable video was found at this link.', 'Open it in Danav\'s browser and play the video so Danav can detect it.', 'browser');
    if (/unable to download webpage|timed out|name or service not known|network is unreachable|connection|temporary failure|getaddrinfo|ssl/.test(t)) return r('Couldn\'t reach the site.', 'Check your internet connection and try again.', 'retry');
    if (/not a valid url|is not a valid url/.test(t)) return r('That doesn\'t look like a valid link.', 'Copy the full link (starting with https://) and paste it again.');
    if (/no space left|enospc/.test(t)) return r('Your phone is out of storage.', 'Free up some space and try again.');
    if (/engine could not start|not initialized/.test(t)) return r('The download engine couldn\'t start.', 'Restart the app. If it keeps happening, reinstall Vidra.');
    return r('Couldn\'t get this video.', 'Try again, or update the engine in Settings — sites change often.', 'retry');
  }

  const api = { buildQualities, toVideo, friendlyError, defaultIndex, hostOf };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.VFormats = api;
})(typeof window !== 'undefined' ? window : globalThis);
