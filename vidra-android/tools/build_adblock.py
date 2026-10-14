#!/usr/bin/env python3
"""Builds Vidra's ad-block data from uBlock Origin Lite rulesets (GPL-3.0, from the Vidra extension).
Output (app/src/main/assets/adblock/):
  net1.txt  basic tier  (ads, trackers, malware)       -> level >= 1
  net3.txt  complete tier (annoyances, mobile, spyware) -> level == 3
     lines:  D <host> [flags]   block host + subdomains   flags: t = third-party only, x=<a,b> excluded initiators
             A <host>           allow host
             P <host> <path>    block host + path prefix
             S <substring>      block if url contains
  css2.txt / css3.txt   cosmetic: H <generic selector> | L <#id/.class token>\t<selectors> | S <host>\t<selectors>
"""
import json, os, re, sys
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser('~/video-grabber/rulesets')
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), '..', 'app/src/main/assets/adblock')
T1 = ['ublock-filters', 'easylist', 'easyprivacy', 'pgl', 'ublock-badware', 'urlhaus-full']
T3 = ['adguard-mobile', 'adguard-spyware-url', 'annoyances-cookies', 'annoyances-overlays', 'annoyances-social',
      'annoyances-widgets', 'annoyances-others', 'annoyances-notifications', 'annoyances-ai']
HOSTRE = re.compile(r'^\|\|([a-z0-9.-]+)\^?$')
HOSTPATH = re.compile(r'^\|\|([a-z0-9.-]+)(/[^*^|]*)\^?\*?$')

def net(names):
    lines = set()
    for n in names:
        p = f'{SRC}/main/{n}.json'
        if not os.path.exists(p): continue
        for r in json.load(open(p)):
            a = r['action']['type']; c = r.get('condition', {})
            if c.get('initiatorDomains') or c.get('requestMethods') or c.get('regexFilter'): continue
            rt = c.get('resourceTypes')
            if rt and set(rt) <= {'main_frame'}: continue
            flags = []
            if c.get('domainType') == 'thirdParty': flags.append('t')
            if c.get('domainType') == 'firstParty': continue
            if c.get('excludedInitiatorDomains'): flags.append('x=' + ','.join(c['excludedInitiatorDomains'][:40]))
            fl = (' ' + ' '.join(flags)) if flags else ''
            if a == 'allow':
                if c.get('requestDomains') and not c.get('urlFilter') and not flags:
                    for d in c['requestDomains']: lines.add(f'A {d}')
                continue
            if a not in ('block', 'redirect'): continue
            if c.get('requestDomains') and not c.get('urlFilter'):
                for d in c['requestDomains']: lines.add(f'D {d}{fl}')
                continue
            uf = (c.get('urlFilter') or '').lower()
            if not uf or c.get('requestDomains'): continue
            m = HOSTRE.match(uf)
            if m: lines.add(f'D {m.group(1)}{fl}'); continue
            m = HOSTPATH.match(uf)
            if m and len(m.group(2)) > 1: lines.add(f'P {m.group(1)} {m.group(2)}'); continue
            s = uf.strip('*')
            if s.startswith('|') or '*' in s or '^' in s[:-1] or '|' in s: continue
            s = s.rstrip('^')
            if len(s) >= 6 and not flags: lines.add(f'S {s}')
    return sorted(lines)

def cosmetic(names):
    high, low, spec, exc = set(), {}, {}, {}
    tok = re.compile(r'^([#.])([A-Za-z0-9_-]+)')
    for n in names:
        g = f'{SRC}/scripting/generic/{n}.js'
        if os.path.exists(g):
            t = open(g).read()
            m = re.search(r'const lowlyGeneric = new Map\(/\* \d+ \*/(\[.*?\])\);\n', t, re.S)
            if m:
                for _, sels in json.loads(m.group(1)):
                    for s in sels.split(',\n'):
                        k = tok.match(s)
                        if k: low.setdefault(k.group(0), set()).add(s)
            m = re.search(r'const highlyGeneric = /\* \d+ \*/(".*?");\n', t, re.S)
            if m:
                for s in json.loads(m.group(1)).split(',\n'):
                    if s: high.add(s)
        sp = f'{SRC}/scripting/specific/{n}.json'
        if os.path.exists(sp):
            d = json.load(open(sp))
            for h, ref in zip(d['hostnames'], d['selectorListRefs']):
                for i in json.loads('[' + d['selectorLists'][ref] + ']'):
                    if i >= 0:
                        s = d['selectors'][i]
                        if not s.startswith('{'): spec.setdefault(h, set()).add(s)
                    else: exc.setdefault(h, set()).add(d['selectors'][~i])
    for h, ex in exc.items():
        if h in spec: spec[h] -= ex
    return {'high': sorted(high), 'low': {k: sorted(v) for k, v in low.items()}, 'spec': {h: sorted(v) for h, v in spec.items() if v}}

os.makedirs(OUT, exist_ok=True)
n1 = net(T1); n3 = [l for l in net(T3) if l not in set(n1)]
open(f'{OUT}/net1.txt', 'w').write('\n'.join(n1) + '\n')
open(f'{OUT}/net3.txt', 'w').write('\n'.join(n3) + '\n')
def css_txt(c, path):
    # H <selector> | L <token>\t<sel>\t<sel>… | S <host>\t<sel>\t<sel>…   (selectors never contain tabs)
    with open(path, 'w') as f:
        for x in c['high']: f.write('H ' + x + '\n')
        for k, v in c['low'].items(): f.write('L ' + k + '\t' + '\t'.join(v) + '\n')
        for k, v in c['spec'].items(): f.write('S ' + k + '\t' + '\t'.join(v) + '\n')
css_txt(cosmetic(['easylist', 'ublock-filters']), f'{OUT}/css2.txt')
css_txt(cosmetic(T3), f'{OUT}/css3.txt')
import gzip
for f in ['net1.txt', 'net3.txt', 'css2.txt', 'css3.txt']:
    data = open(f'{OUT}/{f}', 'rb').read(); os.remove(f'{OUT}/{f}')
    with gzip.open(f'{OUT}/{f}.gz', 'wb', 9) as g: g.write(data)
for f in sorted(os.listdir(OUT)): print(f, os.path.getsize(f'{OUT}/{f}'))
import collections
print(collections.Counter(l[0] for l in n1), collections.Counter(l[0] for l in n3))
